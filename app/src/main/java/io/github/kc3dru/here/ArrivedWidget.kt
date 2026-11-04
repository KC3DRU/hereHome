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
 * Widget for "I have arrived" function.
 * Tapping the widget launches MainActivity directly with the widget type.
 *
 * The PendingIntent is attached to the root layout as well as the label so a
 * launcher that only honours clicks on the outer view still reaches the app,
 * and the same update runs on enable/options-change so a widget restored after
 * a reboot or resized by the user gets its click handler re-attached.
 */
class ArrivedWidget : AppWidgetProvider() {

    companion object {
        const val EXTRA_WIDGET_TYPE = "widget_type"
        const val WIDGET_TYPE = "arrived"
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
        val ids = manager.getAppWidgetIds(ComponentName(context, ArrivedWidget::class.java))
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
        // Direct activity launch — more reliable than broadcast on modern Android
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

        val views = RemoteViews(context.packageName, R.layout.widget_arrived)
        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
        views.setOnClickPendingIntent(R.id.widgetButton, pendingIntent)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
