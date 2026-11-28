package com.zxtape.loader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.webkit.WebViewAssetLoader

/**
 * Hosts the single-file ZX Spectrum Tape Loader web app (assets/index.html) in a WebView.
 * - Served from https://appassets.androidplatform.net/ so localStorage, Web Audio and Blob URLs behave normally.
 * - Blob/data downloads (.TAP, .TZX, .WAV, .BAS, .lst ...) are saved to the public Downloads folder.
 * - <input type="file"> works through the system file picker.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splash: View
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val startTime = System.currentTimeMillis()
    private var splashHidden = false

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris: Array<Uri>? =
                if (result.resultCode == RESULT_OK && result.data != null) {
                    android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                } else null
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    // "Save as" file selector (Storage Access Framework): the user picks the folder and file name.
    // Works on Android 5.0+ and needs no storage permission.
    private class PendingExport(val name: String, val mime: String, val bytes: ByteArray)
    private val exportQueue = ArrayDeque<PendingExport>()
    private var currentExport: PendingExport? = null

    private val saveAsPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val export = currentExport
            currentExport = null
            val uri = if (result.resultCode == RESULT_OK) result.data?.data else null
            if (export != null) {
                if (uri != null) writeExport(uri, export)
                else Toast.makeText(this, "Save cancelled", Toast.LENGTH_SHORT).show()
            }
            showNextExport()
        }

    private fun queueExport(export: PendingExport) {
        exportQueue.addLast(export)
        if (currentExport == null) showNextExport()
    }

    private fun showNextExport() {
        if (currentExport != null) return
        val next = exportQueue.removeFirstOrNull() ?: return
        currentExport = next
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = next.mime
            putExtra(Intent.EXTRA_TITLE, next.name)
        }
        try {
            saveAsPicker.launch(intent)
        } catch (e: ActivityNotFoundException) {
            currentExport = null
            Toast.makeText(this, "No file manager available to save the file", Toast.LENGTH_LONG).show()
            showNextExport()
        }
    }

    private fun writeExport(uri: Uri, export: PendingExport) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(export.bytes) }
                ?: throw IllegalStateException("Could not open the selected file")
            Toast.makeText(this, "Saved: ${export.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        splash = findViewById(R.id.splash)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(APP_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }

        webView.addJavascriptInterface(DownloadBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host == APP_HOST) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
                hideSplash()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    filePicker.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    Toast.makeText(this@MainActivity, "No file picker available", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl("https://$APP_HOST/assets/index.html")
        } else {
            webView.restoreState(savedInstanceState)
            hideSplash()
        }

        // Safety net: never leave the splash up for more than 6 seconds
        Handler(Looper.getMainLooper()).postDelayed({ hideSplash() }, 6000)
    }

    private fun hideSplash() {
        if (splashHidden) return
        val remaining = MIN_SPLASH_MS - (System.currentTimeMillis() - startTime)
        Handler(Looper.getMainLooper()).postDelayed({
            if (splashHidden) return@postDelayed
            splashHidden = true
            splash.animate().alpha(0f).setDuration(350).withEndAction { splash.visibility = View.GONE }.start()
        }, remaining.coerceAtLeast(0))
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onPause() { webView.onPause(); super.onPause() }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** Receives files the web app "downloads" (Blob/data URLs) and opens the Save-as file selector. */
    inner class DownloadBridge {
        @JavascriptInterface
        fun saveBase64(fileName: String, mime: String, base64: String) {
            val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { return }
            val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download.bin" }
            val lower = safe.lowercase()
            // .tap/.tzx must stay "binary" so the file manager does not append another extension
            val type = if (lower.endsWith(".tap") || lower.endsWith(".tzx") || mime.isBlank()) "application/octet-stream" else mime
            runOnUiThread { queueExport(PendingExport(safe, type, bytes)) }
        }
    }

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val MIN_SPLASH_MS = 1400L

        // Intercepts the web app's <a download href="blob:..."> clicks and hands the bytes to Android.
        private const val DOWNLOAD_HOOK_JS = """
(function () {
  if (window.__zxDlHook) return;
  window.__zxDlHook = true;
  function send(name, blob) {
    var r = new FileReader();
    r.onloadend = function () {
      var s = String(r.result || '');
      var i = s.indexOf(',');
      AndroidBridge.saveBase64(name || 'download.bin', blob.type || 'application/octet-stream', i >= 0 ? s.slice(i + 1) : '');
    };
    r.readAsDataURL(blob);
  }
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.href || '';
    if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
    ev.preventDefault();
    ev.stopPropagation();
    fetch(href).then(function (res) { return res.blob(); })
      .then(function (blob) { send(a.getAttribute('download'), blob); })
      .catch(function (e) { console.error('Android download failed', e); });
  }, true);
})();
"""
    }
}
