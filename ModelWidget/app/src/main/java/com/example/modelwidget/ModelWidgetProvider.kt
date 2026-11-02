package com.example.modelwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import java.io.File

class ModelWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateOne(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val angle = prefs.getFloat("angle", 0f)
        val zoom = prefs.getFloat("zoom", 1f)
        when (intent.action) {
            LEFT -> prefs.edit().putFloat("angle", angle - 45f).apply()
            RIGHT -> prefs.edit().putFloat("angle", angle + 45f).apply()
            IN -> prefs.edit().putFloat("zoom", (zoom - 0.15f).coerceAtLeast(0.45f)).apply()
            OUT -> prefs.edit().putFloat("zoom", (zoom + 0.15f).coerceAtMost(2.5f)).apply()
            else -> return
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("render_widget", true)
            .putExtra("widget_action", intent.action)
        context.startActivity(open)
    }

    companion object {
        const val LEFT = "com.example.modelwidget.LEFT"
        const val RIGHT = "com.example.modelwidget.RIGHT"
        const val IN = "com.example.modelwidget.IN"
        const val OUT = "com.example.modelwidget.OUT"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ModelWidgetProvider::class.java))
            ids.forEach { updateOne(context, manager, it) }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget)
            val path = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString("preview", null)
            if (path != null && File(path).exists()) {
                BitmapFactory.decodeFile(path)?.let { views.setImageViewBitmap(R.id.model_preview, it) }
            }
            bind(context, views, R.id.rotate_left, LEFT, id)
            bind(context, views, R.id.rotate_right, RIGHT, id)
            bind(context, views, R.id.zoom_in, IN, id)
            bind(context, views, R.id.zoom_out, OUT, id)
            val open = PendingIntent.getActivity(context, id + 1000, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.model_preview, open)
            manager.updateAppWidget(id, views)
        }

        private fun bind(context: Context, views: RemoteViews, viewId: Int, action: String, id: Int) {
            val intent = Intent(context, ModelWidgetProvider::class.java).setAction(action)
            val pending = PendingIntent.getBroadcast(context, id * 10 + viewId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(viewId, pending)
        }
    }
}
