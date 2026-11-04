package io.github.kc3dru.here

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Widget for "Heading Home" function.
 * Tapping the widget launches MainActivity directly with the widget type.
 *
 * The PendingIntent is attached to the root layout as well as the label, and
 * the same update runs on enable/options-change, so restored or resized
 * instances keep a working click handler. See [ArrivedWidget].
 */
class HeadingHomeWidget : AppWidgetProvider() {

    companion object {
        const val EXTRA_WIDGET_TYPE = "widget_type"
        const val WIDGET_TYPE = "heading_home"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    /** First instance placed — refresh every instance of this widget. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, HeadingHomeWidget::class.java))
        for (appWidgetId in ids) {
            updateWidget(context, manager, appWidgetId)
        }
    }

    /** Resized or restored — re-attach the click handler. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_WIDGET_TYPE, WIDGET_TYPE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val views = RemoteViews(context.packageName, R.layout.widget_heading_home)
        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
        views.setOnClickPendingIntent(R.id.widgetButton, pendingIntent)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
