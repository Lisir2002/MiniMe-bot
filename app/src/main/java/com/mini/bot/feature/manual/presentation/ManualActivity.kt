package com.mini.bot.feature.manual.presentation

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * 离线使用手册阅读器：直接加载 APK 内置的 manual/index.html。
 *
 * 手册由 scripts/manual/build_manual.py 生成，全部页面、样式、脚本与搜索索引都随包内置，
 * 不依赖网络。站外链接交给系统浏览器打开。
 */
class ManualActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(object {
                @JavascriptInterface
                fun closeManual() {
                    runOnUiThread { finish() }
                }
            }, "AndroidManual")
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = false
                loadsImagesAutomatically = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(true)
                builtInZoomControls = false
                displayZoomControls = false
                setSupportMultipleWindows(false)
                textZoom = (resources.configuration.fontScale * 100f).toInt()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    return when (url.scheme) {
                        "file" -> false
                        "http", "https" -> {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, url))
                            }
                            true
                        }
                        else -> true
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    applyTheme()
                }
            }
            loadUrl(manualEntry(intent))
        }

        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        webView.settings.textZoom =
            (newConfig.fontScale * 100f).toInt()
        applyTheme()
    }

    /** 手册跟随应用深浅色：WebView 无法可靠读取系统的 prefers-color-scheme，主动写入主题标记。 */
    private fun applyTheme() {
        val dark = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val theme = if (dark) "dark" else "light"
        webView.evaluateJavascript("document.documentElement.dataset.theme='$theme';", null)
    }

    private fun manualEntry(intent: Intent?): String {
        val page = intent?.getStringExtra(EXTRA_PAGE)
            ?.takeIf { it.isNotBlank() && !it.contains("..") }
            ?: "index.html"
        return "file:///android_asset/manual/$page"
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** 打开指定内置页面，例如 terms.html。 */
        const val EXTRA_PAGE = "manual_page"
        const val PAGE_TERMS = "terms.html"
        const val PAGE_PRIVACY = "privacy.html"
    }
}
