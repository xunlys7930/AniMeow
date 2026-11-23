package com.animeow.app.data.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.animeow.app.AniMeowApplication
import com.animeow.app.MainActivity
import com.animeow.app.R
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.data.reminder.AnimeReminderNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class TrackerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try { updateAll(context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w("TrackerWidget", "Unable to refresh widget", error) }
            finally { pending.finish() }
        }
    }
    companion object {
        const val ACTION_OPEN_LIBRARY = "com.animeow.app.OPEN_TRACKER_WIDGET"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TrackerWidget::class.java))
        fun requestPin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, TrackerWidget::class.java), null, null)
        }
        suspend fun updateAll(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val app = context.applicationContext as AniMeowApplication
            val animes = app.libraryRepository.getActiveAnimesSnapshot().filter { it.status == "在看" && (it.totalEpisodes <= 0 || it.watchedEpisodes < it.totalEpisodes) }
                .sortedByDescending { it.id }.take(2)
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { widgetId ->
                val view = RemoteViews(context.packageName, R.layout.widget_tracker)
                val openLibrary = PendingIntent.getActivity(context, widgetId, Intent(context, MainActivity::class.java)
                    .setAction(ACTION_OPEN_LIBRARY).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                view.setOnClickPendingIntent(R.id.widget_heading, openLibrary)
                view.setOnClickPendingIntent(R.id.widget_footer, openLibrary)
                view.setOnClickPendingIntent(R.id.widget_empty, openLibrary)
                view.setViewVisibility(R.id.widget_empty, if (animes.isEmpty()) View.VISIBLE else View.GONE)
                val rows = listOf(intArrayOf(R.id.widget_row_one, R.id.widget_open_one, R.id.widget_title_one, R.id.widget_progress_one, R.id.widget_add_one),
                    intArrayOf(R.id.widget_row_two, R.id.widget_open_two, R.id.widget_title_two, R.id.widget_progress_two, R.id.widget_add_two))
                rows.forEachIndexed { index, row ->
                    val anime = animes.getOrNull(index)
                    view.setViewVisibility(row[0], if (anime == null) View.GONE else View.VISIBLE)
                    if (anime != null) {
                        view.setTextViewText(row[2], anime.title)
                        view.setTextViewText(row[3], "${anime.watchedEpisodes} / ${anime.totalEpisodes.takeIf { it > 0 } ?: "?"} 集")
                        val open = Intent(context, MainActivity::class.java).setAction(AnimeReminderNotifier.ACTION_OPEN_ANIME)
                            .putExtra(AnimeReminderNotifier.EXTRA_ANIME_ID, anime.id).setData(Uri.parse("animeow://widget/detail/${anime.id}"))
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        view.setOnClickPendingIntent(row[1], PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                        val progress = Intent(context, WidgetProgressReceiver::class.java)
                            .setData(Uri.parse("animeow://widget/$widgetId/${anime.id}/${anime.watchedEpisodes}"))
                            .putExtra("anime_id", anime.id).putExtra("expected_progress", anime.watchedEpisodes)
                        view.setOnClickPendingIntent(row[4], PendingIntent.getBroadcast(context, 0, progress, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                        view.setContentDescription(row[4], "《${anime.title}》看过一集")
                    }
                }
                manager.updateAppWidget(widgetId, view)
            }
        }
    }
}

/** Not exported: only immutable PendingIntents issued by this app can update progress. */
class WidgetProgressReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val animeId = intent.getLongExtra("anime_id", 0)
        val expected = intent.getIntExtra("expected_progress", -1)
        if (animeId <= 0 || expected < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as AniMeowApplication
                val settings = AppearancePreferences(context).snapshot()
                app.libraryRepository.incrementProgress(animeId, settings.autoCompleteStatus, settings.completionStatus, expectedProgress = expected)
                TrackerWidget.updateAll(context)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w("TrackerWidget", "Unable to record progress", error) }
            finally { pending.finish() }
        }
    }
}
