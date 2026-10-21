package game.truthordare

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SharedCard(val kind:String, val text:String)
data class IncomingCardBatch(val id:String, val endpointId:String, val sender:String, val cards:List<SharedCard>)

class CardShareManager(
    context:Context,
    private val serviceId:String,
    private val appId:String,
    private val allowedKinds:Set<String>,
    private val listener:Listener
) {
    interface Listener {
        fun onShareStatus(message:String)
        fun onHostReady(qrText:String)
        fun onIncomingBatch(batch:IncomingCardBatch)
        fun onSendComplete(count:Int)
        fun onShareError(message:String)
    }

    private enum class Role { NONE, HOST, GUEST }
    private val client=Nearby.getConnectionsClient(context.applicationContext)
    private val main=Handler(Looper.getMainLooper())
    private var role=Role.NONE
    private var sessionId=""
    private var sessionExpiresAt=0L
    private var hostKeys:KeyPair?=null
    private var scannedHostKey:PublicKey?=null
    private var guestKeys:KeyPair?=null
    private var guestCards:List<SharedCard> = emptyList()
    private var guestName="Player"
    private var requestedEndpoint:String?=null
    private var guestFinished=false
    private val seenNonces=mutableSetOf<String>()
    private val activeEndpoints=mutableSetOf<String>()
    private val strategy=Strategy.P2P_CLUSTER

    fun startHost():String {
        stop()
        role=Role.HOST
        sessionId=randomToken()
        sessionExpiresAt=System.currentTimeMillis()+20*60*1000L
        hostKeys=generateKeyPair()
        val qr=Uri.Builder().scheme("partycards").authority("join")
            .appendQueryParameter("v","1")
            .appendQueryParameter("app",appId)
            .appendQueryParameter("sid",sessionId)
            .appendQueryParameter("key",b64(hostKeys!!.public.encoded))
            .appendQueryParameter("exp",sessionExpiresAt.toString())
            .build().toString()
        client.startAdvertising(
            sessionId,
            serviceId,
            connectionCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            emitStatus("Ready. Ask players to scan this QR code.")
            main.post { listener.onHostReady(qr) }
        }.addOnFailureListener { fail("Could not start nearby sharing: ${safeMessage(it)}") }
        return qr
    }

    fun join(qrText:String, sender:String, cards:List<SharedCard>) {
        stop()
        try {
            val uri=Uri.parse(qrText)
            require(uri.scheme=="partycards" && uri.authority=="join") { "This is not a card-sharing QR code." }
            require(uri.getQueryParameter("v")=="1") { "This QR code uses an unsupported version." }
            require(uri.getQueryParameter("app")==appId) { "This QR code belongs to a different app." }
            sessionId=uri.getQueryParameter("sid").orEmpty()
            sessionExpiresAt=uri.getQueryParameter("exp")?.toLongOrNull() ?: 0L
            require(sessionId.length in 16..80 && sessionExpiresAt>System.currentTimeMillis()) { "This sharing session has expired." }
            scannedHostKey=decodePublicKey(uri.getQueryParameter("key").orEmpty())
            guestCards=validateCards(cards)
            require(guestCards.isNotEmpty()) { "Select at least one card to share." }
            guestName=sender.trim().take(40).ifBlank { "Player" }
            guestKeys=generateKeyPair()
            role=Role.GUEST
            emitStatus("Looking for the host nearby…")
            client.startDiscovery(serviceId,discoveryCallback,DiscoveryOptions.Builder().setStrategy(strategy).build())
                .addOnFailureListener { fail("Could not look for the host: ${safeMessage(it)}") }
        } catch(e:Exception) { fail(e.message ?: "That QR code could not be read.") }
    }

    fun resolve(batch:IncomingCardBatch) {
        activeEndpoints.remove(batch.endpointId)
        client.disconnectFromEndpoint(batch.endpointId)
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        role=Role.NONE
        sessionId=""
        sessionExpiresAt=0L
        hostKeys=null
        scannedHostKey=null
        guestKeys=null
        guestCards=emptyList()
        requestedEndpoint=null
        guestFinished=false
        seenNonces.clear()
        activeEndpoints.clear()
    }

    private val discoveryCallback=object:EndpointDiscoveryCallback(){
        override fun onEndpointFound(endpointId:String, info:DiscoveredEndpointInfo) {
            if(role!=Role.GUEST || requestedEndpoint!=null || info.endpointName!=sessionId)return
            requestedEndpoint=endpointId
            client.stopDiscovery()
            emitStatus("Host found. Creating an encrypted connection…")
            client.requestConnection("Card sender",endpointId,connectionCallback)
                .addOnFailureListener { requestedEndpoint=null;fail("Could not connect to the host: ${safeMessage(it)}") }
        }
        override fun onEndpointLost(endpointId:String) {
            if(role==Role.GUEST && requestedEndpoint==endpointId && !guestFinished)fail("The host is no longer nearby.")
        }
    }

    private val connectionCallback=object:ConnectionLifecycleCallback(){
        override fun onConnectionInitiated(endpointId:String, info:ConnectionInfo) {
            if(role==Role.NONE || (role==Role.HOST && activeEndpoints.size>=8)) {
                client.rejectConnection(endpointId)
                return
            }
            activeEndpoints.add(endpointId)
            client.acceptConnection(endpointId,payloadCallback)
        }
        override fun onConnectionResult(endpointId:String, result:ConnectionResolution) {
            if(result.status.statusCode!=ConnectionsStatusCodes.STATUS_OK){
                activeEndpoints.remove(endpointId)
                if(role==Role.GUEST)fail("The nearby connection was not accepted.")
                return
            }
            if(role==Role.GUEST)sendGuestBatch(endpointId)
        }
        override fun onDisconnected(endpointId:String) {
            activeEndpoints.remove(endpointId)
            if(role==Role.GUEST && !guestFinished)fail("The connection ended before the cards were received.")
        }
    }

    private val payloadCallback=object:PayloadCallback(){
        override fun onPayloadReceived(endpointId:String, payload:Payload) {
            val bytes=payload.asBytes() ?: return
            if(payload.type!=Payload.Type.BYTES || bytes.size>MAX_PAYLOAD_BYTES){client.disconnectFromEndpoint(endpointId);return}
            runCatching {
                val envelope=JSONObject(String(bytes,StandardCharsets.UTF_8))
                when(role){
                    Role.HOST->receiveGuestBatch(endpointId,envelope)
                    Role.GUEST->receiveAck(endpointId,envelope)
                    else->{ }
                }
            }.onFailure {
                if(role==Role.GUEST)fail("The encrypted response could not be verified.")
                else client.disconnectFromEndpoint(endpointId)
            }
        }
        override fun onPayloadTransferUpdate(endpointId:String, update:PayloadTransferUpdate) = Unit
    }

    private fun sendGuestBatch(endpointId:String) {
        try {
            val keys=guestKeys ?: error("Missing guest key")
            val key=deriveKey(keys,scannedHostKey ?: error("Missing host key"),sessionId)
            val nonce=randomToken()
            val cards=JSONArray().apply { guestCards.forEach { put(JSONObject().put("kind",it.kind).put("text",it.text)) } }
            val body=JSONObject().put("nonce",nonce).put("sentAt",System.currentTimeMillis()).put("sender",guestName).put("cards",cards)
            val encrypted=encrypt(key,body.toString().toByteArray(StandardCharsets.UTF_8),aad())
            val envelope=JSONObject().put("v",1).put("type","cards").put("sid",sessionId)
                .put("guestKey",b64(keys.public.encoded)).put("iv",encrypted.first).put("cipher",encrypted.second)
            val bytes=envelope.toString().toByteArray(StandardCharsets.UTF_8)
            require(bytes.size<=MAX_PAYLOAD_BYTES){"The selected cards are too large to send together."}
            emitStatus("Sending ${guestCards.size} encrypted cards…")
            client.sendPayload(endpointId,Payload.fromBytes(bytes)).addOnFailureListener { fail("Could not send the cards: ${safeMessage(it)}") }
        } catch(e:Exception){fail(e.message ?: "Could not encrypt the selected cards.")}
    }

    private fun receiveGuestBatch(endpointId:String,envelope:JSONObject) {
        require(role==Role.HOST && envelope.optInt("v")==1 && envelope.optString("type")=="cards")
        require(envelope.optString("sid")==sessionId && System.currentTimeMillis()<sessionExpiresAt)
        val key=deriveKey(hostKeys ?: error("Missing host key"),decodePublicKey(envelope.getString("guestKey")),sessionId)
        val clear=decrypt(key,envelope.getString("iv"),envelope.getString("cipher"),aad())
        val body=JSONObject(String(clear,StandardCharsets.UTF_8))
        val nonce=body.getString("nonce")
        require(nonce.length in 16..80 && seenNonces.add(nonce)){"Repeated card batch"}
        val sentAt=body.getLong("sentAt")
        require(kotlin.math.abs(System.currentTimeMillis()-sentAt)<10*60*1000L){"Expired card batch"}
        val array=body.getJSONArray("cards")
        val cards=validateCards(List(array.length()){i->val item=array.getJSONObject(i);SharedCard(item.optString("kind"),item.optString("text"))})
        val sender=body.optString("sender","Player").trim().take(40).ifBlank{"Player"}
        val encrypted=encrypt(key,JSONObject().put("received",cards.size).toString().toByteArray(StandardCharsets.UTF_8),aad())
        val ack=JSONObject().put("v",1).put("type","ack").put("sid",sessionId).put("iv",encrypted.first).put("cipher",encrypted.second)
        client.sendPayload(endpointId,Payload.fromBytes(ack.toString().toByteArray(StandardCharsets.UTF_8)))
        main.post { listener.onIncomingBatch(IncomingCardBatch(nonce,endpointId,sender,cards)) }
    }

    private fun receiveAck(endpointId:String,envelope:JSONObject) {
        require(envelope.optInt("v")==1 && envelope.optString("type")=="ack" && envelope.optString("sid")==sessionId)
        val key=deriveKey(guestKeys ?: error("Missing guest key"),scannedHostKey ?: error("Missing host key"),sessionId)
        val clear=JSONObject(String(decrypt(key,envelope.getString("iv"),envelope.getString("cipher"),aad()),StandardCharsets.UTF_8))
        val count=clear.getInt("received")
        guestFinished=true
        main.post { listener.onSendComplete(count) }
        main.postDelayed({client.disconnectFromEndpoint(endpointId)},500)
    }

    private fun validateCards(source:List<SharedCard>):List<SharedCard> {
        require(source.size in 1..MAX_CARDS){"Share between 1 and $MAX_CARDS cards at a time."}
        return source.map {
            val kind=it.kind.trim().lowercase()
            val text=it.text.trim()
            require(kind in allowedKinds && text.isNotBlank() && text.length<=MAX_CARD_CHARS){"A card has an invalid type or length."}
            SharedCard(kind,text)
        }
    }

    private fun generateKeyPair():KeyPair=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
    private fun decodePublicKey(value:String):PublicKey=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(value)))
    private fun deriveKey(own:KeyPair,other:PublicKey,sid:String):SecretKey {
        val agreement=KeyAgreement.getInstance("ECDH").apply{init(own.private);doPhase(other,true)}
        val digest=MessageDigest.getInstance("SHA-256").digest(agreement.generateSecret()+"$appId|$sid|cards-v1".toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(digest,"AES")
    }
    private fun encrypt(key:SecretKey,plain:ByteArray,aad:ByteArray):Pair<String,String>{
        val iv=ByteArray(12).also{SecureRandom().nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,iv));updateAAD(aad)}
        return b64(iv) to b64(cipher.doFinal(plain))
    }
    private fun decrypt(key:SecretKey,iv:String,data:String,aad:ByteArray):ByteArray=
        Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,unb64(iv)));updateAAD(aad);doFinal(unb64(data))}
    private fun aad()="$appId|$sessionId|cards-v1".toByteArray(StandardCharsets.UTF_8)
    private fun b64(value:ByteArray)=Base64.encodeToString(value,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun unb64(value:String)=Base64.decode(value,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun randomToken()=b64(ByteArray(18).also{SecureRandom().nextBytes(it)})
    private fun emitStatus(message:String)=main.post{listener.onShareStatus(message)}
    private fun fail(message:String)=main.post{listener.onShareError(message)}
    private fun safeMessage(error:Throwable)=error.localizedMessage?.take(120) ?: "Nearby service error"

    companion object {
        private const val MAX_CARDS=200
        private const val MAX_CARD_CHARS=500
        private const val MAX_PAYLOAD_BYTES=240_000
    }
}
