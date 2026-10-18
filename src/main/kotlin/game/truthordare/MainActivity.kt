package game.truthordare

import android.app.*
import android.os.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.Gravity
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import android.animation.ValueAnimator
import android.speech.tts.TextToSpeech
import android.view.animation.DecelerateInterpolator
import com.android.billingclient.api.*
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("truth_or_dare", MODE_PRIVATE) }
    private val players = mutableListOf<Player>()
    private val customCards = mutableListOf<Card>()
    private val decks = mutableMapOf<String, MutableList<Card>>()
    private var selectedLevel = "soft"
    private var useSystemColors = false
    private var themeMode = "system"
    private var limitTruths = true
    private var truthLimit = 2
    private var reverseDares = false
    private var reverseDareLimit = 2
    private var reverseDareTarget: Player? = null
    private var reverseDarePending = false
    private var premium = false
    private val offlinePremiumWindowMs = 3L * 24L * 60L * 60L * 1000L
    private val premiumCacheKeyAlias = "truth_or_dare_premium_entitlement_v1"
    private val ownedProductIds = mutableSetOf<String>()
    private var offerAfterPrivacy = false
    private var firstOfferShown = false
    private var restorePendingAfterConnection = false
    private var round = 1
    private var turn = 0
    private val remainingTurnIndices = mutableSetOf<Int>()
    private var hasActiveTurn = false
    private var activeTimer: CountDownTimer? = null
    private var ttsEnabled = false
    private var textToSpeech: TextToSpeech? = null
    private var currentCard: Card? = null
    private var homeScrollY = 0
    private lateinit var billingClient: BillingClient
    private val prices = mutableMapOf<String, String>()
    private val products = mutableMapOf<String, ProductDetails>()
    private val navigationStack = ArrayDeque<String>()
    private var currentScreen = "home"
    private val exportCardsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { exportCardsTo(it) } }
    private val importCardsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importCardsFrom(it) } }

    private fun openScreen(screen:String, render:()->Unit) {
        if(currentScreen != screen) navigationStack.addLast(currentScreen)
        currentScreen=screen
        render()
    }
    private fun goBack() {
        if(currentScreen in setOf("spinner","choices","card")) {
            activeTimer?.cancel()
            navigationStack.clear()
            currentScreen="home"
            showHome()
            return
        }
        if(navigationStack.isEmpty()) { finish(); return }
        val previous=navigationStack.removeLast()
        currentScreen=previous
        when(previous) {
            "home" -> showHome()
            "settings" -> showSettings()
            "appearance" -> showAppearance()
            "gameplay" -> showGameplay()
            "about" -> showAbout()
            "rules" -> showGameRules()
            "privacy" -> showPrivacyPolicy()
            "appInfo" -> showAppInformation()
            "choices" -> showChoices()
            "spinner" -> showHome()
            "card" -> currentCard?.let { showCard(it) } ?: showChoices()
            "custom" -> showCustomCards()
            "manage" -> manageCards()
            else -> showHome()
        }
    }
    @Deprecated("Handled for legacy Back navigation")
    override fun onBackPressed() { goBack() }

    private fun dynamicColor(light: Int, dark: Int, fallback: Int): Int = if (useSystemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getColor(if (isDark()) dark else light) else fallback
    private val background get() = dynamicColor(android.R.color.system_neutral1_10, android.R.color.system_neutral1_900, if (isDark()) Color.rgb(23,19,38) else Color.rgb(250,247,255))
    private val surface get() = dynamicColor(android.R.color.system_neutral1_0, android.R.color.system_neutral1_800, if (isDark()) Color.rgb(34,28,49) else if (isDark()) Color.rgb(34,28,49) else Color.WHITE)
    private val ink get() = dynamicColor(android.R.color.system_neutral1_900, android.R.color.system_neutral1_10, if (isDark()) Color.rgb(255,249,239) else Color.rgb(35,27,51))
    private val muted get() = dynamicColor(android.R.color.system_neutral2_700, android.R.color.system_neutral2_300, if (isDark()) Color.rgb(211,204,226) else Color.rgb(99,88,120))
    private val outline get() = dynamicColor(android.R.color.system_neutral2_300, android.R.color.system_neutral2_600, if (isDark()) Color.rgb(61,54,79) else Color.rgb(220,212,233))
    private val accent get() = dynamicColor(android.R.color.system_accent1_600, android.R.color.system_accent1_300, if (isDark()) Color.rgb(255,128,160) else Color.rgb(224,79,120))
    private val softAccent get() = ColorUtils.blendARGB(accent, background, if (isDark()) .22f else .14f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setupBilling()
        loadState()
        textToSpeech=TextToSpeech(this){ if(it==TextToSpeech.SUCCESS) configureSpeechVoice() }
        players.forEach { it.truthStreak = 0 }
        saveState()
        loadDecks()
        applyThemeAndHome()
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { runOnUiThread { goBack() } }
        if(!prefs.getBoolean("privacySeen",false)) window.decorView.post { showPrivacyNotice() }
    }

    override fun onDestroy() { activeTimer?.cancel(); textToSpeech?.stop(); textToSpeech?.shutdown(); super.onDestroy() }

    private fun configureSpeechVoice() {
        val engine=textToSpeech ?: return
        engine.language=Locale.getDefault()
        val femaleVoice=engine.voices?.filter { it.locale.language==Locale.getDefault().language && it.name.contains("female",true) && it.quality>=android.speech.tts.Voice.QUALITY_HIGH }?.maxByOrNull { it.quality }
        femaleVoice?.let { engine.voice=it }
        engine.setSpeechRate(.90f)
        engine.setPitch(1.02f)
    }

    private fun isDark(): Boolean = when (themeMode) { "dark" -> true; "light" -> false; else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun lp(w: Int = -1, h: Int = -2, m: Int = 0) = LinearLayout.LayoutParams(w, h).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }
    private fun weight(weight: Float) = LinearLayout.LayoutParams(0, -2, weight)
    private fun onAccent(): Int = if (ColorUtils.calculateLuminance(accent) > .55) Color.rgb(35,27,51) else if (isDark()) Color.rgb(34,28,49) else Color.WHITE
    private fun contrastOn(color:Int): Int = if (ColorUtils.calculateLuminance(color) > .45) Color.rgb(35,27,51) else Color.WHITE
    private fun shape(color: Int, radius: Int = 24, stroke: Int = 0, strokeColor: Int = outline) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke > 0) setStroke(dp(stroke), strokeColor) }
    private fun expressiveToggle(initial:Boolean, enabled:Boolean=true, onChanged:(Boolean)->Unit): FrameLayout {
        var checked=initial
        val holder=FrameLayout(this).apply { isClickable=enabled }
        val knob=TextView(this).apply { textSize=26f; gravity=Gravity.CENTER; typeface=android.graphics.Typeface.DEFAULT_BOLD }
        fun render(){
            holder.background=shape(if(checked)accent else ColorUtils.blendARGB(surface,outline,.62f),20,if(checked)0 else 2)
            knob.text=if(checked)"✓" else "×"
            knob.setTextColor(if(!checked && isDark()) Color.BLACK else if(checked)accent else ink)
            knob.background=shape(if(checked)surface else muted,16)
            knob.alpha=if(enabled)1f else .55f
            knob.layoutParams=FrameLayout.LayoutParams(dp(32),dp(32),if(checked)Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL).apply{setMargins(dp(4),0,dp(4),0)}
        }
        holder.addView(knob); render(); holder.setOnClickListener{ if(enabled){ checked=!checked;render();onChanged(checked) } }; return holder
    }

    private fun prepareWindow() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.decorView.setBackgroundColor(background)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark()
            isAppearanceLightNavigationBars = !isDark()
        }
    }
    private fun base(scroll: Boolean = true, dock: View? = null, dockFollowsIme: Boolean = true, dockMargin: Int = 16, hideScrollBar: Boolean = false): LinearLayout {
        prepareWindow()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(this@MainActivity.background)
            setPadding(if(scroll) 0 else dp(16), 0, if(scroll) 0 else dp(16), dp(16))
        }
        if (!scroll) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left+dp(16),bars.top+dp(4),bars.right+dp(16),bars.bottom+dp(16))
                insets
            }
            setContentView(root); return root
        }
        val sv = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = !hideScrollBar; setBackgroundColor(this@MainActivity.background); addView(root) }
        if (dock == null) {
            ViewCompat.setOnApplyWindowInsetsListener(sv) { view, insets ->
                val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top + dp(4), bars.right, bars.bottom)
                insets
            }
            setContentView(sv)
        } else {
            val dockHeight=max(dp(66),dock.minimumHeight)
            val frame = FrameLayout(this).apply {
                setBackgroundColor(this@MainActivity.background)
                addView(sv, FrameLayout.LayoutParams(-1, -1))
                addView(dock, FrameLayout.LayoutParams(-1, dockHeight, Gravity.BOTTOM).apply { setMargins(dp(dockMargin), 0, dp(dockMargin), 0) })
            }
            ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
                val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val keyboard=insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val bottom=max(bars.bottom,keyboard)
                sv.setPadding(bars.left, bars.top + dp(4), bars.right, bottom + dockHeight + dp(16))
                (dock.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = (if(dockFollowsIme) bottom else bars.bottom) + dp(8); dock.layoutParams = this }
                insets
            }
            sv.setOnScrollChangeListener { _, _, y, _, _ -> homeScrollY = y }
            setContentView(frame)
            sv.alpha=0f; sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener { override fun onPreDraw(): Boolean { sv.viewTreeObserver.removeOnPreDrawListener(this); sv.scrollTo(0, homeScrollY); sv.alpha=1f; return true } })
        }
        return root
    }
    private fun backButton(action: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(R.drawable.ic_back)
        setColorFilter(onAccent(), android.graphics.PorterDuff.Mode.SRC_IN)
        background = shape(accent, 23)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        contentDescription = "Back"
        setOnClickListener { action() }
    }
    private fun title(root: LinearLayout, value: String, back: Boolean = true, backAction: () -> Unit = { goBack() }, horizontalInset: Int = 16) {
        val top=FrameLayout(this)
        if(back) top.addView(backButton(backAction),FrameLayout.LayoutParams(dp(46),dp(46),Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(label(value,22,true,ink,Gravity.CENTER),FrameLayout.LayoutParams(-1,dp(46),Gravity.CENTER))
        root.addView(top,lp(-1,dp(46),0).apply { leftMargin=dp(horizontalInset); rightMargin=dp(horizontalInset) })
    }    private fun label(value: String, sp: Int = 14, bold: Boolean = false, color: Int = ink, gravity: Int = Gravity.START): TextView = TextView(this).apply { text = value; textSize = sp.toFloat(); setTextColor(color); this.gravity = gravity; typeface = android.graphics.Typeface.create("Google Sans Flex", if(bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL); includeFontPadding=false; if(bold) letterSpacing=.01f }
    private fun button(value: String, color: Int = accent, fg: Int = Color.WHITE, click: () -> Unit): TextView = TextView(this).apply { text=value; textSize=15f; typeface=android.graphics.Typeface.create("Google Sans Flex",android.graphics.Typeface.BOLD); setTextColor(fg); gravity=Gravity.CENTER; minHeight=dp(52); background=shape(color,26); setPadding(dp(16),0,dp(16),0); setOnClickListener { click() } }
    private fun card(root: LinearLayout, margin: Int = 10): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(surface,30,1); setPadding(dp(16),dp(16),dp(16),dp(16)); root.addView(this,lp(-1,-2,margin).apply { leftMargin=dp(16); rightMargin=dp(16) }) }
    private fun divider(root: LinearLayout, n: Int = 14) { root.addView(Space(this), lp(-1, dp(n),0)) }
    private fun mainMargin(view:View) { (view.layoutParams as? LinearLayout.LayoutParams)?.let { it.leftMargin=dp(16); it.rightMargin=dp(16); view.layoutParams=it } }

    private fun applyThemeAndHome() { showHome() }
    private fun showHome() {
        activeTimer?.cancel()
        val play=button("Let's play  →",accent,onAccent(),{ if(players.size>=2){ val incomplete=players.indexOfFirst{it.gender.isBlank() || it.preference.isBlank()}; if(incomplete>=0){ Toast.makeText(this,"Choose gender and interaction preferences for every player before playing.",Toast.LENGTH_LONG).show(); editPlayer(incomplete); return@button }; round=1; hasActiveTurn=false; remainingTurnIndices.clear(); openScreen("spinner"){showPlayerSpinner()} } }).apply { textSize=20f; minHeight=dp(66); isEnabled=players.size>=2; alpha=if(players.size>=2)1f else .5f }
        val input=EditText(this).apply { hint="Player name"; textSize=17f; setTextColor(ink); setHintTextColor(muted); setSingleLine(true); minHeight=dp(52); background=shape(this@MainActivity.background,18,1); setPadding(dp(15),0,dp(10),0) }
        val add=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        add.addView(input,weight(1f)); add.addView(Space(this),lp(dp(10),1,0)); add.addView(button("Add player +",accent,onAccent()){ val name=input.text.toString().trim(); if(name.isNotEmpty()){ players.add(Player(name)); input.text.clear(); editPlayer(players.lastIndex,true) } },lp(dp(120),dp(52),0))
        val root=base(dock=play,dockFollowsIme=false,hideScrollBar=true)
        val brand=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; orientation=LinearLayout.HORIZONTAL; setPadding(dp(12),dp(10),dp(10),dp(10)); background=shape(surface,30,1) }
        val mark=ImageView(this).apply { setImageResource(resources.getIdentifier("ic_launcher","drawable",packageName)); setColorFilter(accent,android.graphics.PorterDuff.Mode.SRC_IN) }
        brand.addView(mark,lp(dp(36),dp(36),0))
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),0,0,0) }
        copy.addView(label("Truth or Dare",18,true))
        copy.addView(label("MAKE TONIGHT A STORY",9,true,accent).apply { letterSpacing=.14f })
        brand.addView(copy,weight(1f))
        val gear=ImageButton(this).apply { setImageResource(R.drawable.ic_settings_gear); setColorFilter(ink,android.graphics.PorterDuff.Mode.SRC_IN); background=null; setPadding(dp(8),dp(8),dp(8),dp(8)); contentDescription="Settings"; setOnClickListener { openScreen("settings"){showSettings()} } }; brand.addView(gear,lp(dp(46),dp(46),0))
        root.addView(brand,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(16),dp(4),dp(16),dp(15)) })

        val playersPanel=card(root,0).apply { setPadding(dp(16),dp(16),dp(16),dp(16)) }; (playersPanel.layoutParams as LinearLayout.LayoutParams).setMargins(dp(16),0,dp(16),0)
        playersPanel.addView(label("PLAYERS: ${players.size}",13,true,muted))
        playersPanel.addView(label("Add everyone, then edit their interaction preferences any time.",15,false,muted).apply { setPadding(0,dp(8),0,dp(8)) })
        if(players.size<2) playersPanel.addView(label(if(players.isEmpty()) "No players added yet. Add at least 2 players to start the game." else "Add 1 more player to start the game.",14,true,accent).apply { setPadding(0,0,0,dp(10)); setLineSpacing(dp(2).toFloat(),1.03f) })
        val playerGrid=GridLayout(this).apply { columnCount=2; useDefaultMargins=false }
        players.forEachIndexed { index, p ->
            val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(8),dp(8),dp(8)); background=shape(surface,16,1) }
            val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            info.addView(label(p.name,13,true).apply { textSize=13.65f; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
            if(p.preference=="Men & women" || p.preference=="Specific players") {
                info.addView(label("${p.gender} ·",10,false,muted).apply { setPadding(0,dp(3),0,0) })
                info.addView(label(p.preference,10,false,muted))
            } else info.addView(label("${p.gender} · ${p.preference}",10,false,muted).apply { setPadding(0,dp(3),0,0) })
            row.addView(info,weight(1f))
            row.addView(cardAction(R.drawable.ic_edit,"Edit ${p.name}"){editPlayer(index)},LinearLayout.LayoutParams(dp(42),dp(42)).apply { leftMargin=dp(4) })
            val position=playerGrid.childCount
            playerGrid.addView(row,GridLayout.LayoutParams().apply { width=0;height=dp(86);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f); val left=if(position%2==0)0 else dp(4);val right=if(position%2==0)dp(4) else 0;setMargins(left,dp(2),right,dp(2)) })
        }
        playersPanel.addView(playerGrid,lp(-1,-2,0))
        playersPanel.addView(add,lp(-1,dp(52),7))

        divider(root,15)
        val levels=card(root,0).apply { setPadding(dp(16),dp(12),dp(16),dp(12)) }; (levels.layoutParams as LinearLayout.LayoutParams).setMargins(dp(16),0,dp(16),0)
        levels.addView(label("CHOOSE A LEVEL",13,true,muted).apply { setPadding(0,0,0,dp(7)) })
        val grid=GridLayout(this).apply { columnCount=2; useDefaultMargins=false }
        val lockedFill=ColorUtils.blendARGB(surface,outline,.55f)
        fun levelCell(key:String, title:String, detail:String, locked:Boolean) {
            val unavailable=locked&&!premium
            val selected=selectedLevel==key&&!unavailable
            val cell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER; background=shape(if(selected)accent else if(unavailable)lockedFill else surface,22,1); isClickable=true;setOnClickListener { if(unavailable)showPremium() else {selectedLevel=key;showHome()} } }
            cell.addView(label(title,13,true,if(selected)onAccent() else if(unavailable)muted else ink,Gravity.CENTER).apply { textSize=13.65f })
            cell.addView(label(detail,11,false,if(selected)ColorUtils.setAlphaComponent(onAccent(),255) else muted,Gravity.CENTER).apply { textSize=11.55f; setPadding(0,dp(2),0,0) })
            val gp=GridLayout.LayoutParams().apply { width=0;height=dp(66);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f); val left=if(grid.childCount%2==0)0 else dp(4);val right=if(grid.childCount%2==0)dp(4) else 0;setMargins(left,dp(2),right,dp(2)) };grid.addView(cell,gp)
        }
        levelCell("soft","Soft 😎","Easy & silly",false);levelCell("hot","Hot 😍","Flirty & bold",false);levelCell("hard","Hard 😉",if(premium)"Confident & intimate" else "Premium  🔒",true);levelCell("extreme","Extreme 😈",if(premium)"Bold & daring" else "Premium  🔒",true)
        val customLocked=!premium
        val customSelected=selectedLevel=="mydares"
        val custom=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; isClickable=true; background=shape(if(customSelected)accent else if(customLocked)lockedFill else surface,22,1); setOnClickListener { if(customLocked)showPremium() else {selectedLevel="mydares";openScreen("custom"){showCustomCards()}} } }
        custom.addView(label("My truths and dares ✏️",13,true,if(customSelected)onAccent() else if(customLocked)muted else ink,Gravity.CENTER).apply { textSize=13.65f })
        custom.addView(label(if(customLocked)"Premium  🔒" else "Your custom cards",11,false,if(customSelected)ColorUtils.setAlphaComponent(onAccent(),255) else muted,Gravity.CENTER).apply { textSize=11.55f; setPadding(0,dp(2),0,0) })
        grid.addView(custom,GridLayout.LayoutParams().apply{width=-1;height=dp(66);columnSpec=GridLayout.spec(0,2);setMargins(0,dp(2),0,dp(2))});levels.addView(grid)
        if(!premium) root.addView(label("Hard, Extreme and My truths and dares unlock with a one-time Play purchase.",12,false,muted,Gravity.CENTER).apply{setPadding(0,dp(3),0,0)},lp(-1,-2,0))
    }
    private fun editPlayer(index:Int, isNew:Boolean=false) {
        val player=players[index]; val dialog=Dialog(this); var committed=!isNew
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(18),dp(16),dp(18));background=shape(this@MainActivity.background,22,1)};dialog.setContentView(form);dialog.setOnDismissListener{if(isNew && !committed && index in players.indices && players[index]===player)players.removeAt(index);showHome()}
        val top=FrameLayout(this);top.addView(backButton{dialog.dismiss()},FrameLayout.LayoutParams(dp(46),dp(46),Gravity.START or Gravity.CENTER_VERTICAL));top.addView(label("Edit player",18,true,ink,Gravity.CENTER),FrameLayout.LayoutParams(-1,dp(46),Gravity.CENTER));form.addView(top,lp(-1,dp(46),0))
        form.addView(label("You can change this later, including who they want to interact with.",12,false,muted).apply{setPadding(0,dp(8),0,dp(12))});form.addView(label("Name",12,true));val name=EditText(this).apply{setText(player.name);setTextColor(ink);setSingleLine(true);textSize=17f;background=shape(this@MainActivity.background,14,1);setPadding(dp(12),0,dp(12),0)};form.addView(name,lp(-1,dp(50),3))
        fun choices(values:List<String>, initial:String, columns:Int=1, change:(String)->Unit={}):Pair<ViewGroup,()->String>{var selected=initial;val host=if(columns==1)LinearLayout(this).apply{gravity=Gravity.CENTER}else GridLayout(this).apply{columnCount=2};val all=mutableListOf<TextView>();values.forEachIndexed{i,v->val b=button(v,if(v==selected)accent else surface,if(v==selected)onAccent()else ink){};b.textSize=13f;b.minHeight=dp(46);b.setOnClickListener{selected=v;all.forEach{x->val on=x.text==v;x.background=shape(if(on)accent else surface,22,1);x.setTextColor(if(on)onAccent()else ink)};change(v)};all.add(b);if(host is LinearLayout)host.addView(b,LinearLayout.LayoutParams(0,dp(46),1f).apply{if(i<values.lastIndex)rightMargin=dp(7)})else host.addView(b,GridLayout.LayoutParams().apply{width=0;height=dp(46);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(if(i%2==0)0 else dp(4),dp(2),if(i%2==0)dp(4)else 0,dp(2))})};return host to {selected}}
        form.addView(label("Gender",12,true).apply{setPadding(0,dp(8),0,dp(4))});val(gender,getGender)=choices(listOf("Man","Woman"),player.gender);form.addView(gender)
        val calibration=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=shape(if(premium)surface else ColorUtils.blendARGB(surface,outline,.42f),16,1);setPadding(dp(16),dp(8),dp(16),dp(8));alpha=if(premium)1f else .55f};val calibrationHead=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val comfortTitle=label("Comfort-zone calibration · ${player.comfort}/10"+if(!premium)"  🔒" else "",12,true,if(premium)ink else muted);calibrationHead.addView(comfortTitle,weight(1f));var comfortEnabled=player.comfortEnabled;calibrationHead.addView(expressiveToggle(comfortEnabled&&premium,premium){comfortEnabled=it},lp(dp(58),dp(36),0));calibration.addView(calibrationHead);calibration.addView(label(if(premium)"Adjust easier prompts for this player." else "Premium feature · unlocks per-player prompt adjustment.",10,false,muted));val comfort=SeekBar(this).apply{max=9;progress=player.comfort-1;progressTintList=android.content.res.ColorStateList.valueOf(accent);thumbTintList=android.content.res.ColorStateList.valueOf(accent);backgroundTintList=android.content.res.ColorStateList.valueOf(outline);minimumHeight=dp(34);isEnabled=premium};comfort.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean){comfortTitle.text="Comfort-zone calibration · ${v+1}/10"+if(!premium)"  🔒" else ""};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}});calibration.addView(comfort,lp(-1,dp(26),0));calibration.addView(label("1 · Wholesome & soft                              10 · Chaos & spicy",10,false,muted,Gravity.CENTER));form.addView(calibration,lp(-1,-2,6))
        form.addView(label("Wants to interact with",12,true).apply{setPadding(0,dp(8),0,dp(4))});val refreshers=mutableListOf<()->Unit>();val(pref,getPref)=choices(listOf("Men","Women","Men & women","Specific players"),player.preference,2){refreshers.forEach{it()}};form.addView(pref);form.addView(label("Choose specific players to decide exactly who this player can be matched with.",12,false,muted).apply{setPadding(0,dp(8),0,dp(6))})
        val selected=player.specificPlayers.toMutableSet();val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(4),dp(16),dp(4))};val box=ScrollView(this).apply{addView(list);background=shape(surface,14,1);isFillViewport=true;setPadding(0,0,dp(2),0)}
        players.filterIndexed{i,_->i!=index}.forEach{p->val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;background=shape(this@MainActivity.background,14,1);setPadding(dp(10),0,dp(10),0)};val check=CheckBox(this).apply{buttonTintList=android.content.res.ColorStateList.valueOf(accent);isClickable=false};val copy=label("${p.name}  ·  ${p.gender}",13,true);row.addView(check,lp(dp(40),dp(40),0));row.addView(copy,weight(1f));fun render(){val yes=getPref()=="Specific players";check.isChecked=p.name in selected;row.alpha=if(yes)1f else .38f;copy.setTextColor(if(yes)ink else muted)};row.setOnClickListener{if(getPref()=="Specific players"){if(p.name in selected)selected.remove(p.name)else selected.add(p.name);render()}};refreshers.add(::render);render();list.addView(row,lp(-1,dp(46),2))};val otherPlayers=players.size-1;if(otherPlayers>0){ form.addView(box,LinearLayout.LayoutParams(-1,dp(if(otherPlayers>=3)154 else otherPlayers*48+12)).apply{topMargin=dp(4);bottomMargin=dp(6)}) }
        val validation=label("",11,true,Color.rgb(186,26,26)).apply{visibility=View.GONE};form.addView(validation,lp(-1,-2,0))
        val actions=LinearLayout(this);actions.addView(button("Remove player",Color.rgb(186,26,26),Color.WHITE){players.removeAt(index);saveState();dialog.dismiss()},LinearLayout.LayoutParams(0,dp(52),1f).apply{rightMargin=dp(6)});actions.addView(button("Save player",accent,onAccent()){if(getGender().isBlank() || getPref().isBlank()){validation.text="Choose both gender and interaction preferences to save this player.";validation.visibility=View.VISIBLE;return@button};player.name=name.text.toString().trim().ifEmpty{player.name};player.gender=getGender();player.preference=getPref();player.comfort=comfort.progress+1;player.comfortEnabled=comfortEnabled&&premium;player.specificPlayers.clear();player.specificPlayers.addAll(selected);committed=true;saveState();dialog.dismiss()},LinearLayout.LayoutParams(0,dp(52),1f).apply{leftMargin=dp(6)});form.addView(actions,lp(-1,dp(52),0));dialog.show();dialog.window?.apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));setLayout((resources.displayMetrics.widthPixels*.90f).toInt(),if(players.size<=3)WindowManager.LayoutParams.WRAP_CONTENT else (resources.displayMetrics.heightPixels*.88f).toInt());setGravity(Gravity.CENTER);attributes=attributes.apply{dimAmount=.4f};addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)}
    }
    private fun findRadio(group:RadioGroup):String { val id=group.checkedRadioButtonId; return group.findViewById<RadioButton>(id)?.text?.toString() ?: "" }

    private fun showSettings() {
        val root=base(); title(root,"Settings")
        divider(root,7)
        settingsRow(root,"appearance","Appearance","Themes and colors",{openScreen("appearance"){showAppearance()}})
        settingsRow(root,"gameplay","Gameplay","Rules and preferences",{openScreen("gameplay"){showGameplay()}})
        settingsRow(root,"about","General Information","Privacy policy, Game rules, App info",{openScreen("about"){showAbout()}})
    }    private fun settingsRow(root:LinearLayout,icon:String,name:String,desc:String,action:()->Unit,showArrow:Boolean=true){
        val c=card(root,8).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; background=shape(surface,24,1); setPadding(dp(18),dp(8),dp(18),dp(8)); isClickable=true; setOnClickListener{action()} }; mainMargin(c)
        val iconBackground=when(icon){ "appearance"->Color.rgb(54,31,99); "gameplay"->Color.rgb(20,62,92); "about"->Color.rgb(89,18,70); else->accent }
        val iconForeground=when(icon){ "appearance"->Color.rgb(190,164,255); "gameplay"->Color.rgb(66,196,255); "about"->Color.rgb(255,141,207); else->onAccent() }
        val iconRes=when(icon){ "appearance"->R.drawable.ic_wallpaper; "gameplay"->R.drawable.ic_gameplay; "about"->R.drawable.ic_info; else->R.drawable.ic_info }
        val iconHolder=FrameLayout(this).apply { background=shape(iconBackground,24) }
        iconHolder.addView(ImageView(this).apply { setImageResource(iconRes); setColorFilter(iconForeground,android.graphics.PorterDuff.Mode.SRC_IN); contentDescription=name },FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER))
        c.addView(iconHolder,lp(dp(48),dp(48),0))
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),0,0,0) }
        copy.addView(label(name,16,true)); copy.addView(label(desc,12,false,muted)); c.addView(copy,weight(1f))
        if(showArrow) c.addView(label("›",28,false,muted,Gravity.CENTER),lp(dp(28),dp(48),0))
    }    private fun showAppearance(){
        val root=base(); title(root,"Appearance")
        val picker=LinearLayout(this).apply { gravity=Gravity.CENTER; setPadding(dp(5),dp(5),dp(5),dp(5)); background=shape(surface,22,1) }
        fun themeOption(key:String, caption:String, icon:Int) {
            val active=themeMode==key
            val foreground=if(active) onAccent() else muted
            val option=LinearLayout(this).apply {
                gravity=Gravity.CENTER
                isClickable=true
                background=shape(if(active)accent else Color.TRANSPARENT,20)
                setPadding(dp(5),0,dp(5),0)
                setOnClickListener { themeMode=key; saveState(); showAppearance() }
            }
            option.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(foreground,android.graphics.PorterDuff.Mode.SRC_IN)
            },LinearLayout.LayoutParams(dp(24),dp(24)).apply { leftMargin=dp(10) })
            option.addView(label(caption,13,true,foreground,Gravity.CENTER).apply { setPadding(dp(4),0,0,0) },LinearLayout.LayoutParams(0,dp(48),1f))
            picker.addView(option,LinearLayout.LayoutParams(0,dp(48),1f))
        }
        themeOption("system","System",R.drawable.ic_theme_system)
        themeOption("dark","Dark",R.drawable.ic_theme_dark)
        themeOption("light","Light",R.drawable.ic_theme_light)
        root.addView(picker,lp(-1,-2,16)); mainMargin(picker)
        val c=card(root,10).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }; mainMargin(c)
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        copy.addView(label("Use Android system colors",16,true))
        copy.addView(label("Match the app to your device palette.",12,false,muted))
        c.addView(copy,weight(1f))
        c.addView(expressiveToggle(useSystemColors){ on->useSystemColors=on;saveState();showAppearance() },lp(dp(64),dp(40),0))
    }    private fun showGameplay(){
        val root=base(); title(root,"Gameplay"); val locked=!premium
        val c=card(root,16); mainMargin(c)
        val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        copy.addView(label("Limit consecutive truths"+if(locked)"  🔒" else "",16,true,if(locked)muted else ink))
        copy.addView(label(if(locked)"Premium feature · requires Premium Levels." else "Require a dare after the chosen number of truths.",12,false,muted))
        heading.addView(copy,weight(1f))
        heading.addView(expressiveToggle(!locked&&limitTruths,!locked){ on->limitTruths=on;saveState() },lp(dp(64),dp(40),0))
        c.addView(heading)
        c.addView(label("Truths allowed before a dare",13,true,if(locked)muted else ink).apply { setPadding(0,dp(18),0,dp(8)) })
        val values=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; alpha=if(locked).48f else 1f }
        (1..5).forEach { value ->
            val active=value==truthLimit
            val choice=button(value.toString(),if(active&&!locked)accent else surface,if(active&&!locked)onAccent() else muted) { if(!locked){ truthLimit=value; saveState(); showGameplay() } }.apply { minHeight=dp(44); textSize=15f; isEnabled=!locked; background=shape(if(active&&!locked)accent else surface,18,1) }
            values.addView(choice,LinearLayout.LayoutParams(0,dp(44),1f).apply { if(value>1) leftMargin=dp(5) })
        }
        c.addView(values)
        c.addView(label(if(locked)"Unlock Premium Levels to choose a custom truth limit." else "Each player can choose up to $truthLimit truth${if(truthLimit==1)"" else "s"} before Dare is required.",12,false,muted).apply { setPadding(0,dp(10),0,dp(2)) })
        val reverse=card(root,10); mainMargin(reverse)
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val reverseCopy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};reverseCopy.addView(label("Reverse Dare"+if(locked)"  🔒" else "",16,true,if(locked)muted else ink));reverseCopy.addView(label(if(locked)"Premium feature · requires Premium Levels." else "After consecutive dares, allow the player to assign a dare to another random player.",12,false,muted));head.addView(reverseCopy,weight(1f));head.addView(expressiveToggle(!locked&&reverseDares,!locked){on->reverseDares=on;saveState();showGameplay()},lp(dp(64),dp(40),0));reverse.addView(head)
        reverse.addView(label("Dares before Reverse Dare",13,true,if(locked)muted else ink).apply{setPadding(0,dp(14),0,dp(7))});val counts=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;alpha=if(locked).48f else 1f};(1..5).forEach{value->val active=value==reverseDareLimit;val choice=button(value.toString(),if(active&&!locked)accent else surface,if(active&&!locked)onAccent()else muted){if(!locked){reverseDareLimit=value;saveState();showGameplay()}}.apply{minHeight=dp(44);textSize=15f;isEnabled=!locked;background=shape(if(active&&!locked)accent else surface,18,1)};counts.addView(choice,LinearLayout.LayoutParams(0,dp(44),1f).apply{if(value>1)leftMargin=dp(5)})};reverse.addView(counts)
    }
    private fun showAbout(){
        val root=base(); title(root,"About app")
        divider(root,7)
        settingsRow(root,"◆","Game rules","How to play and keep it respectful",{openScreen("rules"){showGameRules()}},false)
        settingsRow(root,"ⓘ","Privacy policy","How Truth or Dare handles your data",{openScreen("privacy"){showPrivacyPolicy()}},false)
        settingsRow(root,"✦","About app","Version and Android compatibility",{openScreen("appInfo"){showAppInformation()}},false)
        settingsRow(root,"↻","Restore purchases","Check Google Play for eligible purchases",{queryOwned(true)},false)
    }    private fun informationPage(titleText:String, body:String) {
        val root=base(scroll=false)
        root.setPadding(dp(16),0,dp(16),dp(16))
        title(root,titleText,true,{goBack()},0)
        val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(surface,30,1); setPadding(dp(18),dp(16),dp(18),dp(16)) }
        val scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false; isVerticalScrollBarEnabled=true; scrollBarStyle=View.SCROLLBARS_INSIDE_INSET; verticalScrollbarPosition=View.SCROLLBAR_POSITION_RIGHT; setScrollBarSize(dp(3)); setPadding(0,0,dp(2),0) }
        scroll.addView(label(body,15,false,ink).apply { setLineSpacing(dp(7).toFloat(),1.0f); setPadding(0,0,dp(10),dp(10)) })
        c.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(c,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(10) })
    }
    private fun showGameRules(){ informationPage("Game rules","• Add at least two players and set each player's gender and interaction preferences.\n\n• A player is chosen at random. The same player cannot be chosen twice in a row; with four or more players, everyone is selected before anyone repeats.\n\n• Choose Truth or Dare. After two Truth choices in a row, that player must choose Dare next time.\n\n• Targeted prompts follow the active player's saved preference: Men, Women, Men & women, or only their selected specific players.\n\n• Every prompt can be passed or skipped. Keep the game respectful, consensual, and free of physical harm.") }
    private fun privacyText():String = "Effective date: 15 October 2026\n\nTruth or Dare is designed to work locally on your device. The app stores player names, preferences, selected level, custom cards, and game progress only in local app storage so your group can resume later.\n\nWe do not collect, sell, share, or transmit personal information. We do not use advertising, analytics, accounts, location, contacts, camera, microphone, nearby devices, or Bluetooth.\n\nGoogle Play Billing is used only when you choose to buy the optional Premium Levels one-time product. Google processes payment information under its own privacy policy; this app does not receive or store payment card details.\n\nYou can delete local game information at any time by clearing the app's storage or uninstalling the app.\n\nIf this policy changes, the updated version will be included in the app and release notes."
    private fun showPrivacyPolicy(){ informationPage("Privacy policy",privacyText()) }
    private fun installedVersion():String = runCatching { packageManager.getPackageInfo(packageName,0).versionName ?: "Unknown" }.getOrDefault("Unknown")
    private fun showAppInformation(){ informationPage("About app","App version\n${installedVersion()}\n\nMinimum SDK\nAndroid 7.0 · API 24\n\nTarget SDK\nAndroid 16 · API 36\n\nSupported API levels\nAPI 24 to API 36") }
    private fun showPrivacyNotice(){
        val dialog=Dialog(this)
        val shell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(surface,28,1); setPadding(dp(18),dp(18),dp(18),dp(18)) }
        shell.addView(label("Privacy policy",22,true))
        shell.addView(label("Before you start, please read how Truth or Dare handles your data.",13,false,muted).apply { setPadding(0,dp(6),0,dp(8)) })
        val scroll=ScrollView(this)
        scroll.addView(label(privacyText(),13,false,ink).apply { setLineSpacing(dp(5).toFloat(),1f); setPadding(0,0,0,dp(8)) })
        shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        shell.addView(button("Understood",accent,onAccent()){ prefs.edit().putBoolean("privacySeen",true).apply(); dialog.dismiss(); offerAfterPrivacy=true; window.decorView.post { requestFirstOffer() } },lp(-1,dp(54),0))
        dialog.setContentView(shell); dialog.setCancelable(false); dialog.show()
        dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels*.90f).toInt(),(resources.displayMetrics.heightPixels*.76f).toInt()); attributes=attributes.apply { dimAmount=.42f; if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) blurBehindRadius=dp(22) }; addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND) }
    }    private fun eligibleTargets(active:Player):List<Player>{
        val others=players.filter { it !== active }
        return when(active.preference){
            "Men" -> others.filter { it.gender=="Man" }
            "Women" -> others.filter { it.gender=="Woman" }
            "Men & women" -> others.filter { it.gender=="Man" || it.gender=="Woman" }
            "Specific players" -> others.filter { active.specificPlayers.contains(it.name) }
            else -> emptyList()
        }
    }
    private fun renderPrompt(text:String, active:Player, targetName:String?=null):String{
        val target=targetName ?: eligibleTargets(active).randomOrNull()?.name.orEmpty()
        return text.replace("{X}",active.name).replace("{Y}",target)
    }
    private fun promptLabel(display:String):TextView {
        val styled=android.text.SpannableString(display)
        players.map { it.name }.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { name ->
            var start=display.indexOf(name)
            while(start>=0){
                styled.setSpan(android.text.style.ForegroundColorSpan(accent),start,start+name.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                start=display.indexOf(name,start+name.length)
            }
        }
        return label("",20,true,ink,Gravity.CENTER).apply { text=styled; setLineSpacing(dp(4).toFloat(),1f) }
    }
    private inner class PlayerWheelView(context: Context, private val names: List<String>) : View(context) {
        var wheelRotation = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx=width/2f; val cy=height/2f; val radius=min(width,height)*.43f
            val bounds=RectF(cx-radius,cy-radius,cx+radius,cy+radius)
            val step=360f/names.size.coerceAtLeast(1)
            canvas.save(); canvas.rotate(wheelRotation,cx,cy)
            names.forEachIndexed { index, name ->
                paint.color=if(index%2==0) accent else ColorUtils.blendARGB(accent, surface, .48f)
                canvas.drawArc(bounds,-90f+index*step,step,true,paint)
                paint.color=onAccent(); paint.textSize=dp(15).toFloat(); paint.typeface=android.graphics.Typeface.create("Google Sans Flex",android.graphics.Typeface.BOLD); paint.textAlign=Paint.Align.CENTER
                val angle=Math.toRadians((-90f+index*step+step/2f).toDouble())
                val tx=cx+(radius*.68f*kotlin.math.cos(angle)).toFloat(); val ty=cy+(radius*.68f*kotlin.math.sin(angle)).toFloat()-((paint.descent()+paint.ascent())/2)
                canvas.drawText(name,tx,ty,paint)
            }
            canvas.restore()
            paint.color=surface; canvas.drawCircle(cx,cy,radius*.27f,paint)
            paint.color=accent; canvas.drawRoundRect(RectF(cx-dp(13),cy-dp(28),cx+dp(13),cy+dp(29)),dp(10).toFloat(),dp(10).toFloat(),paint)
            paint.color=onAccent(); canvas.drawRoundRect(RectF(cx-dp(6),cy-dp(43),cx+dp(6),cy-dp(25)),dp(4).toFloat(),dp(4).toFloat(),paint)
            paint.color=surface; canvas.drawCircle(cx,cy+dp(13),dp(4).toFloat(),paint)
            paint.color=accent; paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(5).toFloat(); canvas.drawLine(cx,cy-radius-dp(8),cx,cy-radius+dp(12),paint); paint.style=Paint.Style.FILL
        }
    }
    private fun showPlayerSpinner(){
        if(players.size<2){ showHome(); return }
        if(remainingTurnIndices.isEmpty()) {
            if(hasActiveTurn) round++
            remainingTurnIndices.addAll(players.indices)
        }
        val candidates=remainingTurnIndices.filter { !hasActiveTurn || it != turn || remainingTurnIndices.size == 1 }
        val chosen=(if(candidates.isNotEmpty()) candidates else remainingTurnIndices.toList()).random()
        remainingTurnIndices.remove(chosen)
        val root=base(scroll=false)
        title(root,"Who's next?",true,{ showHome() },0)
        root.addView(label("Pass the bottle — it picks the next player.",16,false,muted,Gravity.CENTER).apply { setPadding(0,dp(20),0,dp(10)) },lp(-1,-2,0))
        val wheel=PlayerWheelView(this,players.map { it.name }).apply { minimumHeight=dp(360); background=shape(surface,32,1) }
        root.addView(wheel,LinearLayout.LayoutParams(-1,0,1f).apply { setMargins(0,dp(10),0,dp(16)) })
        root.addView(label("Spinning the bottle…",17,true,accent,Gravity.CENTER),lp(-1,dp(48),0))
        val step=360f/players.size
        val targetRotation=4*360f-step/2f-chosen*step
        ValueAnimator.ofFloat(0f,targetRotation).apply { duration=4000; interpolator=DecelerateInterpolator(); addUpdateListener { wheel.wheelRotation=it.animatedValue as Float; wheel.invalidate() }; doOnEnd { turn=chosen; hasActiveTurn=true; if(reverseDarePending){reverseDarePending=false;currentScreen="card";showCard(nextCard("dare"))}else{currentScreen="choices";showChoices()} }; start() }
    }
    private fun ValueAnimator.doOnEnd(action:()->Unit) { addListener(object:android.animation.AnimatorListenerAdapter(){ override fun onAnimationEnd(animation:android.animation.Animator){ action() } }) }
    private fun showChoices(){
        val root=base(scroll=false);val player=players[turn%players.size];val truthAllowed=!(limitTruths&&player.truthStreak>=truthLimit);val reverseReady=premium&&reverseDares&&player.dareStreak>=reverseDareLimit
        val header=FrameLayout(this);header.addView(backButton{goBack()},FrameLayout.LayoutParams(dp(46),dp(46),Gravity.START));val copy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;addView(label("ROUND $round",10,true,accent,Gravity.CENTER));addView(label("${player.name}'s turn",21,true,ink,Gravity.CENTER))};header.addView(copy,FrameLayout.LayoutParams(-1,dp(52),Gravity.CENTER));root.addView(header,lp(-1,dp(56),0))
        root.addView(label(if(!truthAllowed)"Truth is locked this turn — choose Dare to continue." else if(reverseReady)"Reverse Dare is ready — spin to assign a dare." else "Choose your move",if(truthAllowed)17 else 14,false,if(truthAllowed)muted else accent,Gravity.CENTER),lp(-1,dp(42),0))
        val truth=button("✦\nTRUTH\nBe honest",if(truthAllowed)accent else ColorUtils.blendARGB(surface,outline,.65f),if(truthAllowed)onAccent() else muted){if(truthAllowed)openScreen("card"){showCard(nextCard("truth"))}}.apply{textSize=22f;isEnabled=truthAllowed;alpha=if(truthAllowed)1f else .55f};if(reverseReady)root.addView(button("Reverse Dare · spin for player",accent,onAccent()){reverseDarePending=true;showPlayerSpinner()}.apply{textSize=13f},lp(-1,dp(46),2));root.addView(truth,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(5);bottomMargin=dp(3)})
        val dare=button("⚡\nDARE\nTake the challenge",accent,onAccent()){openScreen("card"){showCard(nextCard("dare"))}}.apply{textSize=22f};root.addView(dare,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(3);bottomMargin=dp(2)})
    }
    private fun comfortAdjustedLevel(active:Player, target:Player?=null):String {
        val levels=listOf("soft","hot","hard","extreme")
        val selectedIndex=levels.indexOf(selectedLevel).coerceAtLeast(0)
        val involved=listOfNotNull(active,target).filter { premium && it.comfortEnabled }
        val allowedIndex=involved.minOfOrNull { ((it.comfort-1)*4/10).coerceIn(0,3) } ?: selectedIndex
        return levels[min(selectedIndex,allowedIndex)]
    }
    private fun nextCard(kind:String):Card {
        val active=players[turn%players.size]
        if(selectedLevel=="mydares") {
            val source=if(customCards.isNotEmpty()) customCards else decks["soft"].orEmpty()
            val pool=source.filter { it.kind==kind && (!it.text.contains("{Y}") || eligibleTargets(active).isNotEmpty()) }
            return if(pool.isEmpty()) Card(kind,"No prompt available for the current player preferences.") else pool.random()
        }
        val personalLevel=comfortAdjustedLevel(active)
        val personalCards=decks[personalLevel].orEmpty().filter { it.kind==kind && !it.text.contains("{Y}") }
        val targetedCards=eligibleTargets(active).flatMap { target ->
            decks[comfortAdjustedLevel(active,target)].orEmpty()
                .filter { it.kind==kind && it.text.contains("{Y}") }
                .map { Card(it.kind,it.text,target.name) }
        }
        val pool=personalCards + targetedCards
        return if(pool.isEmpty()) Card(kind,"No prompt available for the current player preferences.") else pool.random()
    }
    private fun showCard(card:Card){
        currentCard=card;val player=players[turn%players.size];val next=button("Done · next turn →",accent,onAccent()){if(card.kind=="truth"){player.truthStreak++;player.dareStreak=0}else{player.truthStreak=0;player.dareStreak++};saveState();if(navigationStack.isNotEmpty()&&navigationStack.last()=="choices")navigationStack.removeLast();currentScreen="spinner";showPlayerSpinner()}.apply{textSize=16f;minHeight=dp(72)};val choose=!(limitTruths&&player.truthStreak>=truthLimit);val dock=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};if(choose){dock.addView(button("Choose\nagain",accent,onAccent()){activeTimer?.cancel();currentScreen="choices";showChoices()}.apply{textSize=12f},LinearLayout.LayoutParams(0,dp(72),.35f).apply{rightMargin=dp(8)});dock.addView(next,LinearLayout.LayoutParams(0,dp(72),.65f))}else dock.addView(next,LinearLayout.LayoutParams(-1,dp(72)))
        val root=base(scroll=false);val header=FrameLayout(this);header.addView(backButton{goBack()},FrameLayout.LayoutParams(dp(46),dp(46),Gravity.START or Gravity.CENTER_VERTICAL));header.addView(label(if(card.kind=="truth")"Truth time" else "Dare time",22,true,ink,Gravity.CENTER),FrameLayout.LayoutParams(-1,dp(46),Gravity.CENTER));val speaker=ImageButton(this).apply{setImageResource(if(ttsEnabled)R.drawable.ic_volume_on else R.drawable.ic_volume_off);setColorFilter(onAccent(),android.graphics.PorterDuff.Mode.SRC_IN);background=shape(accent,23);setPadding(dp(11),dp(11),dp(11),dp(11));contentDescription=if(ttsEnabled)"Mute speech" else "Enable speech";setOnClickListener{if(ttsEnabled)textToSpeech?.stop();animate().rotationBy(180f).setDuration(180).withEndAction{ttsEnabled=!ttsEnabled;saveState();showCard(card)}.start()}};header.addView(speaker,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.END or Gravity.CENTER_VERTICAL));root.addView(header,lp(-1,dp(46),0));val display=renderPrompt(card.text,player,card.targetName);if(ttsEnabled)textToSpeech?.speak(display,TextToSpeech.QUEUE_FLUSH,null,"prompt");val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=shape(surface,30,1);setPadding(dp(18),dp(18),dp(18),dp(18));addView(promptLabel(display))};root.addView(c,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(0,dp(10),0,dp(8))});root.addView(dock,LinearLayout.LayoutParams(-1,dp(72)).apply{setMargins(0,0,0,dp(8))})
    }
    private fun startTimer(seconds:Int,tv:TextView){activeTimer?.cancel();activeTimer=object:CountDownTimer(seconds*1000L,1000){override fun onTick(ms:Long){tv.text="${max(1,ms/1000)} s"};override fun onFinish(){tv.text="Time's up!";android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION,90).startTone(android.media.ToneGenerator.TONE_PROP_BEEP2)}}.start()}
    private fun parseSeconds(s:String):Int { val m=Regex("(\\d+)\\s*(seconds?|secs?|minutes?|mins?)",RegexOption.IGNORE_CASE).find(s)?:return if(s.contains("half a minute",true))30 else 0;val n=m.groupValues[1].toInt();return if(m.groupValues[2].lowercase().startsWith("m"))n*60 else n }

    private fun cardAction(icon:Int, description:String, action:()->Unit):ImageButton = ImageButton(this).apply {
        setImageResource(icon); setColorFilter(accent,android.graphics.PorterDuff.Mode.SRC_IN); contentDescription=description
        background=shape(surface,15,1); setPadding(dp(12),dp(12),dp(12),dp(12)); setOnClickListener { action() }
    }
    private fun showCustomCards(){
        val root=base(); title(root,"My truths and dares")
        root.addView(label("Create cards for your group, then choose whether a game uses only your cards or mixes them with the regular decks.",17,false,muted).apply{setPadding(0,dp(20),0,dp(16))},lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16)})
        customCards.forEachIndexed{i,c->
            val row=card(root,6); val line=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            line.addView(label(c.kind.uppercase(),14,true,accent),LinearLayout.LayoutParams(dp(84),-2))
            line.addView(label(c.text,17,false,ink),LinearLayout.LayoutParams(0,-2,1f))
            line.addView(cardAction(R.drawable.ic_edit,"Edit card"){editCustom(i)},lp(dp(48),dp(48),0))
            line.addView(cardAction(R.drawable.ic_delete,"Delete card"){customCards.removeAt(i);saveState();showCustomCards()},lp(dp(48),dp(48),3))
            row.addView(line)
        }
        val addCustom=button("Add a custom truth or dare",accent,onAccent()){editCustom(-1)};root.addView(addCustom,lp(-1,dp(62),10).apply{leftMargin=dp(16);rightMargin=dp(16)})
        val manage=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; isClickable=true; setPadding(dp(18),dp(15),dp(18),dp(15)); background=shape(surface,22,1); setOnClickListener { openScreen("manage"){manageCards()} } }
        manage.addView(label("Manage your cards",17,true)); manage.addView(label("Import, export, and organize your custom cards",12,false,muted).apply { setPadding(0,dp(4),0,0) })
        root.addView(manage,lp(-1,-2,8).apply{leftMargin=dp(16);rightMargin=dp(16)})
    }
    private fun editCustom(index:Int){
        val old=if(index>=0)customCards[index] else null
        val dialog=Dialog(this)
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(surface,28,1); setPadding(dp(22),dp(22),dp(22),dp(20)) }
        form.addView(label(if(old==null)"My truths and dares" else "Edit custom card",22,true))
        var selected=old?.kind ?: "truth"
        val picker=LinearLayout(this).apply { gravity=Gravity.CENTER; setPadding(0,dp(18),0,dp(10)) }
        val options=mutableListOf<TextView>()
        fun option(kind:String, symbol:String):TextView = button("$symbol  ${kind.replaceFirstChar { it.titlecase() }}",surface,ink) { selected=kind; options.forEach { view -> val active=view.tag==selected; view.background=shape(if(active)accent else surface,18,1); view.setTextColor(if(active)onAccent() else ink) } }.apply { tag=kind; textSize=15f; minHeight=dp(54) }
        val truth=option("truth","◯"); val dare=option("dare","◯"); options.add(truth); options.add(dare)
        picker.addView(truth,LinearLayout.LayoutParams(0,dp(54),1f).apply { rightMargin=dp(5) }); picker.addView(dare,LinearLayout.LayoutParams(0,dp(54),1f).apply { leftMargin=dp(5) })
        options.forEach { view -> val active=view.tag==selected; view.background=shape(if(active)accent else surface,18,1); view.setTextColor(if(active)onAccent() else ink) }
        form.addView(picker)
        val input=EditText(this).apply { hint="Write a custom card"; setText(old?.text?:""); setTextColor(ink); setHintTextColor(muted); textSize=17f; gravity=Gravity.TOP; minLines=4; background=shape(this@MainActivity.background,18,1); setPadding(dp(14),dp(14),dp(14),dp(14)) }
        form.addView(input,lp(-1,dp(150),4))
        val actions=LinearLayout(this).apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0,dp(16),0,0) }
        actions.addView(button("Cancel",surface,muted){dialog.dismiss()},lp(dp(122),dp(54),0)); actions.addView(button(if(old==null)"Add" else "Save",accent,onAccent()){ val value=input.text.toString().trim(); if(value.isNotEmpty()){if(old==null)customCards.add(Card(selected,value))else{old.kind=selected;old.text=value};saveState();dialog.dismiss();showCustomCards()} },lp(dp(122),dp(54),8))
        form.addView(actions)
        dialog.setContentView(form); dialog.show(); dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels*.90f).toInt(),-2); attributes=attributes.apply { dimAmount=.48f }; addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
    }
    private fun manageCards(){val root=base();title(root,"Manage your cards");root.addView(label("Export, import, or delete your custom truths and dares.",17,false,muted).apply{setPadding(0,dp(20),0,dp(12))},lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16)});listOf(button("Export cards",accent,onAccent()){exportCards()},button("Import cards",accent,onAccent()){importCards()},button("Delete all cards",Color.rgb(186,26,26),Color.WHITE){customCards.clear();saveState();showCustomCards()}).forEach{root.addView(it,lp(-1,dp(62),6).apply{leftMargin=dp(16);rightMargin=dp(16)})}}
    private fun exportCards(){
        exportCardsLauncher.launch("truth-or-dare-cards.json")
    }
    private fun importCards(){
        importCardsLauncher.launch(arrayOf("application/json","text/plain","text/*","application/octet-stream"))
    }
    private fun exportCardsTo(uri:Uri){
        try {
            val cards=org.json.JSONArray(); customCards.forEach { cards.put(JSONObject().put("kind",it.kind).put("text",it.text)) }
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(JSONObject().put("cards",cards).toString(2)) }
            Toast.makeText(this,"Cards exported.",Toast.LENGTH_SHORT).show()
        } catch(e:Exception) { Toast.makeText(this,"Could not export cards.",Toast.LENGTH_LONG).show() }
    }
    private fun importCardsFrom(uri:Uri){
        try {
            val raw=contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val imported=mutableListOf<Card>()
            fun collect(value:Any?){ when(value){ is org.json.JSONArray -> for(i in 0 until value.length()) collect(value.opt(i)); is JSONObject -> { val kind=value.optString("kind",value.optString("type")).lowercase(); val text=value.optString("text",value.optString("prompt",value.optString("content",value.optString("card")))).trim(); if(kind in listOf("truth","dare") && text.isNotEmpty()) imported.add(Card(kind,text)); value.keys().forEach { key -> if(key !in listOf("kind","type","text","prompt","content","card")) collect(value.opt(key)) } } } }
            runCatching { collect(JSONObject(raw)) }.getOrElse { runCatching { collect(org.json.JSONArray(raw)) } }
            if(imported.isEmpty()) raw.lineSequence().forEach { line -> Regex("^(TRUTH|DARE)\\s*:\\s*(.+)$",RegexOption.IGNORE_CASE).find(line.trim())?.let { imported.add(Card(it.groupValues[1].lowercase(),it.groupValues[2].trim())) } }
            if(imported.isEmpty()) throw IllegalArgumentException("No cards found")
            customCards.addAll(imported); saveState(); Toast.makeText(this,"Imported ${imported.size} cards.",Toast.LENGTH_SHORT).show(); showCustomCards()
        } catch(e:Exception) { Toast.makeText(this,"Could not read that card file.",Toast.LENGTH_LONG).show() }
    }
    private fun entitlementMac(): Mac {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = if (keyStore.containsAlias(premiumCacheKeyAlias)) {
            keyStore.getKey(premiumCacheKeyAlias, null) as SecretKey
        } else {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(premiumCacheKeyAlias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build())
            }.generateKey()
        }
        return Mac.getInstance("HmacSHA256").apply { init(key) }
    }
    private fun premiumCacheSignature(expiresAt: Long): String =
        Base64.encodeToString(entitlementMac().doFinal("$packageName|$expiresAt".toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    private fun hasValidCachedPremiumEntitlement(): Boolean = try {
        val expiresAt = prefs.getLong("premiumCacheExpiresAt", 0L)
        val signature = prefs.getString("premiumCacheSignature", "") ?: ""
        expiresAt > System.currentTimeMillis() && signature.isNotBlank() &&
            signature == premiumCacheSignature(expiresAt)
    } catch (_: Exception) { false }
    private fun cachePremiumEntitlement() {
        try {
            val expiresAt = System.currentTimeMillis() + offlinePremiumWindowMs
            prefs.edit().putLong("premiumCacheExpiresAt", expiresAt)
                .putString("premiumCacheSignature", premiumCacheSignature(expiresAt)).apply()
        } catch (_: Exception) { }
    }
    private fun clearCachedPremiumEntitlement() {
        prefs.edit().remove("premiumCacheExpiresAt").remove("premiumCacheSignature").apply()
    }
    private fun connectBilling(){
        billingClient.startConnection(object: BillingClientStateListener {
            override fun onBillingSetupFinished(result:BillingResult){
                if(result.responseCode==BillingClient.BillingResponseCode.OK){
                    queryProducts()
                    val announceRestore=restorePendingAfterConnection
                    restorePendingAfterConnection=false
                    queryOwned(announceRestore) { if(offerAfterPrivacy) showFirstOffer() }
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }
    private fun setupBilling(){
        billingClient=BillingClient.newBuilder(this).setListener { _, purchases -> handlePurchases(purchases ?: emptyList()) }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).build()
        connectBilling()
    }
    private fun queryProducts(){
        fun query(ids:List<String>,type:String){
            val list=ids.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() }
            billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(list).build()) { _, response -> response.productDetailsList.forEach { detail ->
                products[detail.productId]=detail
                prices[detail.productId]=detail.oneTimePurchaseOfferDetails?.formattedPrice ?: detail.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: "See price in Google Play"
            } }
        }
        query(listOf("full_app_unlock","premium_annual"),BillingClient.ProductType.SUBS)
        query(listOf("premium_levels"),BillingClient.ProductType.INAPP)
    }
    private fun queryOwned(announce:Boolean=false, onComplete:()->Unit={}){
        if(!::billingClient.isInitialized || !billingClient.isReady){
            if(announce) {
                restorePendingAfterConnection=true
                Toast.makeText(this,"Connecting to Google Play to restore purchases…",Toast.LENGTH_SHORT).show()
            }
            if(::billingClient.isInitialized) connectBilling()
            return
        }
        val restored=mutableListOf<Purchase>()
        var remaining=2
        listOf(BillingClient.ProductType.INAPP,BillingClient.ProductType.SUBS).forEach { type ->
            billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, response ->
                if(result.responseCode==BillingClient.BillingResponseCode.OK) restored.addAll(response)
                remaining--
                if(remaining==0){
                    handlePurchases(restored,true)
                    if(announce){
                        val found=restored.any { purchase -> purchase.purchaseState==Purchase.PurchaseState.PURCHASED && purchase.products.any { it=="premium_levels" || it=="full_app_unlock" || it=="premium_annual" } }
                        Toast.makeText(this,if(found) "Purchases restored. Premium Levels unlocked." else "No eligible purchases were found for this Google Play account.",Toast.LENGTH_LONG).show()
                    }
                    onComplete()
                }
            }
        }
    }
    private fun handlePurchases(purchases:List<Purchase>, replaceEntitlements:Boolean=false){
        val wasPremium=premium
        val owned=purchases.filter { it.purchaseState==Purchase.PurchaseState.PURCHASED }.flatMap { it.products }.filter { it in setOf("premium_levels","full_app_unlock","premium_annual") }.toSet()
        if(replaceEntitlements) ownedProductIds.clear()
        ownedProductIds.addAll(owned)
        premium = when {
            ownedProductIds.isNotEmpty() -> {
                cachePremiumEntitlement()
                true
            }
            replaceEntitlements -> {
                clearCachedPremiumEntitlement()
                false
            }
            else -> premium || hasValidCachedPremiumEntitlement()
        }
        saveState()
        if(wasPremium!=premium && currentScreen=="home") runOnUiThread { showHome() }
        purchases.filter { it.purchaseState==Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { purchase -> billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()){} }
    }
    private fun requestFirstOffer(){
        if(firstOfferShown) return
        if(!::billingClient.isInitialized || !billingClient.isReady) return
        queryOwned(false) { showFirstOffer() }
    }
    private fun showFirstOffer(){
        if(firstOfferShown || !offerAfterPrivacy) return
        offerAfterPrivacy=false
        val plans=when {
            "premium_levels" in ownedProductIds -> emptyList()
            "premium_annual" in ownedProductIds -> listOf("premium_levels")
            "full_app_unlock" in ownedProductIds -> listOf("premium_annual","premium_levels")
            else -> listOf("full_app_unlock","premium_annual","premium_levels")
        }
        if(plans.isNotEmpty()) { firstOfferShown=true; showPremium(true,plans) }
    }
    private fun showPremium(firstLaunch:Boolean=false, planIds:List<String> = listOf("full_app_unlock","premium_annual","premium_levels")){
        val dialog=Dialog(this); val scroll=ScrollView(this); val shell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(surface,28,1); setPadding(dp(18),dp(18),dp(18),dp(18)) }; scroll.addView(shell)
        val head=FrameLayout(this); head.addView(label(if(firstLaunch)"Welcome offer" else "Unlock Premium",22,true,ink,Gravity.CENTER),FrameLayout.LayoutParams(-1,dp(46),Gravity.CENTER)); head.addView(button("×",softAccent,ink){dialog.dismiss()}.apply{textSize=26f;minHeight=dp(42);background=shape(softAccent,21)},FrameLayout.LayoutParams(dp(42),dp(42),Gravity.END));shell.addView(head,lp(-1,dp(46),0))
        shell.addView(label(if(firstLaunch)"Your introductory discounts are ready." else "Get every level and setting.",14,false,muted,Gravity.CENTER).apply{setPadding(0,dp(4),0,dp(12))})
        shell.addView(label("✓ Unlock Hard, Extreme, and custom cards\n✓ Create your own truths and dares\n✓ Enable all gameplay settings",13,true,ink).apply{setLineSpacing(dp(5).toFloat(),1f);setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(ColorUtils.blendARGB(surface,accent,.10f),18,1)},lp(-1,-2,0))
        var selected=if("premium_annual" in planIds) "premium_annual" else planIds.first(); val rows=mutableListOf<TextView>()
        fun refresh(){ rows.forEach { row -> val active=row.tag==selected; row.setTextColor(if(active)onAccent() else ink); row.background=shape(if(active)accent else surface,18,if(active)0 else 1) } }
        fun plan(id:String,title:String,detail:String){ val row=label("$title\n${prices[id] ?: "Price shown by Google Play"} · $detail",15,true,ink,Gravity.CENTER_VERTICAL).apply { tag=id; setPadding(dp(16),0,dp(16),0); isClickable=true; setOnClickListener{selected=id;refresh()} }; rows.add(row);shell.addView(row,lp(-1,dp(62),4));refresh() }
        val captions=mapOf("full_app_unlock" to ("Monthly" to "renews monthly"),"premium_annual" to ("Yearly" to "best value"),"premium_levels" to ("Lifetime" to "one-time purchase"))
        planIds.forEach { id -> captions[id]?.let { plan(id,it.first,it.second) } }
        shell.addView(label("Subscriptions renew automatically. Cancel anytime.",11,false,muted,Gravity.CENTER).apply{setPadding(0,dp(8),0,dp(8))})
        shell.addView(button("Continue",accent,onAccent()){
            val detail=products[selected]; if(detail==null){Toast.makeText(this,"Google Play is loading the current price. Please try again shortly.",Toast.LENGTH_SHORT).show();return@button}
            val item=BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(detail).apply { if(selected!="premium_levels") setOfferToken(detail.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: "") }.build()
            billingClient.launchBillingFlow(this,BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item)).build())
        },lp(-1,dp(54),4))
        shell.addView(label("Restore purchases",12,false,muted,Gravity.CENTER).apply{setPadding(0,dp(10),0,0);isClickable=true;setOnClickListener{queryOwned(true)}})
        dialog.setContentView(scroll);dialog.show();dialog.window?.apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));setLayout((resources.displayMetrics.widthPixels*.90f).toInt(),if(firstLaunch)dp(if(planIds.size==3)620 else if(planIds.size==2)540 else 455) else -2);setGravity(Gravity.CENTER);attributes=attributes.apply{dimAmount=.45f;if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)blurBehindRadius=dp(20)};addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)}
    }
    private fun loadDecks(){
        try{
            val obj=JSONObject(assets.open("truth_or_dare_prompts.json").bufferedReader().use{it.readText()})
            val plans=obj.optJSONObject("plans") ?: obj
            listOf("soft","hot","hard","extreme").forEach { level ->
                val list=mutableListOf<Card>(); val plan=plans.optJSONObject(level)
                listOf("truths" to "truth","dares" to "dare").forEach { (key,kind) ->
                    val prompts=plan?.optJSONArray(key)
                    if(prompts!=null) for(i in 0 until prompts.length()){
                        val item=prompts.get(i); val text=if(item is JSONObject)item.optString("text") else item.toString()
                        if(text.isNotBlank()) list.add(Card(kind,text))
                    }
                }
                decks[level]=list
            }
        }catch(_:Exception){
            val fallback=mutableListOf(Card("truth","What is a small thing that made you smile today?"),Card("dare","Dance with no music for 30 seconds."))
            decks["soft"]=fallback;decks["hot"]=fallback;decks["hard"]=fallback;decks["extreme"]=fallback
        }
    }    private fun loadState(){themeMode=prefs.getString("theme","system")?:"system";useSystemColors=prefs.getBoolean("systemColors",false);limitTruths=prefs.getBoolean("limit",true);truthLimit=prefs.getInt("truthLimit",2).coerceIn(1,5);reverseDares=prefs.getBoolean("reverseDares",false);reverseDareLimit=prefs.getInt("reverseDareLimit",2).coerceIn(1,5);premium=hasValidCachedPremiumEntitlement();ttsEnabled=prefs.getBoolean("ttsEnabled",false);val json=prefs.getString("players","[]")?:"[]";try{val a=org.json.JSONArray(json);for(i in 0 until a.length()){val x=a.getJSONObject(i);players.add(Player(x.optString("name"),x.optString("gender","").let{if(it=="Other")"" else it},x.optString("preference",""),x.optInt("truthStreak"),x.optInt("dareStreak"),x.optInt("comfort",5).coerceIn(1,10),x.optBoolean("comfortEnabled",false),MutableList(x.optJSONArray("specific")?.length()?:0){j->x.optJSONArray("specific")?.optString(j)?:""}.filter{it.isNotBlank()}.toMutableSet()))}}catch(_:Exception){};val cards=prefs.getString("cards","[]")?:"[]";try{val a=org.json.JSONArray(cards);for(i in 0 until a.length()){val x=a.getJSONObject(i);customCards.add(Card(x.optString("kind"),x.optString("text")))}}catch(_:Exception){}}
    private fun saveState(){val a=org.json.JSONArray();players.forEach{p->a.put(JSONObject().put("name",p.name).put("gender",p.gender).put("preference",p.preference).put("truthStreak",p.truthStreak).put("dareStreak",p.dareStreak).put("comfort",p.comfort).put("comfortEnabled",p.comfortEnabled).put("specific",org.json.JSONArray(p.specificPlayers.toList())))};val c=org.json.JSONArray();customCards.forEach{x->c.put(JSONObject().put("kind",x.kind).put("text",x.text))};prefs.edit().putString("theme",themeMode).putBoolean("systemColors",useSystemColors).putBoolean("limit",limitTruths).putInt("truthLimit",truthLimit).putBoolean("reverseDares",reverseDares).putInt("reverseDareLimit",reverseDareLimit).remove("premium").putBoolean("ttsEnabled",ttsEnabled).putString("players",a.toString()).putString("cards",c.toString()).apply()}
    data class Player(var name:String,var gender:String="",var preference:String="",var truthStreak:Int=0,var dareStreak:Int=0,var comfort:Int=5,var comfortEnabled:Boolean=false,var specificPlayers:MutableSet<String> = mutableSetOf())
    data class Card(var kind:String,var text:String,var targetName:String?=null)
}























