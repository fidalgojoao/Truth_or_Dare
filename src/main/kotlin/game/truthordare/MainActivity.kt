package game.truthordare

import android.app.*
import android.os.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
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
    private var themeMode = "dark"
    private var limitTruths = false
    private var truthLimit = 2
    private var reverseDares = false
    private var reverseDareLimit = 2
    private var reverseDareTarget: Player? = null
    private var reverseDarePending = false
    private var premium = false
    private var lifetimePremium = false
    private var premiumDialog: Dialog? = null
    private val offlinePremiumWindowMs = 30L * 24L * 60L * 60L * 1000L
    private val premiumCacheKeyAlias = "truth_or_dare_premium_entitlement_v1"
    private val ownedProductIds = mutableSetOf<String>()
    private var offerAfterPrivacy = false
    private var firstOfferShown = false
    private var firstOfferFallback: Runnable? = null
    private var restorePendingAfterConnection = false
    private var round = 1
    private var turn = 0
    private var playMode = "spin"
    private val orderedTurnIndices = mutableListOf<Int>()
    private var orderedTurnPosition = 0
    private var orderAnimationFrom = -1
    private var orderAnimationTo = -1
    private val remainingTurnIndices = mutableSetOf<Int>()
    private var hasActiveTurn = false
    private var activeTimer: CountDownTimer? = null
    private var ttsEnabled = false
    private var textToSpeech: TextToSpeech? = null
    private var currentCard: Card? = null
    private var homeScrollY = 0
    private var playersPanelExpanded = true
    private var levelsPanelExpanded = true
    private lateinit var billingClient: BillingClient
    private val prices = mutableMapOf<String, String>()
    private val products = mutableMapOf<String, ProductDetails>()
    private val knownLanguages=listOf("en","es","fr","de","pt","it","ru","ja","ko","zh-CN","zh-TW","ar","hi","id","tr","vi","th","pl","nl","uk","bn","fa","sv","ro","cs","el","hu","he","da","fi","no","ms","fil","ur","sw","ta","te","mr","gu","kn","ml","pa","sr","hr","sk","bg","lt","sl","et","lv")
    private val uiTranslations by lazy { runCatching { JSONObject(assets.open("ui_translations.json").bufferedReader().use{it.readText()}) }.getOrDefault(JSONObject()) }
    private val promptAssetPrefix="truth_or_dare_prompts_"
    private val supportedLanguages by lazy {
        val available=runCatching { assets.list("").orEmpty().toSet() }.getOrDefault(emptySet())
        knownLanguages.filter { language ->
            "${promptAssetPrefix}${promptFileSuffix(language)}.json" in available ||
                "${promptAssetPrefix}${language.lowercase(Locale.ENGLISH)}.json" in available
        }
    }
    private val navigationStack = ArrayDeque<String>()
    private var currentScreen = "home"
    private val exportCardsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { exportCardsTo(it) } }
    private val importCardsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importCardsFrom(it) } }

    private fun openScreen(screen:String, render:()->Unit) {
        if(currentScreen=="card" && screen!="card") textToSpeech?.stop()
        if(currentScreen != screen) navigationStack.addLast(currentScreen)
        currentScreen=screen
        render()
    }
    private fun goBack() {
        textToSpeech?.stop()
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
            "language" -> showLanguage()
            "gameplay" -> showGameplay()
            "help" -> showHelp()
            "about" -> showAbout()
            "rules" -> showGameRules()
            "privacy" -> showPrivacyPolicy()
            "appInfo" -> showAppInformation()
            "playMode" -> showPlayModeSelection()
            "turnOrder" -> showTurnOrderSetup()
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

    private fun dynamicColor(light: Int, dark: Int, fallback: Int): Int =
        if (useSystemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getColor(if (isDark()) dark else light)
        } else fallback

    // The supplied icon defines the default navy and orange identity. Android's
    // system palette remains available as an explicit Appearance preference.
    private val background get() = dynamicColor(android.R.color.system_neutral1_10, android.R.color.system_neutral1_900, if (isDark()) Color.rgb(32,44,56) else Color.WHITE)
    private val gradientEnd get() = dynamicColor(android.R.color.system_accent2_100,android.R.color.system_accent2_900,if(isDark())Color.rgb(27,18,58)else Color.rgb(232,225,250))
    private val gradientStart get() = ColorUtils.blendARGB(background,gradientEnd,if(isDark()).30f else .20f)
    private val surface get() = dynamicColor(android.R.color.system_neutral1_0, android.R.color.system_neutral1_800, if (isDark()) Color.rgb(41,56,70) else Color.rgb(255,248,240))
    private val ink get() = dynamicColor(android.R.color.system_neutral1_900, android.R.color.system_neutral1_10, if (isDark()) Color.rgb(255,248,238) else Color.rgb(23,37,50))
    private val muted get() = dynamicColor(android.R.color.system_neutral2_700, android.R.color.system_neutral2_300, if (isDark()) Color.rgb(195,203,211) else Color.rgb(82,91,100))
    private val outline get() = dynamicColor(android.R.color.system_neutral2_300, android.R.color.system_neutral2_600, if (isDark()) Color.rgb(70,88,106) else Color.rgb(224,183,143))
    private val accent get() = dynamicColor(android.R.color.system_accent1_600, android.R.color.system_accent1_300, if (isDark()) Color.rgb(255,146,13) else Color.rgb(190,76,0))
    private val truthColor = Color.rgb(77,163,255)
    private val dareColor = Color.rgb(255,200,61)
    private val moveButtonInk = Color.rgb(18,31,44)
    private val casualLevelColor = Color.rgb(77,163,255)
    private val flirtyLevelColor = Color.rgb(255,91,147)
    private val hotLevelColor = Color.rgb(255,122,69)
    private val naughtyLevelColor = Color.rgb(166,103,255)
    private val softAccent get() = ColorUtils.blendARGB(accent, background, if (isDark()) .22f else .14f)

    private fun levelName(key:String):String = when(key) {
        "soft" -> "Casual"
        "hot" -> "Flirty"
        "hard" -> "Hot"
        "extreme" -> "Naughty"
        "mydares" -> "My custom cards"
        else -> key.replaceFirstChar { if(it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
    private fun levelDetail(key:String):String = when(key) {
        "soft" -> "Easy & silly"
        "hot" -> "Playful & bold"
        "hard" -> "Confident & intimate"
        "extreme" -> "Bold & daring"
        else -> "Your custom cards"
    }
    private fun levelColor(key:String):Int = when(key) {
        "soft" -> casualLevelColor
        "hot" -> flirtyLevelColor
        "hard" -> hotLevelColor
        "extreme" -> naughtyLevelColor
        else -> accent
    }
    private fun selectedLanguage():String {
        val selected=prefs.getString("uiLanguage","en") ?: "en"
        return if(selected=="system" || selected in supportedLanguages)selected else "en"
    }
    private fun effectiveLanguage():String {
        val chosen=selectedLanguage()
        if(chosen!="system")return chosen
        val locale=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N)resources.configuration.locales[0] else @Suppress("DEPRECATION") resources.configuration.locale
        val tag=locale.toLanguageTag()
        return when {
            locale.language=="zh" && "zh-TW" in supportedLanguages && (tag.contains("Hant",true) || locale.country in setOf("TW","HK","MO"))->"zh-TW"
            locale.language=="zh" && "zh-CN" in supportedLanguages->"zh-CN"
            locale.language in supportedLanguages->locale.language
            else->"en"
        }
    }
    private fun tr(value:String):String {
        val language=effectiveLanguage()
        if(language=="en")return value
        return uiTranslations.optJSONObject(language)?.optString(value,value)?.takeIf{it.isNotBlank()} ?: value
    }
    private fun trKey(key:String,fallback:String):String = uiTranslations.optJSONObject(effectiveLanguage())?.optString(key,fallback)?.takeIf{it.isNotBlank()} ?: fallback
    private fun trf(value:String,vararg replacements:Pair<String,Any>):String = replacements.fold(tr(value)){text,(key,replacement)->text.replace("{$key}",replacement.toString())}
    private fun fitSingleLine(view:TextView,minSp:Int=10,maxSp:Int=15){view.isSingleLine=true;TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(view,minSp,maxSp,1,TypedValue.COMPLEX_UNIT_SP)}
    private fun languageDisplayName(code:String):String {
        if(code=="system")return tr("System default")
        val locale=Locale.forLanguageTag(code)
        return locale.getDisplayName(locale).replaceFirstChar{if(it.isLowerCase())it.titlecase(locale)else it.toString()}
    }
    private fun applyLanguageDirection(){window.decorView.layoutDirection=if(effectiveLanguage() in setOf("ar","fa","he","ur"))View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setupBilling()
        loadState()
        textToSpeech=TextToSpeech(this){ if(it==TextToSpeech.SUCCESS) configureSpeechVoice() }
        players.forEach { it.truthStreak = 0 }
        saveState()
        loadDecks()
        applyThemeAndHome()
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { runOnUiThread { goBack() } }
        if(!prefs.getBoolean("privacySeen",false)) window.decorView.post { showPrivacyNotice() }
        else if(!prefs.getBoolean("gameRulesSeen",false)) window.decorView.post { showGameRulesNotice() }
        else if(!prefs.getBoolean("premiumWelcomeSeen",false)) { offerAfterPrivacy=true; window.decorView.post { requestFirstOffer() } }
    }

    override fun onDestroy() { activeTimer?.cancel(); textToSpeech?.stop(); textToSpeech?.shutdown(); super.onDestroy() }

    private fun configureSpeechVoice() {
        val engine=textToSpeech ?: return
        val languageLocale=Locale.forLanguageTag(effectiveLanguage())
        engine.language=languageLocale
        val femaleVoice=engine.voices?.filter { it.locale.language==languageLocale.language && it.name.contains("female",true) && it.quality>=android.speech.tts.Voice.QUALITY_HIGH }?.maxByOrNull { it.quality }
        femaleVoice?.let { engine.voice=it }
        engine.setSpeechRate(.90f)
        engine.setPitch(1.02f)
    }

    private fun isDark(): Boolean = when (themeMode) { "dark" -> true; "light" -> false; else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun lp(w: Int = -1, h: Int = -2, m: Int = 0) = LinearLayout.LayoutParams(w, h).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }
    private fun weight(weight: Float) = LinearLayout.LayoutParams(0, -2, weight)
    private fun onAccent(): Int = contrastOn(accent)
    private fun contrastOn(color:Int): Int = if (ColorUtils.calculateLuminance(color) > .36) Color.rgb(23,37,50) else Color.WHITE
    private fun shape(color: Int, radius: Int = 24, stroke: Int = 0, strokeColor: Int = outline) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke > 0) setStroke(dp(stroke), strokeColor) }
    private fun appBackgroundDrawable() = GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(gradientStart,gradientEnd))
    private fun styleScrollBar(scroll:ScrollView, enabled:Boolean=true) {
        scroll.isVerticalScrollBarEnabled=enabled
        if(!enabled) return
        scroll.scrollBarStyle=View.SCROLLBARS_INSIDE_OVERLAY
        scroll.verticalScrollbarPosition=View.SCROLLBAR_POSITION_RIGHT
        scroll.setScrollBarSize(dp(4))
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) scroll.verticalScrollbarThumbDrawable=android.graphics.drawable.InsetDrawable(shape(accent,2),0,0,dp(1),0)
    }
    private fun groupedShape(color: Int, topRadius: Int, bottomRadius: Int, stroke: Int = 0, strokeColor: Int = outline) = GradientDrawable().apply {
        setColor(color)
        val top = dp(topRadius).toFloat(); val bottom = dp(bottomRadius).toFloat()
        cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }
    private inner class MaterialSwitchView(initial:Boolean, private val controlEnabled:Boolean, private val onChanged:(Boolean)->Unit):View(this@MainActivity) {
        private var checked=initial
        private var progress=if(initial)1f else 0f
        private val fillPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL }
        private val strokePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=dp(2).toFloat() }
        private val iconPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND; strokeWidth=dp(2).toFloat() }
        init {
            isClickable=controlEnabled
            isFocusable=controlEnabled
            alpha=if(controlEnabled)1f else .38f
            contentDescription=if(initial)"On" else "Off"
            setOnClickListener {
                if(!controlEnabled)return@setOnClickListener
                checked=!checked
                contentDescription=if(checked)"On" else "Off"
                ValueAnimator.ofFloat(progress,if(checked)1f else 0f).apply {
                    duration=180
                    interpolator=DecelerateInterpolator()
                    addUpdateListener { progress=it.animatedValue as Float;invalidate() }
                    start()
                }
                onChanged(checked)
            }
        }
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val trackWidth=min(width.toFloat(),dp(52).toFloat())
            val trackHeight=min(height.toFloat(),dp(32).toFloat())
            val left=(width-trackWidth)/2f
            val top=(height-trackHeight)/2f
            val track=RectF(left,top,left+trackWidth,top+trackHeight)
            fillPaint.color=ColorUtils.blendARGB(ColorUtils.blendARGB(surface,outline,.20f),accent,progress)
            canvas.drawRoundRect(track,trackHeight/2f,trackHeight/2f,fillPaint)
            if(progress<.99f){ strokePaint.color=outline;canvas.drawRoundRect(track,trackHeight/2f,trackHeight/2f,strokePaint) }
            // Material 3's "icons in both states" configuration keeps the
            // large thumb in both positions and swaps the icon directly.
            val radius=dp(12).toFloat()
            val startX=left+dp(16)
            val endX=left+trackWidth-dp(16)
            val cx=startX+(endX-startX)*progress
            val cy=top+trackHeight/2f
            val thumbColor=ColorUtils.blendARGB(muted,onAccent(),progress)
            fillPaint.color=thumbColor
            canvas.drawCircle(cx,cy,radius,fillPaint)
            iconPaint.alpha=255
            if(checked){
                iconPaint.color=accent
                val checkX=cx-resources.displayMetrics.density*.5f
                val checkY=cy+resources.displayMetrics.density*.5f
                canvas.drawLine(checkX-dp(5),checkY,checkX-dp(1),checkY+dp(4),iconPaint)
                canvas.drawLine(checkX-dp(1),checkY+dp(4),checkX+dp(6),checkY-dp(5),iconPaint)
            }else{
                iconPaint.color=contrastOn(thumbColor)
                val inset=dp(4).toFloat()
                canvas.drawLine(cx-inset,cy-inset,cx+inset,cy+inset,iconPaint)
                canvas.drawLine(cx+inset,cy-inset,cx-inset,cy+inset,iconPaint)
            }
        }
    }
    private fun expressiveToggle(initial:Boolean, enabled:Boolean=true, onChanged:(Boolean)->Unit): FrameLayout = FrameLayout(this).apply {
        addView(MaterialSwitchView(initial,enabled,onChanged),FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
    }

    private inner class PaletteIconView:View(this@MainActivity) {
        private val palettePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(142,66,12);style=Paint.Style.FILL }
        private val cutoutPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(255,220,196);style=Paint.Style.FILL }
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val s=min(width,height).toFloat();val x=(width-s)/2f;val y=(height-s)/2f
            val p=Path().apply {
                moveTo(x+s*.82f,y+s*.54f)
                cubicTo(x+s*.94f,y+s*.40f,x+s*.86f,y+s*.17f,x+s*.67f,y+s*.10f)
                cubicTo(x+s*.40f,y,x+s*.14f,y+s*.12f,x+s*.08f,y+s*.38f)
                cubicTo(x+s*.01f,y+s*.67f,x+s*.20f,y+s*.91f,x+s*.49f,y+s*.91f)
                cubicTo(x+s*.61f,y+s*.91f,x+s*.66f,y+s*.84f,x+s*.64f,y+s*.75f)
                cubicTo(x+s*.61f,y+s*.64f,x+s*.70f,y+s*.60f,x+s*.82f,y+s*.54f)
                close()
            }
            canvas.drawPath(p,palettePaint)
            canvas.drawCircle(x+s*.67f,y+s*.34f,s*.095f,cutoutPaint)
            canvas.drawCircle(x+s*.30f,y+s*.31f,s*.055f,cutoutPaint)
            canvas.drawCircle(x+s*.45f,y+s*.21f,s*.055f,cutoutPaint)
            canvas.drawCircle(x+s*.24f,y+s*.49f,s*.055f,cutoutPaint)
            canvas.drawCircle(x+s*.43f,y+s*.48f,s*.055f,cutoutPaint)
        }
    }

    private fun closeButton(action:()->Unit):View = FrameLayout(this).apply {
        isClickable=true;isFocusable=true
        background=shape(ColorUtils.blendARGB(surface,accent,.14f),22,1,ColorUtils.blendARGB(outline,accent,.70f))
        addView(object:View(this@MainActivity){
            private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=accent;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeWidth=dp(4).toFloat()}
            override fun onDraw(c:Canvas){super.onDraw(c);val inset=width*.31f;c.drawLine(inset,inset,width-inset,height-inset,p);c.drawLine(width-inset,inset,inset,height-inset,p)}
        },FrameLayout.LayoutParams(-1,-1))
        setOnClickListener { animate().scaleX(.86f).scaleY(.86f).setDuration(80).withEndAction { scaleX=1f;scaleY=1f;action() }.start() }
    }

    private fun accentOutline(radius:Int=26):GradientDrawable = shape(ColorUtils.blendARGB(surface,accent,if(isDark()).12f else .08f),radius,1,accent)
    private fun toneOutline(tone:Int,radius:Int=26):GradientDrawable = shape(ColorUtils.blendARGB(surface,tone,if(isDark()).12f else .08f),radius,1,tone)
    private fun accentIconButton(icon:Int,description:String,action:()->Unit):ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(accent,android.graphics.PorterDuff.Mode.SRC_IN)
        background=accentOutline(23)
        setPadding(dp(11),dp(11),dp(11),dp(11))
        contentDescription=description
        setOnClickListener { animate().scaleX(.86f).scaleY(.86f).setDuration(80).withEndAction { scaleX=1f;scaleY=1f;action() }.start() }
    }
    private fun animateNumberChoice(view:View, onComplete:()->Unit) {
        view.isEnabled=false
        view.animate().scaleX(.86f).scaleY(.86f).setDuration(90).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(130).withEndAction(onComplete).start()
        }.start()
    }

    private fun prepareWindow() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.decorView.background=appBackgroundDrawable()
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
            if(scroll)setBackgroundColor(Color.TRANSPARENT)else background=appBackgroundDrawable()
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
        val sv = ScrollView(this).apply { isFillViewport = true; clipToPadding = true; background=appBackgroundDrawable(); addView(root) }; styleScrollBar(sv,!hideScrollBar)
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
                background=appBackgroundDrawable()
                sv.setBackgroundColor(Color.TRANSPARENT)
                addView(sv, FrameLayout.LayoutParams(-1, -1))
                addView(dock, FrameLayout.LayoutParams(-1, dockHeight, Gravity.BOTTOM).apply { setMargins(dp(dockMargin), 0, dp(dockMargin), 0) })
            }
            ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
                val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val keyboard=insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val bottom=max(bars.bottom,keyboard)
                val contentBottom=if(dockFollowsIme)bottom else bars.bottom
                sv.setPadding(bars.left, bars.top + dp(4), bars.right, contentBottom + dockHeight + dp(16))
                (dock.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = (if(dockFollowsIme) bottom else bars.bottom) + dp(8); dock.layoutParams = this }
                insets
            }
            sv.setOnScrollChangeListener { _, _, y, _, _ -> homeScrollY = y }
            setContentView(frame)
            sv.alpha=0f; sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener { override fun onPreDraw(): Boolean { sv.viewTreeObserver.removeOnPreDrawListener(this); sv.scrollTo(0, homeScrollY); sv.alpha=1f; return true } })
        }
        return root
    }
    private fun backButton(action: () -> Unit): ImageButton = accentIconButton(R.drawable.ic_back,"Back",action)
    private fun title(root: LinearLayout, value: String, back: Boolean = true, backAction: () -> Unit = { goBack() }, horizontalInset: Int = 16) {
        val top=FrameLayout(this)
        if(back) top.addView(backButton(backAction),FrameLayout.LayoutParams(dp(46),dp(46),Gravity.START or Gravity.CENTER_VERTICAL))
        val heading=label(value,22,true,ink,Gravity.CENTER).apply{fitSingleLine(this,14,22)}
        top.addView(heading,FrameLayout.LayoutParams(-1,dp(46),Gravity.CENTER).apply{if(back){leftMargin=dp(54);rightMargin=dp(54)}})
        root.addView(top,lp(-1,dp(46),0).apply { leftMargin=dp(horizontalInset); rightMargin=dp(horizontalInset) })
    }    private fun label(value: CharSequence, sp: Int = 14, bold: Boolean = false, color: Int = ink, gravity: Int = Gravity.START): TextView = TextView(this).apply { text=if(value is String)tr(value)else value;textSize=sp.toFloat();setTextColor(color);this.gravity=gravity;typeface=android.graphics.Typeface.create("Google Sans Flex",if(bold)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL);includeFontPadding=false;if(bold)letterSpacing=.01f }
    private fun button(value: String, color: Int = accent, fg: Int = Color.WHITE, click: () -> Unit): TextView = TextView(this).apply { val outlined=color==accent;text=if(value=="Choose\nagain")tr("Choose again").replaceFirst(" ","\n")else tr(value);textSize=15f;typeface=android.graphics.Typeface.create("Google Sans Flex",android.graphics.Typeface.BOLD);setTextColor(if(outlined)accent else fg);gravity=Gravity.CENTER;minHeight=dp(52);background=if(outlined)accentOutline(26) else shape(color,26);setPadding(dp(16),0,dp(16),0);setOnClickListener { click() } }
    private fun card(root: LinearLayout, margin: Int = 10): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(surface,30,1); setPadding(dp(16),dp(16),dp(16),dp(16)); root.addView(this,lp(-1,-2,margin).apply { leftMargin=dp(16); rightMargin=dp(16) }) }
    private fun divider(root: LinearLayout, n: Int = 14) { root.addView(Space(this), lp(-1, dp(n),0)) }
    private fun mainMargin(view:View) { (view.layoutParams as? LinearLayout.LayoutParams)?.let { it.leftMargin=dp(16); it.rightMargin=dp(16); view.layoutParams=it } }
    private fun accentedHeader(prefix:String, value:String):CharSequence {val p=tr(prefix);return android.text.SpannableString("$p$value").apply {setSpan(android.text.style.ForegroundColorSpan(accent),p.length,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}}
    private fun coloredHeader(prefix:String, value:String, color:Int):CharSequence {val p=tr(prefix);val v=tr(value.lowercase().replaceFirstChar{it.titlecase()}).uppercase();return android.text.SpannableString("$p$v").apply {setSpan(android.text.style.ForegroundColorSpan(color),p.length,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}}
    private fun collapsibleHeader(title:CharSequence, expanded:Boolean, toggle:()->Unit):FrameLayout = FrameLayout(this).apply {
        addView(label(title,13,true,muted,Gravity.CENTER_VERTICAL),FrameLayout.LayoutParams(-1,dp(36),Gravity.CENTER_VERTICAL).apply { rightMargin=dp(36) })
        val arrow=ImageButton(this@MainActivity).apply {
            setImageResource(R.drawable.ic_expand_more)
            setColorFilter(accent,android.graphics.PorterDuff.Mode.SRC_IN)
            background=null
            setPadding(dp(7),dp(7),dp(7),dp(7))
            rotation=if(expanded)180f else 0f
            contentDescription=if(expanded)"Collapse ${title}" else "Expand ${title}"
            setOnClickListener {
                isEnabled=false
                animate().rotation(if(expanded)0f else 180f).setDuration(220).setInterpolator(DecelerateInterpolator()).withEndAction { toggle() }.start()
            }
        }
        addView(arrow,FrameLayout.LayoutParams(dp(36),dp(36),Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun applyThemeAndHome() { applyLanguageDirection();showHome() }
    private fun showHome() {
        textToSpeech?.stop()
        activeTimer?.cancel()
        val play=button("Let's play  →",accent,moveButtonInk,{ if(players.size>=2){ val incomplete=players.indexOfFirst{it.gender.isBlank() || it.preference.isBlank()}; if(incomplete>=0){ Toast.makeText(this,tr("Choose gender and interaction preferences for every player before playing."),Toast.LENGTH_LONG).show(); editPlayer(incomplete); return@button }; round=1; hasActiveTurn=false; remainingTurnIndices.clear(); orderedTurnPosition=0; openScreen("playMode"){showPlayModeSelection()} } }).apply { textSize=20f;setTextColor(moveButtonInk);minHeight=dp(66);background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.rgb(255,130,55),Color.rgb(255,78,132))).apply { cornerRadius=dp(33).toFloat() };isEnabled=players.size>=2;alpha=if(players.size>=2)1f else .5f }
        val input=EditText(this).apply { hint=tr("Player name"); textSize=17f; setTextColor(ink); setHintTextColor(muted); setSingleLine(true); minHeight=dp(52); background=shape(this@MainActivity.background,18,1); setPadding(dp(15),0,dp(10),0) }
        val add=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        add.addView(input,weight(1f)); add.addView(Space(this),lp(dp(10),1,0)); add.addView(button("Add player +",accent,onAccent()){ val name=input.text.toString().trim(); if(name.isNotEmpty()){ players.add(Player(name)); input.text.clear(); editPlayer(players.lastIndex,true) } },lp(dp(120),dp(52),0))
        val root=base(dock=play,dockFollowsIme=false,hideScrollBar=true)
        val brand=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; orientation=LinearLayout.HORIZONTAL; setPadding(dp(12),dp(10),dp(10),dp(10)); background=shape(surface,30,1) }
        val mark=ImageView(this).apply {
            setImageResource(R.drawable.ic_brand_mark_orange)
            scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription=tr("Truth or Dare")
            if(useSystemColors || !isDark()) setColorFilter(accent,android.graphics.PorterDuff.Mode.SRC_IN)
        }
        brand.addView(mark,lp(dp(36),dp(36),0))
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),0,0,0) }
        copy.addView(label("Truth or Dare",18,true))
        copy.addView(label("MAKE TONIGHT A STORY",9,true,accent).apply { letterSpacing=.14f })
        brand.addView(copy,weight(1f))
        val gear=accentIconButton(R.drawable.ic_settings_gear,"Settings"){openScreen("settings"){showSettings()}};brand.addView(gear,lp(dp(46),dp(46),0))
        root.addView(brand,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(16),dp(4),dp(16),dp(15)) })

        val playersPanel=card(root,0).apply { setPadding(dp(16),dp(16),dp(16),dp(16)) }; (playersPanel.layoutParams as LinearLayout.LayoutParams).setMargins(dp(16),0,dp(16),0)
        playersPanel.addView(collapsibleHeader(accentedHeader("PLAYERS ADDED: ",players.size.toString()),playersPanelExpanded){playersPanelExpanded=!playersPanelExpanded;showHome()})
        if(playersPanelExpanded) {
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
        }

        divider(root,15)
        val levels=card(root,0).apply { setPadding(dp(16),dp(12),dp(16),dp(12)) }; (levels.layoutParams as LinearLayout.LayoutParams).setMargins(dp(16),0,dp(16),0)
        val selectedLevelTitle=levelName(selectedLevel).uppercase()
        levels.addView(collapsibleHeader(coloredHeader("LEVEL SELECTED: ",selectedLevelTitle,levelColor(selectedLevel)),levelsPanelExpanded){levelsPanelExpanded=!levelsPanelExpanded;showHome()})
        if(levelsPanelExpanded) {
        val grid=GridLayout(this).apply { columnCount=2; useDefaultMargins=false }
        val lockedFill=ColorUtils.blendARGB(surface,outline,.55f)
        fun levelCell(key:String, locked:Boolean) {
            val unavailable=locked&&!premium
            val selected=selectedLevel==key&&!unavailable
            val tone=levelColor(key)
            val fill=if(unavailable)lockedFill else if(selected)ColorUtils.blendARGB(surface,tone,if(isDark()).16f else .10f) else surface
            val cell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER; background=shape(fill,22,if(selected)2 else 1,if(unavailable)outline else tone); isClickable=true;setOnClickListener { if(unavailable)showPremium() else {selectedLevel=key;saveState();showHome()} } }
            val headline=LinearLayout(this).apply { gravity=Gravity.CENTER }
            headline.addView(LevelFaceView(key,tone),lp(dp(25),dp(25),0).apply { rightMargin=dp(7) })
            headline.addView(label(levelName(key),13,true,if(unavailable)muted else tone,Gravity.CENTER).apply { textSize=13.65f })
            if(selected) headline.addView(label("✓",12,true,tone,Gravity.CENTER).apply { setPadding(dp(7),0,0,0) })
            cell.addView(headline,lp(-2,dp(28),0))
            cell.addView(label(if(unavailable)"Premium  🔒" else levelDetail(key),11,false,muted,Gravity.CENTER).apply { textSize=11.55f; setPadding(0,dp(1),0,0) })
            val gp=GridLayout.LayoutParams().apply { width=0;height=dp(58);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f); val left=if(grid.childCount%2==0)0 else dp(4);val right=if(grid.childCount%2==0)dp(4) else 0;setMargins(left,dp(2),right,dp(2)) };grid.addView(cell,gp)
        }
        levelCell("soft",false);levelCell("hot",false);levelCell("hard",true);levelCell("extreme",true)
        val customLocked=!premium
        val customSelected=selectedLevel=="mydares"
        val custom=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; isClickable=true; background=if(customSelected)accentOutline(26) else shape(if(customLocked)lockedFill else surface,26,1); setOnClickListener { if(customLocked)showPremium() else {selectedLevel="mydares";saveState();openScreen("custom"){showCustomCards()}} } }
        custom.addView(label("My custom cards ✏️",13,true,if(customSelected)accent else if(customLocked)muted else ink,Gravity.CENTER).apply { textSize=13.65f })
        custom.addView(label(if(customLocked)"Premium  🔒" else "Your custom cards",11,false,if(customSelected)accent else muted,Gravity.CENTER).apply { textSize=11.55f; setPadding(0,dp(2),0,0) })
        grid.addView(custom,GridLayout.LayoutParams().apply{width=-1;height=dp(53);columnSpec=GridLayout.spec(0,2);setMargins(0,dp(2),0,dp(2))});levels.addView(grid)
        }
        if(!premium && levelsPanelExpanded) root.addView(label("Hot, Naughty and My custom cards unlock with Premium.",12,false,muted,Gravity.CENTER).apply{setPadding(0,dp(3),0,0)},lp(-1,-2,0).apply { leftMargin=dp(16);rightMargin=dp(16) })
    }
    private fun editPlayer(index:Int, isNew:Boolean=false) {
        val player=players[index]; val dialog=Dialog(this); var committed=!isNew
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(18),dp(16),dp(18));background=shape(this@MainActivity.background,22,1)};dialog.setContentView(form);dialog.setOnDismissListener{if(isNew && !committed && index in players.indices && players[index]===player)players.removeAt(index);showHome()}
        val top=FrameLayout(this);top.addView(backButton{dialog.dismiss()},FrameLayout.LayoutParams(dp(46),dp(46),Gravity.START or Gravity.CENTER_VERTICAL));top.addView(label("Edit player",18,true,ink,Gravity.CENTER),FrameLayout.LayoutParams(-1,dp(46),Gravity.CENTER));form.addView(top,lp(-1,dp(46),0))
        form.addView(label("Name",12,true).apply{setPadding(0,dp(12),0,0)});val name=EditText(this).apply{setText(player.name);setTextColor(ink);setSingleLine(true);textSize=17f;background=shape(this@MainActivity.background,14,1);setPadding(dp(12),0,dp(12),0)};form.addView(name,lp(-1,dp(50),3))
        fun choices(values:List<String>, initial:String, columns:Int=1, change:(String)->Unit={}):Pair<ViewGroup,()->String>{var selected=initial;val host=if(columns==1)LinearLayout(this).apply{gravity=Gravity.CENTER}else GridLayout(this).apply{columnCount=2};val all=mutableListOf<TextView>();values.forEachIndexed{i,v->val b=button(v,if(v==selected)accent else surface,if(v==selected)accent else ink){};b.tag=v;b.textSize=13f;b.minHeight=dp(46);b.setOnClickListener{selected=v;all.forEach{x->val on=x.tag==v;x.background=if(on)accentOutline(22)else shape(surface,22,1);x.setTextColor(if(on)accent else ink)};change(v)};all.add(b);if(host is LinearLayout)host.addView(b,LinearLayout.LayoutParams(0,dp(46),1f).apply{if(i<values.lastIndex)rightMargin=dp(7)})else host.addView(b,GridLayout.LayoutParams().apply{width=0;height=dp(46);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(if(i%2==0)0 else dp(4),dp(2),if(i%2==0)dp(4)else 0,dp(2))})};return host to {selected}}
        form.addView(label("Gender",12,true).apply{setPadding(0,dp(8),0,dp(4))});val(gender,getGender)=choices(listOf("Man","Woman"),player.gender);form.addView(gender)
        fun comfortLabel(value:Int)=trf("Comfort-zone calibration · {value}/10","value" to value)+if(!premium)"  🔒" else ""
        val calibration=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=shape(if(premium)surface else ColorUtils.blendARGB(surface,outline,.42f),16,1);setPadding(dp(16),dp(8),dp(16),dp(8));isClickable=!premium;setOnClickListener{if(!premium)showPremium()}};val calibrationHead=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val comfortTitle=label(comfortLabel(player.comfort),12,true,if(premium)ink else muted);calibrationHead.addView(comfortTitle,weight(1f));var comfortEnabled=player.comfortEnabled&&premium;calibrationHead.addView(expressiveToggle(comfortEnabled,premium){on->comfortEnabled=on},lp(dp(58),dp(36),0));calibration.addView(calibrationHead);calibration.addView(label(if(premium)"Adjust easier prompts for this player." else "Tap to view Premium plans and unlock per-player prompt adjustment.",10,false,muted));val comfort=SeekBar(this).apply{max=9;progress=player.comfort-1;progressTintList=android.content.res.ColorStateList.valueOf(accent);thumbTintList=android.content.res.ColorStateList.valueOf(accent);backgroundTintList=android.content.res.ColorStateList.valueOf(outline);minimumHeight=dp(34);isEnabled=premium};comfort.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean){comfortTitle.text=comfortLabel(v+1)};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}});calibration.addView(comfort,lp(-1,dp(26),0));val scale=LinearLayout(this);scale.addView(label("1 · Wholesome & soft",10,false,muted),weight(1f));scale.addView(label("10 · Chaos & spicy",10,false,muted,Gravity.END),weight(1f));calibration.addView(scale);form.addView(calibration,lp(-1,-2,6))
        form.addView(label("Wants to interact with",12,true).apply{setPadding(0,dp(8),0,dp(4))});val refreshers=mutableListOf<()->Unit>();val(pref,getPref)=choices(listOf("Men","Women","Men & women","Specific players"),player.preference,2){refreshers.forEach{it()}};form.addView(pref);form.addView(label("Choose specific players to decide exactly who this player can be matched with.",12,false,muted).apply{setPadding(0,dp(8),0,dp(6))})
        val selected=player.specificPlayers.toMutableSet();val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(4),dp(16),dp(4))};val box=ScrollView(this).apply{addView(list);background=shape(surface,14,1);clipToOutline=true;isFillViewport=true;styleScrollBar(this)}
        players.filterIndexed{i,_->i!=index}.forEach{p->val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;background=shape(this@MainActivity.background,14,1);setPadding(dp(10),0,dp(10),0)};val check=CheckBox(this).apply{buttonTintList=android.content.res.ColorStateList.valueOf(accent);isClickable=false};val copy=label("${p.name}  ·  ${tr(p.gender)}",13,true);row.addView(check,lp(dp(40),dp(40),0));row.addView(copy,weight(1f));fun render(){val yes=getPref()=="Specific players";check.isChecked=p.name in selected;row.alpha=if(yes)1f else .38f;copy.setTextColor(if(yes)ink else muted)};row.setOnClickListener{if(getPref()=="Specific players"){if(p.name in selected)selected.remove(p.name)else selected.add(p.name);render()}};refreshers.add(::render);render();list.addView(row,lp(-1,dp(46),2))};val otherPlayers=players.size-1;if(otherPlayers>0){ form.addView(box,LinearLayout.LayoutParams(-1,dp(if(otherPlayers>=3)154 else otherPlayers*48+12)).apply{topMargin=dp(4);bottomMargin=dp(6)}) }
        val validation=label("",11,true,Color.rgb(186,26,26)).apply{visibility=View.GONE};form.addView(validation,lp(-1,-2,8))
        val destructive=Color.rgb(211,47,47);val actions=LinearLayout(this);val remove=button("Remove player",surface,destructive){players.removeAt(index);saveState();dialog.dismiss()}.apply{background=toneOutline(destructive);setTextColor(destructive);textSize=13f;fitSingleLine(this,9,13)};actions.addView(remove,LinearLayout.LayoutParams(0,dp(52),1f).apply{rightMargin=dp(6)});val save=button("Save player",accent,onAccent()){if(getGender().isBlank() || getPref().isBlank()){validation.text=tr("Choose both gender and interaction preferences to save this player.");validation.visibility=View.VISIBLE;return@button};player.name=name.text.toString().trim().ifEmpty{player.name};player.gender=getGender();player.preference=getPref();player.comfort=comfort.progress+1;player.comfortEnabled=comfortEnabled&&premium;player.specificPlayers.clear();player.specificPlayers.addAll(selected);committed=true;saveState();dialog.dismiss()}.apply{textSize=13f;fitSingleLine(this,9,13)};actions.addView(save,LinearLayout.LayoutParams(0,dp(52),1f).apply{leftMargin=dp(6)});form.addView(actions,lp(-1,dp(52),6));dialog.show();dialog.window?.apply{decorView.setPadding(0,0,0,0);setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));setLayout(resources.displayMetrics.widthPixels-dp(32),if(players.size<=3)WindowManager.LayoutParams.WRAP_CONTENT else (resources.displayMetrics.heightPixels*.88f).toInt());setGravity(Gravity.CENTER);attributes=attributes.apply{dimAmount=.4f};addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)}
    }
    private fun findRadio(group:RadioGroup):String { val id=group.checkedRadioButtonId; return group.findViewById<RadioButton>(id)?.text?.toString() ?: "" }

    private fun showSettings() {
        val root=base(); title(root,"Settings")
        divider(root,16)
        val group=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(group,lp(-1,-2,0).apply { leftMargin=dp(16); rightMargin=dp(16) })
        val chosenLanguage=selectedLanguage()
        settingsRow(group,"language","Language",languageDisplayName(chosenLanguage),{openScreen("language"){showLanguage()}},28,3,3)
        settingsRow(group,"appearance","Appearance","Themes and colors",{openScreen("appearance"){showAppearance()}},3,3,3)
        settingsRow(group,"gameplay","Gameplay","Rules and preferences",{openScreen("gameplay"){showGameplay()}},3,3,3)
        settingsRow(group,"help","Help","Support tickets, feature requests",{openScreen("help"){showHelp()}},3,3,3)
        settingsRow(group,"about","General Information","Privacy policy, Game rules, App info",{openScreen("about"){showAbout()}},3,28,3)
    }    private fun settingsRow(root:LinearLayout,icon:String,name:String,desc:String,action:()->Unit,topRadius:Int=24,bottomRadius:Int=24,verticalMargin:Int=0,showArrow:Boolean=true){
        val c=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(68); background=groupedShape(surface,topRadius,bottomRadius,1); setPadding(dp(18),dp(8),dp(18),dp(8)); isClickable=true; setOnClickListener{action()} }
        root.addView(c,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(verticalMargin) })
        val iconBackground=when(icon){
            "appearance"->Color.rgb(54,31,99)
            "gameplay"->Color.rgb(20,62,92)
            "language"->Color.rgb(20,78,70)
            "help"->Color.rgb(88,101,242)
            else->Color.rgb(89,18,70)
        }
        val iconForeground=when(icon){
            "appearance"->Color.rgb(190,164,255)
            "gameplay"->Color.rgb(66,196,255)
            "language"->Color.rgb(52,229,176)
            "help"->Color.WHITE
            else->Color.rgb(255,141,207)
        }
        val iconHolder=FrameLayout(this).apply { background=shape(if(icon=="appearance")Color.rgb(255,220,196) else iconBackground,24); clipToOutline=true }
        if(icon=="appearance") iconHolder.addView(PaletteIconView().apply{contentDescription=name},FrameLayout.LayoutParams(dp(31),dp(31),Gravity.CENTER))
        else if(icon=="language") iconHolder.addView(label("文",19,true,iconForeground,Gravity.CENTER).apply{contentDescription=name},FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER))
        else {
            val iconRes=when(icon){ "gameplay"->R.drawable.ic_gameplay; "help"->R.drawable.ic_discord; "about"->R.drawable.ic_info; else->R.drawable.ic_info }
            iconHolder.addView(ImageView(this).apply { setImageResource(iconRes); scaleType=ImageView.ScaleType.FIT_CENTER;setColorFilter(iconForeground,android.graphics.PorterDuff.Mode.SRC_IN);contentDescription=name },FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER))
        }
        c.addView(iconHolder,lp(dp(48),dp(48),0))
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),0,0,0) }
        copy.addView(label(name,16,true)); copy.addView(label(desc,12,false,muted)); c.addView(copy,weight(1f))
        if(showArrow) c.addView(label("›",28,false,muted,Gravity.CENTER),lp(dp(28),dp(48),0))
    }
    private val discordInviteDisplay="discord.gg/EJ7ThCgw4g"
    private val discordInviteUrl="https://$discordInviteDisplay"
    private fun showHelp(){
        val root=base();title(root,"Help");divider(root,16)
        val discordBlue=Color.rgb(88,101,242)
        val discordPurple=Color.rgb(124,58,237)
        val banner=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(22),dp(22),dp(22))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(discordBlue,discordPurple)).apply{cornerRadius=dp(28).toFloat()}
            isClickable=true
            setOnClickListener{openDiscordInvite()}
        }
        val heading=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val logo=FrameLayout(this).apply{background=shape(Color.WHITE,22);clipToOutline=true}
        logo.addView(ImageView(this).apply{setImageResource(R.drawable.ic_discord);setColorFilter(discordBlue,android.graphics.PorterDuff.Mode.SRC_IN);contentDescription="Discord"},FrameLayout.LayoutParams(dp(34),dp(34),Gravity.CENTER))
        heading.addView(logo,lp(dp(56),dp(56),0))
        val headingCopy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),0,0,0)}
        headingCopy.addView(label("Join my Discord",20,true,Color.WHITE))
        headingCopy.addView(label("SUPPORT • IDEAS • COMMUNITY",10,true,Color.argb(220,255,255,255)).apply{letterSpacing=.08f})
        heading.addView(headingCopy,weight(1f));banner.addView(heading)
        banner.addView(label("Need help or have an idea?",17,true,Color.WHITE).apply{setPadding(0,dp(22),0,dp(8))})
        banner.addView(label("To open a support ticket or submit a feature request, please join my Discord server.",14,false,Color.argb(235,255,255,255)).apply{setLineSpacing(dp(3).toFloat(),1f)})
        banner.addView(label(discordInviteDisplay,13,true,Color.WHITE,Gravity.START).apply{setPadding(0,dp(22),0,dp(14))},lp(-1,-2,0))
        banner.addView(button("Join Discord  →",Color.WHITE,discordBlue){openDiscordInvite()}.apply{background=shape(Color.WHITE,20);textSize=15f},lp(-1,dp(54),0))
        root.addView(banner,lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16)})
    }
    private fun openDiscordInvite(){
        val browserIntent=Intent(Intent.ACTION_VIEW,Uri.parse(discordInviteUrl)).apply{addCategory(Intent.CATEGORY_BROWSABLE)}
        runCatching{startActivity(browserIntent)}
    }
    private fun showLanguage(){
        val root=base();title(root,"Language")
        val selected=selectedLanguage()
        val languages=listOf("system")+supportedLanguages
        val group=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(group,lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16);topMargin=dp(16)})
        languages.forEachIndexed { index,code ->
            val active=code==selected
            val row=LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL;isClickable=true
                setPadding(dp(18),0,dp(18),0)
                val topRadius=if(index==0)28 else 3
                val bottomRadius=if(index==languages.lastIndex)28 else 3
                background=groupedShape(if(active)ColorUtils.blendARGB(surface,accent,if(isDark()).12f else .08f)else surface,topRadius,bottomRadius,1,if(active)accent else outline)
                setOnClickListener {
                    prefs.edit().putString("uiLanguage",code).apply()
                    loadDecks();configureSpeechVoice();applyLanguageDirection();showLanguage()
                }
            }
            row.addView(label(languageDisplayName(code),14,true,if(active)accent else ink,Gravity.CENTER_VERTICAL),weight(1f))
            if(active)row.addView(label("✓",16,true,accent,Gravity.CENTER),lp(dp(28),dp(44),0))
            group.addView(row,lp(-1,dp(52),0).apply{bottomMargin=if(index==languages.lastIndex)0 else dp(3)})
        }
    }
    private fun showAppearance(){
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
        val group=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(group,lp(-1,-2,16).apply { leftMargin=dp(16); rightMargin=dp(16) })
        fun groupCard(topRadius:Int,bottomRadius:Int)=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=groupedShape(surface,topRadius,bottomRadius,1); setPadding(dp(16),dp(16),dp(16),dp(16)) }
        val c=groupCard(28,3); group.addView(c,lp(-1,-2,0).apply{bottomMargin=dp(3)})
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
            lateinit var choice:TextView
            choice=button(value.toString(),if(active&&!locked)accent else surface,if(active&&!locked)accent else muted) { if(!locked && value!=truthLimit) animateNumberChoice(choice){ truthLimit=value; saveState(); showGameplay() } }.apply { minHeight=dp(44); textSize=15f; isEnabled=!locked; background=if(active&&!locked)accentOutline(18)else shape(surface,18,1) }
            values.addView(choice,LinearLayout.LayoutParams(0,dp(44),1f).apply { if(value>1) leftMargin=dp(5) })
        }
        c.addView(values)
        val truthSummary=trf(if(truthLimit==1)"Each player can choose up to {count} truth before Dare is required." else "Each player can choose up to {count} truths before Dare is required.","count" to truthLimit)
        c.addView(label(if(locked)"Unlock Premium Levels to choose a custom truth limit." else truthSummary,12,false,muted).apply { setPadding(0,dp(10),0,dp(2)) })
        val reverse=groupCard(3,28); group.addView(reverse,lp(-1,-2,0))
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val reverseCopy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};reverseCopy.addView(label("Reverse Dare"+if(locked)"  🔒" else "",16,true,if(locked)muted else ink));reverseCopy.addView(label(if(locked)"Premium feature · requires Premium Levels." else "After consecutive dares, allow the player to assign a dare to another random player.",12,false,muted));head.addView(reverseCopy,weight(1f));head.addView(expressiveToggle(!locked&&reverseDares,!locked){on->reverseDares=on;saveState();showGameplay()},lp(dp(64),dp(40),0));reverse.addView(head)
        reverse.addView(label("Dares before Reverse Dare",13,true,if(locked)muted else ink).apply{setPadding(0,dp(14),0,dp(7))});val counts=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;alpha=if(locked).48f else 1f};(1..5).forEach{value->val active=value==reverseDareLimit;lateinit var choice:TextView;choice=button(value.toString(),if(active&&!locked)accent else surface,if(active&&!locked)accent else muted){if(!locked && value!=reverseDareLimit)animateNumberChoice(choice){reverseDareLimit=value;saveState();showGameplay()}}.apply{minHeight=dp(44);textSize=15f;isEnabled=!locked;background=if(active&&!locked)accentOutline(18)else shape(surface,18,1)};counts.addView(choice,LinearLayout.LayoutParams(0,dp(44),1f).apply{if(value>1)leftMargin=dp(5)})};reverse.addView(counts)
    }
    private fun showAbout(){
        val root=base(); title(root,"General Information")
        divider(root,16)
        val group=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(group,lp(-1,-2,0).apply { leftMargin=dp(16); rightMargin=dp(16) })
        settingsRow(group,"◆","Game rules","How to play and keep it respectful",{openScreen("rules"){showGameRules()}},28,3,3,showArrow=false)
        settingsRow(group,"ⓘ","Privacy policy","How Truth or Dare handles your data",{openScreen("privacy"){showPrivacyPolicy()}},3,3,3,showArrow=false)
        settingsRow(group,"✦","About app","Version and Android compatibility",{openScreen("appInfo"){showAppInformation()}},3,if(premium)28 else 3,3,showArrow=false)
        if(!premium) {
            settingsRow(group,"✦","Premium plans","Compare subscriptions and lifetime access",{openScreen("premiumPlans"){showPremiumPlans()}},3,28,3,showArrow=false)
        }
    }    private val privacyPolicyDisplayUrl="github.com/joaofidalgo1998/joaofidalgo1998/blob/main/truth-or-dare-privacy-policy.md"
    private val privacyPolicyUrl="https://$privacyPolicyDisplayUrl"
    private fun linkedInformationText(body:String):android.text.SpannableString {
        val linked=android.text.SpannableString(body)
        var start=body.indexOf(privacyPolicyDisplayUrl)
        while(start>=0){
            linked.setSpan(object:android.text.style.ClickableSpan(){
                override fun onClick(widget:View){
                    val browserIntent=Intent(Intent.ACTION_VIEW,Uri.parse(privacyPolicyUrl)).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
                    runCatching { startActivity(browserIntent) }
                }
                override fun updateDrawState(ds:android.text.TextPaint){ ds.color=accent;ds.isUnderlineText=true }
            },start,start+privacyPolicyDisplayUrl.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start=body.indexOf(privacyPolicyDisplayUrl,start+privacyPolicyDisplayUrl.length)
        }
        return linked
    }
    private fun informationPage(titleText:String, body:String) {
        val root=base(scroll=false)
        root.setPadding(dp(16),0,dp(16),dp(16))
        title(root,titleText,true,{goBack()},0)
        val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(surface,30,1); clipToOutline=true; setPadding(0,dp(16),0,dp(16)) }
        val scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=true };styleScrollBar(scroll)
        scroll.addView(label(linkedInformationText(body),15,false,ink).apply { movementMethod=android.text.method.LinkMovementMethod.getInstance();highlightColor=Color.TRANSPARENT;setLineSpacing(dp(7).toFloat(),1.0f);setPadding(dp(18),0,dp(18),dp(10)) })
        c.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(c,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(10) })
    }
    private data class GameRule(val title:String,val detail:String,val highlighted:Boolean=false,val premium:Boolean=false)
    private fun ruleText(english:String,spanish:String):String = if(effectiveLanguage()=="es")spanish else english
    private fun gameRuleItems():List<GameRule> = listOf(
        GameRule(ruleText("Play safely and respect every player","Juega de forma segura y respeta a todos"),ruleText("Participation is always voluntary. Anyone may skip a prompt, change their mind, or stop playing. Stop immediately if someone feels uncomfortable.","Participar siempre es voluntario. Cualquiera puede saltarse una carta, cambiar de opinión o dejar de jugar. Detente inmediatamente si alguien se siente incómodo."),highlighted=true),
        GameRule(ruleText("1. Add players","1. Añade jugadores"),ruleText("Add at least two players to the game.","Añade al menos dos jugadores a la partida.")),
        GameRule(ruleText("2. Edit their preferences","2. Edita sus preferencias"),ruleText("Set each player's gender, interaction preferences, and specific players.","Configura el género, las preferencias de interacción y los jugadores específicos de cada persona.")),
        GameRule(ruleText("3. Choose the game level","3. Elige el nivel de juego"),ruleText("Choose Casual, Flirty, Hot, Naughty, or your custom cards.","Elige Casual, Coqueto, Caliente, Travieso o tus cartas personalizadas.")),
        GameRule(ruleText("4. Start the game","4. Empieza la partida"),ruleText("Tap Let's play, then choose Spin the Wheel or Taking turns.","Pulsa Juguemos y elige Girar la ruleta o Por turnos.")),
        GameRule(ruleText("My custom cards","Mis cartas personalizadas"),ruleText("Create, edit, import, export, and play your own cards.","Crea, edita, importa, exporta y juega con tus propias cartas."),premium=true),
        GameRule(ruleText("Gameplay settings","Ajustes de juego"),ruleText("Use comfort-zone calibration, limit consecutive truths, or enable Reverse Dare.","Usa la calibración de la zona de confort, limita las verdades consecutivas o activa Reto inverso."),premium=true)
    )
    private fun gameRulesPanel():LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        gameRuleItems().forEachIndexed { index,rule ->
            val card=LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(14),dp(12),dp(14),dp(12))
                val safetyGreen=Color.rgb(52,229,176)
                background=if(rule.highlighted) shape(ColorUtils.blendARGB(surface,safetyGreen,if(isDark()).18f else .10f),18,2,safetyGreen) else shape(surface,18,1)
                addView(label(if(rule.premium)"PREMIUM · ${rule.title}" else rule.title,14,true,if(rule.highlighted)safetyGreen else if(rule.premium)accent else ink))
                addView(label(rule.detail,12,false,muted).apply{setPadding(0,dp(5),0,0);setLineSpacing(dp(2).toFloat(),1f)})
            }
            addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=if(index==gameRuleItems().lastIndex)0 else dp(7)})
        }
    }
    private fun showGameRules(){
        val root=base();title(root,"Game rules");divider(root,12)
        root.addView(label(ruleText("Follow these steps to start, then explore Premium features whenever you want.","Sigue estos pasos para empezar y explora las funciones Premium cuando quieras."),14,false,muted).apply{setPadding(0,0,0,dp(10))},lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16)})
        root.addView(gameRulesPanel(),lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16)})
    }
    private fun continueAfterFirstRun(){ if(!prefs.getBoolean("premiumWelcomeSeen",false)){offerAfterPrivacy=true;window.decorView.post{requestFirstOffer()}} }
    private fun showGameRulesNotice(){
        val dialog=Dialog(this)
        val shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=shape(surface,28,1);clipToOutline=true;setPadding(dp(16),dp(18),dp(16),dp(18))}
        shell.addView(label("Game rules",22,true))
        shell.addView(label(ruleText("A quick guide before your first game.","Una guía rápida antes de tu primera partida."),13,false,muted).apply{setPadding(0,dp(6),0,dp(10))})
        val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}
        scroll.addView(gameRulesPanel().apply{setPadding(0,0,0,dp(8))})
        shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        shell.addView(button(ruleText("Start playing","Empezar a jugar"),accent,onAccent()){prefs.edit().putBoolean("gameRulesSeen",true).apply();dialog.dismiss();continueAfterFirstRun()},lp(-1,dp(54),0))
        dialog.setContentView(shell);dialog.setCancelable(false);dialog.show()
        dialog.window?.apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));val dialogHeight=((resources.displayMetrics.heightPixels*.76f).toInt()+dp(40)).coerceAtMost(resources.displayMetrics.heightPixels-dp(32));setLayout((resources.displayMetrics.widthPixels*.90f).toInt(),dialogHeight);setGravity(Gravity.CENTER);attributes=attributes.apply{dimAmount=.42f;if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)blurBehindRadius=dp(22)};addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)}
    }
    private fun privacyText():String = """
Truth or Dare is a local party game. It does not require an account and does not operate an advertising, analytics, or remote gameplay server.

Data stored on your device may include player names, gender and interaction preferences, specific-player selections, comfort-zone settings, selected game level, custom cards, gameplay progress, appearance and gameplay settings, and a protected Premium entitlement status. This information is used only to provide the app's features and resume your game.

The app does not sell your information. The developer does not receive or share your locally stored game data. Truth or Dare does not request access to your location, contacts, camera, microphone, Bluetooth, or other nearby devices.

When you choose a Premium plan or restore purchases, the app uses Google Play Billing. Google Play processes the transaction and provides the app with product and purchase status information needed to unlock and acknowledge the purchase. The app does not receive or store your payment card details. A protected Premium status may be cached locally for up to 30 days so paid features can continue working temporarily without internet access.

If you enable prompt narration, the displayed prompt is passed to your device's configured text-to-speech engine. Its processing is controlled by that engine and its provider.

Custom card import and export occurs only when you choose a file or destination through Android's system file picker. Exported files remain under your control.

Local data remains on the device until you edit it, delete it, clear the app's storage, or uninstall the app. Files you export must be deleted separately. Google Play may retain purchase records under its own policies.

Truth or Dare is intended for adults and is not directed to children.

The published privacy policy is available at: github.com/joaofidalgo1998/joaofidalgo1998/blob/main/truth-or-dare-privacy-policy.md

For privacy questions, use the contact options available through that GitHub profile.

If this policy changes, the current text will be provided in the app and on its published privacy-policy page.
""".trimIndent()
    private fun showPrivacyPolicy(){ informationPage("Privacy policy",trKey("__privacy_policy_body",privacyText())) }
    private fun installedVersion():String = runCatching { packageManager.getPackageInfo(packageName,0).versionName ?: "Unknown" }.getOrDefault("Unknown")
    private fun showAppInformation(){ informationPage("About app","${tr("App version")}\n${installedVersion()}\n\n${tr("Minimum SDK")}\nAndroid 7.0 · API 24\n\n${tr("Target SDK")}\nAndroid 16 · API 36\n\n${tr("Supported API levels")}\nAPI 24 to API 36") }
    private fun showPrivacyNotice(){
        val dialog=Dialog(this)
        val shell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(surface,28,1); clipToOutline=true; setPadding(dp(18),dp(18),dp(18),dp(18)) }
        shell.addView(label("Privacy policy",22,true))
        shell.addView(label("Before you start, please read how Truth or Dare handles your data.",13,false,muted).apply { setPadding(0,dp(6),0,dp(8)) })
        shell.addView(label(ruleText("You can revisit the privacy policy in Settings > General Information.","Puedes volver a consultar la política de privacidad en Ajustes > Información general."),12,true,accent).apply{setPadding(0,0,0,dp(10))})
        val scroll=ScrollView(this).apply { setPadding(0,0,dp(2),0) };styleScrollBar(scroll)
        scroll.addView(label(linkedInformationText(trKey("__privacy_policy_body",privacyText())),13,false,ink).apply { movementMethod=android.text.method.LinkMovementMethod.getInstance();highlightColor=Color.TRANSPARENT;setLineSpacing(dp(5).toFloat(),1f);setPadding(0,0,dp(18),dp(8)) })
        shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f).apply { rightMargin=dp(-16) })
        shell.addView(button("Understood",accent,onAccent()){ prefs.edit().putBoolean("privacySeen",true).apply(); dialog.dismiss(); if(!prefs.getBoolean("gameRulesSeen",false))window.decorView.post{showGameRulesNotice()}else continueAfterFirstRun() },lp(-1,dp(54),0))
        dialog.setContentView(shell); dialog.setCancelable(false); dialog.show()
        dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); val dialogHeight=((resources.displayMetrics.heightPixels*.76f).toInt()+dp(40)).coerceAtMost(resources.displayMetrics.heightPixels-dp(32)); setLayout((resources.displayMetrics.widthPixels*.90f).toInt(),dialogHeight); setGravity(Gravity.CENTER); attributes=attributes.apply { dimAmount=.42f; if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) blurBehindRadius=dp(22) }; addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND) }
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
    private fun resetOrderedTurnIndices(){
        if(orderedTurnIndices.size!=players.size || orderedTurnIndices.toSet()!=players.indices.toSet()){
            orderedTurnIndices.clear();orderedTurnIndices.addAll(players.indices)
        }
    }
    private fun showPlayModeSelection(){
        val root=base();title(root,ruleText("Choose how to play","Elige cómo jugar"));divider(root,14)
        root.addView(label(ruleText("How should players take turns?","¿Cómo deben turnarse los jugadores?"),15,false,muted,Gravity.CENTER).apply{setPadding(0,0,0,dp(10))},lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16)})
        fun option(symbol:String,name:String,description:String,action:()->Unit){
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=shape(surface,24,1);setPadding(dp(16),dp(16),dp(16),dp(16));isClickable=true;setOnClickListener{action()}}
            row.addView(label(symbol,25,true,accent,Gravity.CENTER),lp(dp(52),dp(52),0).apply{rightMargin=dp(12)})
            val copy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(label(name,17,true));addView(label(description,12,false,muted).apply{setPadding(0,dp(4),0,0);setLineSpacing(dp(2).toFloat(),1f)})}
            row.addView(copy,weight(1f));row.addView(label("›",26,true,accent,Gravity.CENTER),lp(dp(30),dp(52),0))
            root.addView(row,lp(-1,-2,6).apply{leftMargin=dp(16);rightMargin=dp(16)})
        }
        option("◉",ruleText("Spin the Wheel","Girar la ruleta"),ruleText("Randomly choose each player with the animated wheel.","Elige a cada jugador al azar con la ruleta animada.")){playMode="spin";currentScreen="spinner";showPlayerSpinner()}
        option("↕",ruleText("Taking turns","Por turnos"),ruleText("Set a player order and repeat it throughout the game.","Establece un orden de jugadores y repítelo durante la partida.")){playMode="ordered";resetOrderedTurnIndices();openScreen("turnOrder"){showTurnOrderSetup()}}
    }
    private fun showTurnOrderSetup(){
        resetOrderedTurnIndices()
        val start=button(ruleText("Start game  →","Empezar partida  →"),accent,onAccent()){
            if(orderedTurnIndices.isEmpty())return@button
            playMode="ordered";orderedTurnPosition=0;turn=orderedTurnIndices.first();round=1;hasActiveTurn=true;currentScreen="choices";showChoices()
        }.apply{textSize=17f}
        val root=base(dock=start);title(root,ruleText("Taking turns","Por turnos"));divider(root,12)
        root.addView(label(ruleText("Touch and hold the four-line handle, then drag players into the order you want. The order repeats after the last player.","Mantén pulsado el control de cuatro líneas y arrastra a los jugadores al orden que quieras. El orden se repite después del último jugador."),14,false,muted,Gravity.CENTER).apply{setPadding(0,0,0,dp(10));setLineSpacing(dp(2).toFloat(),1f)},lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16)})
        val holders=mutableListOf<FrameLayout>()
        val rows=mutableListOf<LinearLayout>()
        val dropMarkers=mutableListOf<View>()
        var previewDestination=-1
        fun clearPreview(animate:Boolean=true){
            previewDestination=-1
            dropMarkers.forEach{it.visibility=View.INVISIBLE}
            rows.forEach{row->row.animate().cancel();if(animate)row.animate().translationY(0f).setDuration(150).setInterpolator(DecelerateInterpolator()).start()else row.translationY=0f}
        }
        fun previewInsertion(from:Int,target:Int,after:Boolean){
            if(from !in orderedTurnIndices.indices)return
            var insertion=target+if(after)1 else 0
            if(from<insertion)insertion--
            val destination=insertion.coerceIn(0,orderedTurnIndices.lastIndex)
            dropMarkers.forEach{it.visibility=View.INVISIBLE}
            dropMarkers[destination].apply{visibility=View.VISIBLE;bringToFront()}
            if(previewDestination==destination)return
            previewDestination=destination
            val slot=(holders.firstOrNull{it.height>0}?.height?:dp(76)).toFloat()
            rows.forEachIndexed{index,row->
                val offset=when{
                    destination<from&&index in destination until from->slot
                    destination>from&&index in (from+1)..destination->-slot
                    else->0f
                }
                row.animate().cancel();row.animate().translationY(offset).setDuration(170).setInterpolator(DecelerateInterpolator()).start()
            }
        }
        orderedTurnIndices.forEachIndexed{position,playerIndex->
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;background=shape(surface,20,1);setPadding(dp(12),dp(10),dp(10),dp(10))}
            row.addView(label("${position+1}",15,true,accent,Gravity.CENTER).apply{background=accentOutline(21)},lp(dp(42),dp(42),0).apply{rightMargin=dp(12)})
            row.addView(label(players[playerIndex].name,16,true),weight(1f))
            val handle=object:View(this){
                private val linePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=muted;strokeWidth=dp(3).toFloat();strokeCap=Paint.Cap.ROUND}
                override fun onDraw(canvas:Canvas){super.onDraw(canvas);val center=height/2f;for(i in 0..3){val y=center+(i-1.5f)*dp(6);canvas.drawLine(dp(11).toFloat(),y,(width-dp(11)).toFloat(),y,linePaint)}}
            }.apply{contentDescription=ruleText("Touch and hold to reorder","Mantén pulsado para reordenar");isLongClickable=true;setOnLongClickListener{performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);val started=startDragAndDrop(android.content.ClipData.newPlainText("player-order",position.toString()),View.DragShadowBuilder(row),position,0);if(started){row.alpha=0f;row.bringToFront()};started}}
            row.addView(handle,lp(dp(52),dp(52),0))
            val holder=FrameLayout(this).apply{clipChildren=false;clipToPadding=false}
            holder.addView(row,FrameLayout.LayoutParams(-1,-2).apply{topMargin=dp(6);bottomMargin=dp(6)})
            val dropMarker=View(this).apply{visibility=View.INVISIBLE;background=GradientDrawable().apply{setColor(accent);cornerRadius=dp(2).toFloat()}}
            holder.addView(dropMarker,FrameLayout.LayoutParams(-1,dp(4),Gravity.CENTER).apply{leftMargin=dp(14);rightMargin=dp(14)})
            holders.add(holder);rows.add(row);dropMarkers.add(dropMarker)
            holder.setOnDragListener{_,event->when(event.action){
                android.view.DragEvent.ACTION_DRAG_STARTED->event.localState is Int
                android.view.DragEvent.ACTION_DRAG_LOCATION->{val from=event.localState as? Int;if(from!=null)previewInsertion(from,position,event.y>holder.height/2f);true}
                android.view.DragEvent.ACTION_DROP->{val from=event.localState as? Int;if(from!=null&&from in orderedTurnIndices.indices){var insertion=position+if(event.y>holder.height/2f)1 else 0;if(from<insertion)insertion--;val destination=insertion.coerceIn(0,orderedTurnIndices.lastIndex);if(destination!=from){val moved=orderedTurnIndices.removeAt(from);orderedTurnIndices.add(destination,moved);clearPreview(false);showTurnOrderSetup()}};true}
                android.view.DragEvent.ACTION_DRAG_ENDED->{rows.forEach{it.alpha=1f};clearPreview();true}
                else->true
            }}
            root.addView(holder,lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16)})
        }
        orderAnimationFrom=-1;orderAnimationTo=-1
    }
    private fun continueToNextTurn(){
        if(playMode=="ordered" && orderedTurnIndices.isNotEmpty()){
            orderedTurnPosition++
            if(orderedTurnPosition>=orderedTurnIndices.size){orderedTurnPosition=0;round++}
            turn=orderedTurnIndices[orderedTurnPosition];hasActiveTurn=true;currentScreen="choices";showChoices()
        }else{currentScreen="spinner";showPlayerSpinner()}
    }
    private fun showPlayerSpinner(){
        textToSpeech?.stop()
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
        val spinSound=android.media.MediaPlayer.create(this,R.raw.wheel_spin)?.apply{isLooping=false;seekTo(0);start()}
        ValueAnimator.ofFloat(0f,targetRotation).apply { duration=4000; interpolator=DecelerateInterpolator(); addUpdateListener { wheel.wheelRotation=it.animatedValue as Float; wheel.invalidate() }; doOnEnd { spinSound?.runCatching{if(isPlaying)stop()};spinSound?.release();turn=chosen; hasActiveTurn=true; if(reverseDarePending){reverseDarePending=false;currentScreen="card";showCard(nextCard("dare"))}else{currentScreen="choices";showChoices()} }; start() }
    }
    private fun ValueAnimator.doOnEnd(action:()->Unit) { addListener(object:android.animation.AnimatorListenerAdapter(){ override fun onAnimationEnd(animation:android.animation.Animator){ action() } }) }
    private fun showChoices(){
        textToSpeech?.stop()
        val root=base(scroll=false);val player=players[turn%players.size];val truthAllowed=!(limitTruths&&player.truthStreak>=truthLimit);val reverseReady=premium&&reverseDares&&player.dareStreak>=reverseDareLimit
        val header=FrameLayout(this);header.addView(backButton{goBack()},FrameLayout.LayoutParams(dp(46),dp(46),Gravity.START));val copy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;addView(label(trf("ROUND {round}","round" to round),10,true,accent,Gravity.CENTER));addView(label(trf("{player}'s turn","player" to player.name),21,true,ink,Gravity.CENTER))};header.addView(copy,FrameLayout.LayoutParams(-1,dp(52),Gravity.CENTER).apply{leftMargin=dp(54);rightMargin=dp(54)});root.addView(header,lp(-1,dp(56),0))
        root.addView(label(if(!truthAllowed)"Truth is locked this turn — choose Dare to continue." else if(reverseReady)"Reverse Dare is ready — spin to assign a dare." else "Choose your move",if(truthAllowed)17 else 14,false,if(truthAllowed)muted else accent,Gravity.CENTER),lp(-1,dp(42),0))
        val truth=button("✦\n${tr("Truth").uppercase()}\n${tr("Be honest")}",if(truthAllowed)truthColor else ColorUtils.blendARGB(surface,outline,.65f),if(truthAllowed)moveButtonInk else muted){if(truthAllowed)openScreen("card"){showCard(nextCard("truth"))}}.apply{textSize=22f;isEnabled=truthAllowed;alpha=if(truthAllowed)1f else .55f};if(reverseReady)root.addView(button(if(playMode=="ordered")ruleText("Reverse Dare · assign it yourself","Reto inverso · asígnalo tú") else "Reverse Dare · spin for player",accent,onAccent()){if(playMode=="ordered"){currentScreen="card";showCard(nextCard("dare"))}else{reverseDarePending=true;showPlayerSpinner()}}.apply{textSize=13f},lp(-1,dp(46),2));root.addView(truth,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(5);bottomMargin=dp(3)})
        val dare=button("⚡\n${tr("Dare").uppercase()}\n${tr("Take the challenge")}",dareColor,moveButtonInk){openScreen("card"){showCard(nextCard("dare"))}}.apply{textSize=22f};root.addView(dare,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(3);bottomMargin=dp(2)})
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
        currentCard=card;val player=players[turn%players.size];val doneTone=Color.rgb(52,229,176);val next=button("Done · next turn →",surface,doneTone){if(card.kind=="truth"){player.truthStreak++;player.dareStreak=0}else{player.truthStreak=0;player.dareStreak++};saveState();if(navigationStack.isNotEmpty()&&navigationStack.last()=="choices")navigationStack.removeLast();continueToNextTurn()}.apply{background=toneOutline(doneTone);setTextColor(doneTone);textSize=16f;minHeight=dp(72);fitSingleLine(this,10,16)};val choose=!(limitTruths&&player.truthStreak>=truthLimit);val dock=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};if(choose){val again=button("Choose again",accent,onAccent()){activeTimer?.cancel();currentScreen="choices";showChoices()}.apply{textSize=16f;fitSingleLine(this,10,16)};dock.addView(again,LinearLayout.LayoutParams(0,dp(72),.48f).apply{rightMargin=dp(8)});dock.addView(next,LinearLayout.LayoutParams(0,dp(72),.52f))}else dock.addView(next,LinearLayout.LayoutParams(-1,dp(72)))
        val root=base(scroll=false);val header=FrameLayout(this);header.addView(backButton{goBack()},FrameLayout.LayoutParams(dp(46),dp(46),Gravity.START or Gravity.CENTER_VERTICAL));header.addView(label(if(card.kind=="truth")"Truth time" else "Dare time",22,true,ink,Gravity.CENTER),FrameLayout.LayoutParams(-1,dp(46),Gravity.CENTER));val speaker=ImageButton(this).apply{setImageResource(if(ttsEnabled)R.drawable.ic_volume_on else R.drawable.ic_volume_off);setColorFilter(accent,android.graphics.PorterDuff.Mode.SRC_IN);background=accentOutline(23);setPadding(dp(11),dp(11),dp(11),dp(11));contentDescription=if(ttsEnabled)"Mute speech" else "Enable speech";setOnClickListener{if(ttsEnabled)textToSpeech?.stop();animate().rotationBy(180f).setDuration(180).withEndAction{ttsEnabled=!ttsEnabled;saveState();showCard(card)}.start()}};header.addView(speaker,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.END or Gravity.CENTER_VERTICAL));root.addView(header,lp(-1,dp(46),0));val display=renderPrompt(card.text,player,card.targetName);if(ttsEnabled)textToSpeech?.speak(display,TextToSpeech.QUEUE_FLUSH,null,"prompt");val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=shape(surface,30,1);setPadding(dp(18),dp(18),dp(18),dp(18));addView(promptLabel(display))};root.addView(c,LinearLayout.LayoutParams(-1,0,1f).apply{setMargins(0,dp(10),0,dp(8))});root.addView(dock,LinearLayout.LayoutParams(-1,dp(72)).apply{setMargins(0,0,0,dp(8))})
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
        fun option(kind:String):TextView = button(kind.replaceFirstChar { it.titlecase() },surface,ink) { selected=kind; options.forEach { view -> val active=view.tag==selected; view.background=if(active)accentOutline(18)else shape(surface,18,1); view.setTextColor(if(active)accent else ink) } }.apply { tag=kind; textSize=15f; minHeight=dp(54) }
        val truth=option("truth"); val dare=option("dare"); options.add(truth); options.add(dare)
        picker.addView(truth,LinearLayout.LayoutParams(0,dp(54),1f).apply { rightMargin=dp(5) }); picker.addView(dare,LinearLayout.LayoutParams(0,dp(54),1f).apply { leftMargin=dp(5) })
        options.forEach { view -> val active=view.tag==selected; view.background=if(active)accentOutline(18)else shape(surface,18,1); view.setTextColor(if(active)accent else ink) }
        form.addView(picker)
        val input=EditText(this).apply { hint=tr("Write a custom card"); setText(old?.text?:""); setTextColor(ink); setHintTextColor(muted); textSize=17f; gravity=Gravity.TOP; minLines=4; background=shape(this@MainActivity.background,18,1); setPadding(dp(14),dp(14),dp(14),dp(14)) }
        form.addView(input,lp(-1,dp(150),4))
        val actions=LinearLayout(this).apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0,dp(16),0,0) }
        actions.addView(button("Cancel",surface,muted){dialog.dismiss()},lp(dp(122),dp(54),0)); actions.addView(button(if(old==null)"Add" else "Save",accent,onAccent()){ val value=input.text.toString().trim(); if(value.isNotEmpty()){if(old==null)customCards.add(Card(selected,value))else{old.kind=selected;old.text=value};saveState();dialog.dismiss();showCustomCards()} },lp(dp(122),dp(54),8))
        form.addView(actions)
        dialog.setContentView(form); dialog.show(); dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels*.90f).toInt(),-2); attributes=attributes.apply { dimAmount=.48f }; addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
    }
    private fun manageCards(){val root=base();title(root,"Manage your cards");root.addView(label("Export, import, or delete your custom truths and dares.",17,false,muted).apply{setPadding(0,dp(20),0,dp(12))},lp(-1,-2,0).apply{leftMargin=dp(16);rightMargin=dp(16)});val destructive=Color.rgb(211,47,47);val delete=button("Delete all cards",surface,destructive){customCards.clear();saveState();showCustomCards()}.apply{background=toneOutline(destructive);setTextColor(destructive)};listOf(button("Export cards",accent,onAccent()){exportCards()},button("Import cards",accent,onAccent()){importCards()},delete).forEach{root.addView(it,lp(-1,dp(62),6).apply{leftMargin=dp(16);rightMargin=dp(16)})}}
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
    private fun lifetimeCacheSignature(expiresAt: Long): String =
        Base64.encodeToString(entitlementMac().doFinal("$packageName|lifetime|$expiresAt".toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    private fun hasValidCachedPremiumEntitlement(): Boolean = try {
        val expiresAt = prefs.getLong("premiumCacheExpiresAt", 0L)
        val signature = prefs.getString("premiumCacheSignature", "") ?: ""
        expiresAt > System.currentTimeMillis() && signature.isNotBlank() &&
            signature == premiumCacheSignature(expiresAt)
    } catch (_: Exception) { false }
    private fun hasValidCachedLifetimeEntitlement(): Boolean = try {
        val expiresAt=prefs.getLong("lifetimeCacheExpiresAt",0L)
        val signature=prefs.getString("lifetimeCacheSignature","") ?: ""
        expiresAt>System.currentTimeMillis() && signature.isNotBlank() && signature==lifetimeCacheSignature(expiresAt)
    } catch (_:Exception) { false }
    private fun cachePremiumEntitlement() {
        try {
            val expiresAt = System.currentTimeMillis() + offlinePremiumWindowMs
            prefs.edit().putLong("premiumCacheExpiresAt", expiresAt)
                .putString("premiumCacheSignature", premiumCacheSignature(expiresAt)).apply()
        } catch (_: Exception) { }
    }
    private fun cacheLifetimeEntitlement() {
        try {
            val expiresAt=System.currentTimeMillis()+offlinePremiumWindowMs
            prefs.edit().putLong("lifetimeCacheExpiresAt",expiresAt)
                .putString("lifetimeCacheSignature",lifetimeCacheSignature(expiresAt)).apply()
        } catch (_:Exception) { }
    }
    private fun clearCachedPremiumEntitlement() {
        prefs.edit().remove("premiumCacheExpiresAt").remove("premiumCacheSignature").apply()
    }
    private fun clearCachedLifetimeEntitlement() {
        prefs.edit().remove("lifetimeCacheExpiresAt").remove("lifetimeCacheSignature").apply()
    }
    private fun connectBilling(){
        billingClient.startConnection(object: BillingClientStateListener {
            override fun onBillingSetupFinished(result:BillingResult){
                if(result.responseCode==BillingClient.BillingResponseCode.OK){
                    queryProducts()
                    val announceRestore=restorePendingAfterConnection
                    restorePendingAfterConnection=false
                    queryOwned(announceRestore) { verified -> if(offerAfterPrivacy && verified) showFirstOffer() }
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
    private fun queryOwned(announce:Boolean=false, onComplete:(Boolean)->Unit={ _ -> }){
        if(announce) Toast.makeText(this,"Checking Google Play purchases…",Toast.LENGTH_SHORT).show()
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
        var allSuccessful=true
        listOf(BillingClient.ProductType.INAPP,BillingClient.ProductType.SUBS).forEach { type ->
            billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, response ->
                if(result.responseCode==BillingClient.BillingResponseCode.OK) restored.addAll(response) else allSuccessful=false
                remaining--
                if(remaining==0){
                    if(allSuccessful) handlePurchases(restored,true)
                    if(announce){
                        val found=restored.any { purchase -> purchase.purchaseState==Purchase.PurchaseState.PURCHASED && purchase.products.any { it=="premium_levels" || it=="full_app_unlock" || it=="premium_annual" } }
                        Toast.makeText(this,if(!allSuccessful) "Google Play could not be reached. Your valid offline Premium access is unchanged." else if(found) "Purchases restored. Premium Levels unlocked." else "No eligible purchases were found for this Google Play account.",Toast.LENGTH_LONG).show()
                    }
                    onComplete(allSuccessful)
                }
            }
        }
    }
    private fun handlePurchases(purchases:List<Purchase>, replaceEntitlements:Boolean=false){
        val wasPremium=premium
        val wasLifetime=lifetimePremium
        val owned=purchases.filter { it.purchaseState==Purchase.PurchaseState.PURCHASED }.flatMap { it.products }.filter { it in setOf("premium_levels","full_app_unlock","premium_annual") }.toSet()
        if(replaceEntitlements) ownedProductIds.clear()
        ownedProductIds.addAll(owned)
        lifetimePremium=when {
            "premium_levels" in ownedProductIds -> { cacheLifetimeEntitlement(); true }
            replaceEntitlements -> { clearCachedLifetimeEntitlement(); false }
            else -> lifetimePremium || hasValidCachedLifetimeEntitlement()
        }
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
        if(wasPremium!=premium || wasLifetime!=lifetimePremium) runOnUiThread {
            premiumDialog?.dismiss()
            if(currentScreen=="home") showHome()
            if(currentScreen=="about") showAbout()
        }
        purchases.filter { it.purchaseState==Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { purchase -> billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()){} }
    }
    private fun requestFirstOffer(){
        if(firstOfferShown || prefs.getBoolean("premiumWelcomeSeen",false)) return
        if(premium) { prefs.edit().putBoolean("premiumWelcomeSeen",true).apply(); offerAfterPrivacy=false; return }
        offerAfterPrivacy=true
        firstOfferFallback?.let { window.decorView.removeCallbacks(it) }
        firstOfferFallback=Runnable { if(offerAfterPrivacy && !premium) showFirstOffer() }
        window.decorView.postDelayed(firstOfferFallback!!,1200)
        if(!::billingClient.isInitialized || !billingClient.isReady) return
        queryOwned(false) { showFirstOffer() }
    }
    private fun showFirstOffer(){
        if(firstOfferShown || prefs.getBoolean("premiumWelcomeSeen",false) || !offerAfterPrivacy) { offerAfterPrivacy=false; return }
        if(premium) { prefs.edit().putBoolean("premiumWelcomeSeen",true).apply(); offerAfterPrivacy=false; return }
        firstOfferFallback?.let { window.decorView.removeCallbacks(it) };firstOfferFallback=null
        offerAfterPrivacy=false
        prefs.edit().putBoolean("premiumWelcomeSeen",true).apply()
        firstOfferShown=true
        showPremium(true)
    }
    private fun premiumSupportBanner():LinearLayout {
        val green=Color.rgb(52,229,176)
        return LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(14),dp(12),dp(14),dp(12))
            background=shape(ColorUtils.blendARGB(surface,green,if(isDark()).13f else .09f),20,1,green)
            addView(label("♥",22,true,green,Gravity.CENTER),lp(dp(38),dp(38),0).apply { rightMargin=dp(10) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL
                addView(label("Support independent development",15,true,green))
                addView(label("Help keep Truth or Dare ad-free and fund this app and future independent projects.",12,false,green).apply { setLineSpacing(dp(2).toFloat(),1f) })
            },weight(1f))
        }
    }
    private fun showPremiumPlans(){
        if(premium){ currentScreen="about"; showAbout(); return }
        queryProducts()
        val root=base(); title(root,"Premium plans")
        root.addView(premiumSupportBanner(),lp(-1,-2,0).apply { leftMargin=dp(16);rightMargin=dp(16);topMargin=dp(16);bottomMargin=dp(10) })
        root.addView(label("Unlock every level, custom cards, comfort-zone calibration, and gameplay settings.",14,false,muted).apply { setPadding(0,0,0,dp(10)) },lp(-1,-2,0).apply { leftMargin=dp(16);rightMargin=dp(16) })
        var selected="premium_annual"
        val rows=mutableListOf<TextView>()
        fun refresh(){ rows.forEach { row -> val active=row.tag==selected; row.background=if(active)accentOutline(22)else shape(surface,22,1);row.setTextColor(if(active)accent else ink) } }
        fun addPlan(id:String,title:String,detail:String){
            val row=label("$title\n${prices[id] ?: "Current price from Google Play"} · $detail",15,true,ink,Gravity.CENTER_VERTICAL).apply { tag=id;setPadding(dp(18),0,dp(18),0);isClickable=true;setOnClickListener{selected=id;refresh()} }
            rows.add(row);root.addView(row,lp(-1,dp(72),4).apply { leftMargin=dp(16);rightMargin=dp(16) });refresh()
        }
        addPlan("full_app_unlock","Monthly","renews monthly")
        addPlan("premium_annual","Yearly","best value")
        addPlan("premium_levels","Lifetime","one-time purchase")
        root.addView(label("Subscriptions renew automatically. Cancel anytime.",11,false,muted,Gravity.CENTER).apply { setPadding(0,dp(8),0,dp(8)) },lp(-1,-2,0).apply { leftMargin=dp(16);rightMargin=dp(16) })
        root.addView(button("Continue",accent,onAccent()){
            val detail=products[selected] ?: run { Toast.makeText(this,"Google Play is loading the current prices. Please try again shortly.",Toast.LENGTH_SHORT).show();return@button }
            val item=BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(detail).apply { if(selected!="premium_levels") setOfferToken(detail.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: "") }.build()
            billingClient.launchBillingFlow(this,BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item)).build())
        },lp(-1,dp(54),4).apply { leftMargin=dp(16);rightMargin=dp(16) })
        root.addView(button("Restore purchases",surface,ink){queryOwned(true)},lp(-1,dp(54),4).apply { leftMargin=dp(16);rightMargin=dp(16) })
    }
    private fun showPremium(firstLaunch:Boolean=false, planIds:List<String> = listOf("full_app_unlock","premium_annual","premium_levels")){
        if(premium) return
        val dialog=Dialog(this); premiumDialog=dialog; dialog.setOnDismissListener { if(premiumDialog===dialog) premiumDialog=null }; val scroll=ScrollView(this).apply { background=shape(surface,28,1);clipToOutline=true };styleScrollBar(scroll); val shell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(surface,28,1); setPadding(dp(16),dp(18),dp(16),dp(18)) }; scroll.addView(shell)
        val head=FrameLayout(this); head.addView(label(if(firstLaunch)"Welcome offer" else "Unlock Premium",22,true,ink,Gravity.CENTER),FrameLayout.LayoutParams(-1,dp(46),Gravity.CENTER)); head.addView(closeButton{dialog.dismiss()},FrameLayout.LayoutParams(dp(42),dp(42),Gravity.END or Gravity.CENTER_VERTICAL));shell.addView(head,lp(-1,dp(46),0))
        shell.addView(label(if(firstLaunch)"Your introductory discounts are ready." else "Get every level and setting.",14,false,muted,Gravity.CENTER).apply{setPadding(0,dp(4),0,dp(12))})
        shell.addView(premiumSupportBanner(),lp(-1,-2,6))
        shell.addView(label("✓ Unlock Hot, Naughty, and custom cards\n✓ Create your own truths and dares\n✓ Enable all gameplay settings",13,true,ink).apply{setLineSpacing(dp(5).toFloat(),1f);setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(ColorUtils.blendARGB(surface,accent,.10f),18,1)},lp(-1,-2,10))
        var selected=if("premium_annual" in planIds) "premium_annual" else planIds.first(); val rows=mutableListOf<TextView>()
        fun refresh(){ rows.forEach { row -> val active=row.tag==selected; row.setTextColor(if(active)accent else ink); row.background=if(active)accentOutline(18)else shape(surface,18,1) } }
        fun plan(id:String,title:String,detail:String){ val row=label("$title\n${prices[id] ?: "Price shown by Google Play"} · $detail",15,true,ink,Gravity.CENTER_VERTICAL).apply { tag=id; setPadding(dp(16),0,dp(16),0); isClickable=true; setOnClickListener{selected=id;refresh()} }; rows.add(row);shell.addView(row,lp(-1,dp(62),4));refresh() }
        val captions=mapOf("full_app_unlock" to ("Monthly" to "renews monthly"),"premium_annual" to ("Yearly" to "best value"),"premium_levels" to ("Lifetime" to "one-time purchase"))
        planIds.forEach { id -> captions[id]?.let { plan(id,it.first,it.second) } }
        shell.addView(label("Subscriptions renew automatically. Cancel anytime.",11,false,muted,Gravity.CENTER).apply{setPadding(0,dp(8),0,dp(8))})
        shell.addView(button("Continue",accent,onAccent()){
            val detail=products[selected]; if(detail==null){Toast.makeText(this,"Google Play is loading the current price. Please try again shortly.",Toast.LENGTH_SHORT).show();return@button}
            val item=BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(detail).apply { if(selected!="premium_levels") setOfferToken(detail.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: "") }.build()
            billingClient.launchBillingFlow(this,BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item)).build())
        },lp(-1,dp(54),4))
        shell.addView(button("Restore purchases",surface,ink){queryOwned(true)},lp(-1,dp(48),8))
        dialog.setContentView(scroll);dialog.show();dialog.window?.apply{decorView.setPadding(0,0,0,0);setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));val preferredHeight=if(firstLaunch)dp(if(planIds.size==3)806 else if(planIds.size==2)702 else 592) else -2;setLayout(resources.displayMetrics.widthPixels-dp(32),if(firstLaunch)preferredHeight.coerceAtMost(resources.displayMetrics.heightPixels-dp(32)) else preferredHeight);setGravity(Gravity.CENTER);attributes=attributes.apply{dimAmount=.45f;if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)blurBehindRadius=dp(20)};addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)}
    }
    private fun promptFileSuffix(language:String):String = when(language){
        "zh-CN"->"chinese_simplified"
        "zh-TW"->"chinese_traditional"
        else->Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ENGLISH).replace(" ","_")
    }
    private fun promptJsonForLanguage():JSONObject {
        val language=effectiveLanguage()
        val candidates=listOf(
            "truth_or_dare_prompts_${promptFileSuffix(language)}.json",
            "truth_or_dare_prompts_${language.lowercase(Locale.ENGLISH)}.json",
            "truth_or_dare_prompts_english.json"
        ).distinct()
        candidates.forEach { name ->
            runCatching { JSONObject(assets.open(name).bufferedReader().use{it.readText()}) }.getOrNull()?.let{return it}
        }
        error("No prompt file is available")
    }
    private fun loadDecks(){
        decks.clear()
        try{
            val obj=promptJsonForLanguage()
            val plans=obj.optJSONObject("plans") ?: obj
            mapOf("soft" to "casual","hot" to "flirty","hard" to "hot","extreme" to "naughty").forEach { (level,sourceLevel) ->
                val list=mutableListOf<Card>(); val plan=plans.optJSONObject(sourceLevel) ?: plans.optJSONObject(level)
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
    }    private fun loadState(){themeMode=prefs.getString("theme","dark")?:"dark";useSystemColors=prefs.getBoolean("systemColors",false);selectedLevel=prefs.getString("selectedLevel","soft")?:"soft";limitTruths=prefs.getBoolean("limit",false);truthLimit=prefs.getInt("truthLimit",2).coerceIn(1,5);reverseDares=prefs.getBoolean("reverseDares",false);reverseDareLimit=prefs.getInt("reverseDareLimit",2).coerceIn(1,5);premium=hasValidCachedPremiumEntitlement();lifetimePremium=hasValidCachedLifetimeEntitlement();ttsEnabled=prefs.getBoolean("ttsEnabled",false);val json=prefs.getString("players","[]")?:"[]";try{val a=org.json.JSONArray(json);for(i in 0 until a.length()){val x=a.getJSONObject(i);players.add(Player(x.optString("name"),x.optString("gender","").let{if(it=="Other")"" else it},x.optString("preference",""),x.optInt("truthStreak"),x.optInt("dareStreak"),x.optInt("comfort",5).coerceIn(1,10),x.optBoolean("comfortEnabled",false),MutableList(x.optJSONArray("specific")?.length()?:0){j->x.optJSONArray("specific")?.optString(j)?:""}.filter{it.isNotBlank()}.toMutableSet()))}}catch(_:Exception){};val cards=prefs.getString("cards","[]")?:"[]";try{val a=org.json.JSONArray(cards);for(i in 0 until a.length()){val x=a.getJSONObject(i);customCards.add(Card(x.optString("kind"),x.optString("text")))}}catch(_:Exception){}}
    private fun saveState(){val a=org.json.JSONArray();players.forEach{p->a.put(JSONObject().put("name",p.name).put("gender",p.gender).put("preference",p.preference).put("truthStreak",p.truthStreak).put("dareStreak",p.dareStreak).put("comfort",p.comfort).put("comfortEnabled",p.comfortEnabled).put("specific",org.json.JSONArray(p.specificPlayers.toList())))};val c=org.json.JSONArray();customCards.forEach{x->c.put(JSONObject().put("kind",x.kind).put("text",x.text))};prefs.edit().putString("theme",themeMode).putBoolean("systemColors",useSystemColors).putString("selectedLevel",selectedLevel).putBoolean("limit",limitTruths).putInt("truthLimit",truthLimit).putBoolean("reverseDares",reverseDares).putInt("reverseDareLimit",reverseDareLimit).remove("premium").putBoolean("ttsEnabled",ttsEnabled).putString("players",a.toString()).putString("cards",c.toString()).apply()}

    private inner class LevelFaceView(private val style:String, private val tone:Int):View(this@MainActivity) {
        private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL }
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            val s=min(width,height).toFloat()
            val cx=width/2f
            val cy=height/2f
            line.color=tone
            line.strokeWidth=max(2f,s*.075f)
            fill.color=tone
            val r=s*.39f
            canvas.drawCircle(cx,cy,r,line)
            fun smile(left:Float,top:Float,right:Float,bottom:Float,start:Float=18f,sweep:Float=144f){canvas.drawArc(RectF(left,top,right,bottom),start,sweep,false,line)}
            fun heart(x:Float,y:Float,size:Float){
                val p=Path();p.moveTo(x,y+size*.85f);p.cubicTo(x-size*1.05f,y+size*.20f,x-size*.72f,y-size*.72f,x,y-size*.12f);p.cubicTo(x+size*.72f,y-size*.72f,x+size*1.05f,y+size*.20f,x,y+size*.85f);p.close();canvas.drawPath(p,fill)
            }
            when(style) {
                "soft" -> {
                    val lens=RectF(cx-r*.70f,cy-r*.35f,cx-r*.08f,cy+r*.08f);canvas.drawRoundRect(lens,s*.06f,s*.06f,line)
                    val lens2=RectF(cx+r*.08f,cy-r*.35f,cx+r*.70f,cy+r*.08f);canvas.drawRoundRect(lens2,s*.06f,s*.06f,line)
                    canvas.drawLine(cx-r*.08f,cy-r*.14f,cx+r*.08f,cy-r*.14f,line)
                    smile(cx-r*.54f,cy-r*.10f,cx+r*.54f,cy+r*.70f)
                }
                "hot" -> {
                    heart(cx-r*.38f,cy-r*.19f,r*.20f);heart(cx+r*.38f,cy-r*.19f,r*.20f)
                    smile(cx-r*.58f,cy-r*.04f,cx+r*.58f,cy+r*.70f)
                }
                "hard" -> {
                    canvas.drawArc(RectF(cx-r*.68f,cy-r*.40f,cx-r*.10f,cy+r*.05f),205f,125f,false,line)
                    canvas.drawArc(RectF(cx+r*.10f,cy-r*.40f,cx+r*.68f,cy+r*.05f),210f,120f,false,line)
                    canvas.drawArc(RectF(cx-r*.50f,cy+r*.02f,cx+r*.58f,cy+r*.62f),18f,122f,false,line)
                }
                else -> {
                    val horns=Path();horns.moveTo(cx-r*.78f,cy-r*.55f);horns.lineTo(cx-r*.58f,cy-r*1.00f);horns.lineTo(cx-r*.28f,cy-r*.72f);horns.moveTo(cx+r*.78f,cy-r*.55f);horns.lineTo(cx+r*.58f,cy-r*1.00f);horns.lineTo(cx+r*.28f,cy-r*.72f);canvas.drawPath(horns,line)
                    canvas.drawArc(RectF(cx-r*.68f,cy-r*.35f,cx-r*.10f,cy+r*.08f),205f,125f,false,line)
                    canvas.drawArc(RectF(cx+r*.10f,cy-r*.35f,cx+r*.68f,cy+r*.08f),210f,120f,false,line)
                    smile(cx-r*.55f,cy-r*.02f,cx+r*.55f,cy+r*.70f)
                }
            }
        }
    }
    data class Player(var name:String,var gender:String="",var preference:String="",var truthStreak:Int=0,var dareStreak:Int=0,var comfort:Int=5,var comfortEnabled:Boolean=false,var specificPlayers:MutableSet<String> = mutableSetOf())
    data class Card(var kind:String,var text:String,var targetName:String?=null)
}























