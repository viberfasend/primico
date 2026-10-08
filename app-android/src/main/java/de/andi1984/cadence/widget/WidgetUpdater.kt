package de.andi1984.cadence.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.sync.SyncWorker

/**
 * The one place that knows every widget Primico ships. [de.andi1984.cadence.AppContainer] calls
 * this on every emission of what the widgets draw ([widgetUiStateFlow]: tasks, projects,
 * sections, tags, settings) — the same "reconcile on every emission" shape
 * [de.andi1984.cadence.reminders.AlarmReminderScheduler] follows — so a new widget only has to be
 * added here once rather than at every call site that mutates a task. [ToggleTaskCallback] and
 * [WidgetMidnightRefresh] call it for the writes and the day change that happen with no screen
 * open.
 *
 * `updateAll` is a no-op when a widget has no instance on any home screen, so this never has to
 * check what is actually pinned before calling it.
 *
 * **What `updateAll` does depends on whether the widget's session is still open.** Glance keeps a
 * composition alive for about 45 seconds after it first draws; inside that window `updateAll` is
 * an event that recomposes it, and the flow each widget collects inside `provideContent` is what
 * carries the new rows in. Past it, `updateAll` starts a new session, which runs `provideGlance`
 * again and draws its first frame from a fresh snapshot ([widgetSnapshot]). Both paths end in the
 * right rows; neither needs this caller to know which it took.
 *
 * [CadenceQuickAddWidget] is deliberately absent: it renders a button and reads no task, so a
 * task change has nothing to tell it.
 */
object WidgetUpdater {
    suspend fun refreshAll(context: Context) {
        TASK_WIDGETS.forEach { it.create().updateAll(context) }
        // Re-armed here as well as from `provideGlance`, because an update that lands on an open
        // session recomposes it without running `provideGlance` — and only while a widget exists,
        // so an alarm is never left chaining for a home screen with nothing on it. The periodic
        // pull is reconciled on the same condition plus a stored session (ADR 0002, amendment 1):
        // the widget is what makes background freshness worth a scheduled job, and a session is
        // what gives the job anything to do.
        if (hasTaskWidgets(context)) {
            WidgetMidnightRefresh.schedule(context)
            val engine = (context.applicationContext as? CadenceApplication)?.container?.syncEngine
            if (engine?.isSignedIn() == true) {
                SyncWorker.ensurePeriodic(context)
            } else {
                SyncWorker.cancelPeriodic(context)
            }
        } else {
            SyncWorker.cancelPeriodic(context)
        }
    }

    private suspend fun hasTaskWidgets(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return TASK_WIDGETS.any { it.isPlaced(manager) }
    }

    /** A widget class and how to build one — typed, so `getGlanceIds` is handed a `Class<T>`. */
    private class TaskWidget<T : GlanceAppWidget>(val type: Class<T>, val create: () -> T) {
        suspend fun isPlaced(manager: GlanceAppWidgetManager): Boolean =
            manager.getGlanceIds(type).isNotEmpty()
    }

    /**
     * Every widget that reads tasks — built fresh per call, as `updateAll` needs an instance and
     * the instance holds no state of its own. [CadenceQuickAddWidget] is the one left out.
     */
    private val TASK_WIDGETS: List<TaskWidget<*>> = listOf(
        TaskWidget(CadenceTodayWidget::class.java, ::CadenceTodayWidget),
        TaskWidget(CadenceInboxWidget::class.java, ::CadenceInboxWidget),
        TaskWidget(CadenceAgendaWidget::class.java, ::CadenceAgendaWidget),
        TaskWidget(CadenceListWidget::class.java, ::CadenceListWidget),
        TaskWidget(CadenceNextTaskWidget::class.java, ::CadenceNextTaskWidget),
        TaskWidget(CadenceProgressWidget::class.java, ::CadenceProgressWidget),
    )
}
