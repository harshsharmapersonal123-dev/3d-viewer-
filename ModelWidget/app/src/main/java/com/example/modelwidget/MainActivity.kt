package com.example.modelwidget

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * Android host for the same multi-format Three.js viewer used by the original
 * 3D Viewer app. The HTML viewer is copied into this module at build time,
 * so model loading and rendering do not depend on a CDN or an internet link.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserRequest = 4102
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(android.graphics.Color.rgb(12, 16, 24))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
            }
            addJavascriptInterface(ModelWidgetBridge(), "ModelWidgetBridge")
            addJavascriptInterface(DownloadBridge(), "ArenaDownload")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        return try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    installModelReadyHook(view)
                    handleWidgetRequest(intent)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.e("ModelWidget-Web", "${message.message()} (line ${message.lineNumber()})")
                    }
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = callback
                    val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    }
                    return try {
                        startActivityForResult(picker, fileChooserRequest)
                        true
                    } catch (e: Exception) {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        Toast.makeText(this@MainActivity, "Could not open file picker: ${e.message}", Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }
        }

        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun installModelReadyHook(view: WebView) {
        val script = """
            (function () {
              if (window.__modelWidgetReadyHook) return;
              window.__modelWidgetReadyHook = true;
              window.__modelWidgetReadySent = false;
              function checkReady() {
                const text = document.body ? document.body.innerText : "";
                const ready = text.includes("Model loaded.") ||
                              text.includes("PMX/PMD loaded in Model Only mode.");
                if (ready && !window.__modelWidgetReadySent) {
                  window.__modelWidgetReadySent = true;
                  const heading = document.querySelector("h1");
                  const name = heading ? heading.textContent : "3D model";
                  if (window.ModelWidgetBridge) ModelWidgetBridge.onModelReady(name);
                }
              }
              document.addEventListener("change", function (event) {
                if (event.target && event.target.type === "file") {
                  window.__modelWidgetReadySent = false;
                }
              }, true);
              new MutationObserver(checkReady).observe(document.body, {
                subtree: true, childList: true, characterData: true
              });
              checkReady();
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun handleWidgetRequest(source: Intent?) {
        if (source?.getBooleanExtra("render_widget", false) != true) return
        val action = source.getStringExtra("widget_action")
        web.postDelayed({
            if (!isFinishing) {
                if (action != null) applyWidgetCameraAction()
                renderPreview()
            }
        }, 1200)
        source.removeExtra("render_widget")
        source.removeExtra("widget_action")
    }

    private fun applyWidgetCameraAction() {
        val angle = ((prefs.getFloat("angle", 0f) % 360f) + 360f) % 360f
        val target = when {
            angle < 45f || angle >= 315f -> "Front"
            angle < 135f -> "Right"
            angle < 225f -> "Back"
            else -> "Left"
        }
        val zoom = prefs.getFloat("zoom", 1f)
        val delta = if (zoom < 1f) -100 else 100
        val script = """
            (function () {
              const buttons = Array.from(document.querySelectorAll("button"));
              const viewsTab = buttons.find(b => b.textContent.trim() === "VIEWS");
              if (viewsTab) viewsTab.click();
              setTimeout(function () {
                const viewButton = Array.from(document.querySelectorAll("button"))
                  .find(b => b.textContent.trim() === "$target");
                if (viewButton) viewButton.click();
                const canvas = document.querySelector("canvas");
                const count = Math.min(8, Math.round(Math.abs($zoom - 1) / 0.15));
                if (canvas) for (let i = 0; i < count; i++) {
                  canvas.dispatchEvent(new WheelEvent("wheel", {
                    deltaY: $delta, bubbles: true, cancelable: true
                  }));
                }
              }, 180);
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    inner class ModelWidgetBridge {
        @JavascriptInterface
        fun onModelReady(name: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Loaded: $name", Toast.LENGTH_SHORT).show()
                web.postDelayed({ renderPreview() }, 900)
            }
        }
    }

    inner class DownloadBridge {
        @JavascriptInterface
        fun save(name: String, mime: String, base64: String) {
            runOnUiThread {
                try {
                    val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                        .ifBlank { "viewer-export.bin" }
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val output = File(getExternalFilesDir(null), safeName)
                    FileOutputStream(output).use { it.write(bytes) }
                    Toast.makeText(this@MainActivity, "Saved: ${output.name}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderPreview() {
        if (!::web.isInitialized || web.width <= 0 || web.height <= 0) return
        try {
            val bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(bitmap))
            val output = File(filesDir, "widget_preview.png")
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            prefs.edit().putString("preview", output.absolutePath).apply()
            ModelWidgetProvider.refresh(this)
            bitmap.recycle()
        } catch (e: Exception) {
            Log.w("ModelWidget", "Could not update widget preview", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetRequest(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != fileChooserRequest) return

        val result = if (resultCode == RESULT_OK && data != null) {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                ?: data.data?.let { arrayOf(it) }
        } else null

        result?.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Some document providers grant temporary rather than persistent access.
            }
        }
        fileChooserCallback?.onReceiveValue(result)
        fileChooserCallback = null
    }

    @Deprecated("Deprecated Android callback retained for WebView file chooser compatibility")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (::web.isInitialized) {
            web.stopLoading()
            web.removeJavascriptInterface("ModelWidgetBridge")
            web.removeJavascriptInterface("ArenaDownload")
            web.loadUrl("about:blank")
            web.removeAllViews()
            web.destroy()
        }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val PREFS = "model_widget_settings"
    }
}
