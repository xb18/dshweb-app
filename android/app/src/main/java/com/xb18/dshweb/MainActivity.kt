package com.xb18.dshweb

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * DSH Web 的全屏 WebView 壳：原生只负责加载网页、暴露稳定 JS API 与处理安全区。
 * 页面逻辑全部在 Web 侧，改 UI 不需要重发 APK；JS API 命名与平台解耦，
 * 后续 iOS 壳可按同一契约实现 window.nativeapp。
 */
class MainActivity : Activity(), Updater.Callback {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var updater: Updater
    private var lastBackPress = 0L
    private var loadFailed = false

    private var insets = mapOf(
        "top" to 0, "bottom" to 0, "left" to 0, "right" to 0,
        "statusBarHeight" to 0, "navigationBarHeight" to 0,
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureFullscreen()

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 4).toInt(),
            ).apply { gravity = Gravity.TOP }
            isIndeterminate = false
            max = 100
        }
        errorView = layoutInflater.inflate(R.layout.view_error, null).apply { visibility = View.GONE }
        errorText = errorView.findViewById(R.id.error_text)

        // 原生兜底：WebView 顶部预留状态栏高度，即使页面未读取 insets 也不会被遮挡。
        // 页面若自行处理安全区（读取 window.nativeapp.shell.getInsets），可调用 setContentInsetTop(0) 关掉。
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { setMargins(0, statusBarHeightPx(), 0, 0) }
        }
        configureCookies()
        updater = Updater(this, this)

        val container = FrameLayout(this).apply {
            addView(webView)
            addView(errorView)
            addView(progress)
        }
        setContentView(container)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString DshWebShell/${BuildConfig.VERSION_NAME}"
        }

        webView.addJavascriptInterface(ShellApi(), "DshWebNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (_: Exception) { /* 无可用应用则忽略 */ }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (!loadFailed) errorView.visibility = View.GONE
                injectBridge()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true) return
                loadFailed = true
                progress.visibility = View.GONE
                errorText.text = getString(R.string.load_error, error?.description ?: "")
                errorView.visibility = View.VISIBLE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        errorView.findViewById<Button>(R.id.retry_button).setOnClickListener {
            loadFailed = false
            errorView.visibility = View.GONE
            webView.reload()
        }

        window.decorView.setOnApplyWindowInsetsListener { v, windowInsets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val b = windowInsets.getInsets(WindowInsets.Type.systemBars())
                insets = mapOf(
                    "top" to b.top, "bottom" to b.bottom, "left" to b.left, "right" to b.right,
                    "statusBarHeight" to statusBarHeightPx(), "navigationBarHeight" to b.bottom,
                )
                notifyInsets()
                injectBridge()
            }
            v.onApplyWindowInsets(windowInsets)
        }

        if (savedInstanceState == null) webView.loadUrl(BuildConfig.WEB_URL)
        else webView.restoreState(savedInstanceState)
    }

    /** 全屏：内容延伸到状态栏下，顶部由 WebView margin 兜底保护。 */
    private fun configureFullscreen() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun configureCookies() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    /**
     * 合并式注入 window.nativeapp：不整体替换对象，避免覆盖页面已有 API。
     * JS 侧只依赖 window.nativeapp 契约，与原生实现对象名（DshWebNative）解耦，
     * 后续 iOS 按同一契约实现即可复用页面代码。
     */
    private fun injectBridge() {
        val bridge = """
            (function(){
              if (window.nativeapp && window.nativeapp.__ready) return;
              var n = window.DshWebNative;
              if (!n) return;
              var m = window.nativeapp = window.nativeapp || {};
              var insetListeners = [];
              window.__nativeappOnInsets = function(json){
                insetListeners.forEach(function(f){ try{ f(JSON.parse(json)); }catch(e){} });
              };

              m.shell = {
                getInsets: function(){ return JSON.parse(n.getInsets()); },
                getInfo: function(){ return JSON.parse(n.getInfo()); },
                setStatusBarStyle: function(opts){ n.setStatusBarStyle(JSON.stringify(opts || {})); },
                setFullscreen: function(on){ n.setFullscreen(!!on); },
                openExternal: function(url){ n.openExternal(String(url || '')); },
                // 单位为物理 px（与 getInsets 一致）；页面自行处理顶部安全区时传 0 关掉原生兜底
                setContentInsetTop: function(px){ n.setContentInsetTop(Number(px) || 0); },
                onInsetsChange: function(cb){
                  if (typeof cb !== 'function') return function(){};
                  insetListeners.push(cb);
                  return function(){ var i = insetListeners.indexOf(cb); if (i >= 0) insetListeners.splice(i, 1); };
                }
              };

              m.update = {
                getAppVersion: function(){ return n.getAppVersion(); },
                check: function(source){ n.checkUpdate(String(source || '')); },
                getSource: function(){ return n.getUpdateSource(); },
                setSource: function(url){ n.setUpdateSource(String(url || '')); },
                resetSource: function(){ n.resetUpdateSource(); },
                download: function(url){ n.downloadUpdate(String(url || '')); },
                install: function(){ n.installUpdate(); },
                canInstall: function(){ return n.canInstall(); },
                requestInstallPermission: function(){ n.requestInstallPermission(); },
                onEvent: function(cb){
                  window.__nativeappUpdateEvent = function(payload){ try{ cb(JSON.parse(payload)); }catch(e){} };
                }
              };

              Object.defineProperty(m, '__ready', { value: true, configurable: true });
              document.dispatchEvent(new CustomEvent('NativeAppReady'));
            })();
        """.trimIndent()
        webView.evaluateJavascript(bridge, null)
    }

    private fun notifyInsets() {
        webView.evaluateJavascript(
            "window.__nativeappOnInsets && window.__nativeappOnInsets(${JSONObject(insets).toString()});", null,
        )
    }

    /** 暴露给网页的稳定原生 API。 */
    inner class ShellApi {
        @JavascriptInterface
        fun getInsets(): String = JSONObject(insets).toString()

        @JavascriptInterface
        fun getInfo(): String = JSONObject().apply {
            put("version", BuildConfig.VERSION_NAME)
            put("platform", "android")
            put("webUrl", BuildConfig.WEB_URL)
        }.toString()

        @JavascriptInterface
        fun setStatusBarStyle(opts: String) {
            val o = try { JSONObject(opts) } catch (_: Exception) { JSONObject() }
            val light = o.optBoolean("light", true)
            runOnUiThread {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
                if (o.has("color")) {
                    try { window.statusBarColor = Color.parseColor(o.getString("color")) }
                    catch (_: Exception) { /* 非法颜色忽略 */ }
                }
            }
        }

        @JavascriptInterface
        fun setFullscreen(on: Boolean) {
            runOnUiThread {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (on) {
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                } else 0
            }
        }

        @JavascriptInterface
        fun setContentInsetTop(px: Int) {
            runOnUiThread {
                (webView.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                    topMargin = px.coerceAtLeast(0)
                    webView.layoutParams = this
                }
            }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { /* 无可用应用则忽略 */ }
        }

        @JavascriptInterface
        fun getAppVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun checkUpdate(source: String) = updater.checkUpdate(source)

        @JavascriptInterface
        fun getUpdateSource(): String = updater.getUpdateSource()

        @JavascriptInterface
        fun setUpdateSource(url: String) = updater.setUpdateSource(url)

        @JavascriptInterface
        fun resetUpdateSource() = updater.resetUpdateSource()

        @JavascriptInterface
        fun downloadUpdate(url: String) = updater.downloadUpdate(url)

        @JavascriptInterface
        fun installUpdate() = updater.installUpdate()

        @JavascriptInterface
        fun canInstall(): Boolean = updater.canInstall()

        @JavascriptInterface
        fun requestInstallPermission() = updater.requestInstallPermission()
    }

    override fun onEvent(type: String, data: JSONObject) {
        val payload = JSONObject().put("type", type).put("data", data).toString()
        runOnUiThread {
            webView.evaluateJavascript(
                "window.__nativeappUpdateEvent && window.__nativeappUpdateEvent(${JSONObject.quote(payload)});", null,
            )
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) { webView.goBack(); return }
        val now = System.currentTimeMillis()
        if (now - lastBackPress > 2000) {
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
            lastBackPress = now
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
