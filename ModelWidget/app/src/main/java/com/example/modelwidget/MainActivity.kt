package com.example.modelwidget

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Base64

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF11151D.toInt())
        }
        status = TextView(this).apply {
            text = "ModelWidget · choose a 3D model"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(-1)
            setPadding(8, 12, 8, 12)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Choose GLB / self-contained GLTF"
            setOnClickListener { chooseModel() }
        }, LinearLayout.LayoutParams(-1, -2))
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Add ModelWidget from your home-screen widget picker. Preview controls update the saved model view."
            textSize = 13f
            setTextColor(0xFFB9C2D0.toInt())
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        val saved = File(filesDir, "selected_model")
        if (saved.exists()) {
            val ext = prefs.getString("model_name", "model.glb")!!.substringAfterLast('.', "glb")
            loadModel(saved, ext)
        } else status.text = "Choose a GLB or self-contained GLTF file."
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("render_widget", false)) {
            val angle = prefs.getFloat("angle", 0f)
            val zoom = prefs.getFloat("zoom", 1f)
            web.evaluateJavascript("if(window.setOrbit)window.setOrbit($angle,$zoom)", null)
            web.postDelayed({ renderPreview() }, 1000)
        }
    }

    private fun chooseModel() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("model/gltf-binary", "model/gltf+json", "application/octet-stream"))
        }
        startActivityForResult(pick, 40)
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 40 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.glb"
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext != "glb" && ext != "gltf") {
                status.text = "Please select a .glb or .gltf file."
                return
            }
            val file = File(filesDir, "selected_model")
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            prefs.edit().putString("model_name", name).apply()
            loadModel(file, ext)
        } catch (e: Exception) {
            status.text = "Could not load model: " + e.message
        }
    }

    private fun loadModel(file: File, extension: String) {
        try {
            val encoded = Base64.getEncoder().encodeToString(file.readBytes())
            val mime = if (extension == "gltf") "model/gltf+json" else "model/gltf-binary"
            val html = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <script type="module" src="https://unpkg.com/@google/model-viewer/dist/model-viewer.min.js"></script>
                <style>html,body{margin:0;width:100%;height:100%;background:#171d29;overflow:hidden}model-viewer{width:100%;height:100%;background:#171d29}</style></head>
                <body><model-viewer id="mv" camera-controls shadow-intensity="1" exposure="1"></model-viewer>
                <script>const mv=document.getElementById('mv');mv.src='data:$mime;base64,$encoded';
                window.setOrbit=(a,z)=>{mv.cameraOrbit=a+'deg 75deg '+(2.5*z)+'m';mv.jumpCameraToGoal()};</script></body></html>
            """.trimIndent()
            web.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
            status.text = "Loaded " + (prefs.getString("model_name", "3D model") ?: "3D model") + ". Model stays on this device."
            web.postDelayed({ renderPreview() }, 3500)
        } catch (e: Exception) {
            status.text = "Model could not be displayed: " + e.message
        }
    }

    private fun renderPreview() {
        if (!::web.isInitialized || web.width <= 0 || web.height <= 0) return
        try {
            val bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
            web.draw(android.graphics.Canvas(bitmap))
            val file = File(filesDir, "widget_preview.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            prefs.edit().putString("preview", file.absolutePath).apply()
            ModelWidgetProvider.refresh(this)
        } catch (_: Exception) { }
    }

    companion object { const val PREFS = "model_widget_settings" }
}
