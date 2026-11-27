package com.zxtape.loader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

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

    // Android 6-9 (API 23-28) needs a runtime storage permission to write into the public Downloads folder
    private var pendingSave: Triple<String, String, ByteArray>? = null
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingSave
            pendingSave = null
            if (pending != null) {
                if (granted) writeAndToast(pending.first, pending.second, pending.third)
                else Toast.makeText(this, "Storage permission denied \u2014 file not saved", Toast.LENGTH_LONG).show()
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

    /** Receives files the web app "downloads" (Blob/data URLs) and writes them to Downloads. */
    inner class DownloadBridge {
        @JavascriptInterface
        fun saveBase64(fileName: String, mime: String, base64: String) {
            val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { return }
            val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download.bin" }
            val type = mime.ifBlank { "application/octet-stream" }
            runOnUiThread {
                val needsPermission = Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsPermission) {
                    pendingSave = Triple(safe, type, bytes)
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    writeAndToast(safe, type, bytes)
                }
            }
        }
    }

    private fun writeAndToast(name: String, mime: String, bytes: ByteArray) {
        try {
            val where = saveToDownloads(name, mime, bytes)
            Toast.makeText(this, "Saved: $where", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            return "Downloads/$name"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            var file = File(dir, name)
            var n = 1
            while (file.exists()) {
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                file = File(dir, "$base ($n)$ext"); n++
            }
            FileOutputStream(file).use { it.write(bytes) }
            return "Downloads/${file.name}"
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
