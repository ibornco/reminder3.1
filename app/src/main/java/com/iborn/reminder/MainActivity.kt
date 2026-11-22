package com.iborn.reminder

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var updater: UpdateBridge

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 13, 12)
        window.navigationBarColor = Color.rgb(8, 13, 12)
        window.decorView.systemUiVisibility = 0

        updater = UpdateBridge(this) { script ->
            runOnUiThread {
                if (::webView.isInitialized) webView.evaluateJavascript(script, null)
            }
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(8, 13, 12))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.loadsImagesAutomatically = true
            settings.defaultTextEncodingName = "UTF-8"
            settings.textZoom = 100
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(updater, "AndroidUpdater")
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidUpdater")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        if (::updater.isInitialized) updater.dispose()
        super.onDestroy()
    }
}
