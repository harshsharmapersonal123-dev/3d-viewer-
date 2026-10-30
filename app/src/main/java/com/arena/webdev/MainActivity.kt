package com.arena.webdev

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) { false }
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(
                    """
                    (function(){
                      if(window.__arenaDownloadHook) return;
                      window.__arenaDownloadHook=true;
                      document.addEventListener('click', function(e){
                        var a=e.target && e.target.closest ? e.target.closest('a[download]') : null;
                        if(!a) return;
                        var href=a.href || '';
                        if(href.startsWith('blob:') || href.startsWith('data:')){
                          e.preventDefault();
                          fetch(href).then(r=>r.blob()).then(blob=>{
                            var reader=new FileReader();
                            reader.onloadend=function(){
                              var data=String(reader.result).split(',')[1] || '';
                              ArenaDownload.save(a.download || 'arena-export.bin', blob.type || 'application/octet-stream', data);
                            };
                            reader.readAsDataURL(blob);
                          }).catch(err=>console.error(err));
                        }
                      }, true);
                    })();
                    """.trimIndent(), null
                )
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                val intent = params.createIntent().apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    startActivityForResult(intent, fileChooserRequest)
                    true
                } catch (_: Exception) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        webView.addJavascriptInterface(FileBridge(), "ArenaDownload")
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class FileBridge {
        @JavascriptInterface
        fun save(name: String, mime: String, base64: String) {
            runOnUiThread {
                try {
                    val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "arena-export.bin" }
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val out = File(getExternalFilesDir(null), safeName)
                    FileOutputStream(out).use { it.write(bytes) }
                    Toast.makeText(this@MainActivity, "Saved: ${out.name}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == fileChooserRequest) {
            val result = if (resultCode == Activity.RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else null
            fileChooserCallback?.onReceiveValue(result)
            fileChooserCallback = null
        }
    }

    @Deprecated("Deprecated in Android API, retained for compatibility")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
