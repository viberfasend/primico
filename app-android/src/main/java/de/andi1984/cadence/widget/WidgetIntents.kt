package de.andi1984.cadence.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import de.andi1984.cadence.MainActivity
import de.andi1984.cadence.reminders.AlarmReminderScheduler
import de.andi1984.cadence.ui.Routes

/**
 * Every way a widget can open the app, in one place — and, more to the point, the one place that
 * gets the `data` URI right.
 *
 * **Two intents that differ only in their extras are the same intent.** [Intent.filterEquals] —
 * which is what `PendingIntent` matches on — compares action, data, type, package, component and
 * categories, and deliberately ignores extras. Glance builds every `actionStartActivity` into a
 * `PendingIntent` with `FLAG_UPDATE_CURRENT`, so a list of rows whose intents carry nothing but a
 * different `taskId` extra all collapse onto *one* PendingIntent, and the last row composed
 * overwrites the extras of every row above it. The list looks fine and every row opens the same
 * task: interactive code that reads as read-only.
 *
 * A distinct URI per destination is what keeps them apart. [AlarmReminderScheduler] already had
 * to learn this for its per-task alarms (`cadence://task/$id`), and these reuse that scheme so the
 * two agree on what a task's URI looks like.
 *
 * The extras still carry the payload — the URI only has to be *distinct*, and `MainActivity`
 * keeps reading the extras it always read.
 */
object WidgetIntents {

    /** Set when the app should come up with the quick-add sheet already open. */
    const val EXTRA_QUICK_ADD = "de.andi1984.cadence.widget.QUICK_ADD"

    /** The task an intent is about. The reminder notification's key, reused so the two agree. */
    const val EXTRA_TASK_ID = AlarmReminderScheduler.EXTRA_TASK_ID

    /** The screen the app should come up on — read back through [startRoute], never raw. */
    const val EXTRA_ROUTE = "de.andi1984.cadence.widget.ROUTE"

    /** The project quick-add should file into, the way a project screen's own add button does. */
    const val EXTRA_QUICK_ADD_PROJECT = "de.andi1984.cadence.widget.QUICK_ADD_PROJECT"

    /** Text the quick-add line starts with — a tag widget's `@handle `. */
    const val EXTRA_QUICK_ADD_TEXT = "de.andi1984.cadence.widget.QUICK_ADD_TEXT"

    /**
     * Ticks a task off from a scrolling list's row without opening the app — see
     * [WidgetToggleActivity] for why a collection item's tap has to be an activity at all.
     */
    fun toggleTask(context: Context, taskId: String): Intent =
        Intent(context, WidgetToggleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            // Distinct per task, for the same reason every intent below carries a URI.
            data = Uri.parse("cadence://widget/toggle/$taskId")
            // NEW_TASK only: paired with `taskAffinity=""` in the manifest this lands in a task
            // of its own, so ticking a row off neither disturbs nor resumes the app's own stack.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_TASK_ID, taskId)
        }

    /**
     * Opens the app on [route] — the screen a widget mirrors. A header that opened "the app"
     * landed on Today whatever it was the header of, so the Inbox widget's own title took the
     * reader somewhere else. [startRoute] is the reading side, and its allowlist is the set of
     * routes a widget may name.
     */
    fun openRoute(context: Context, route: String): Intent =
        base(context, "cadence://widget/route/${Uri.encode(route)}")
            .putExtra(EXTRA_ROUTE, route)

    /** Opens a task's detail screen — the same route a reminder notification opens. */
    fun openTask(context: Context, taskId: String): Intent =
        base(context, "cadence://task/$taskId")
            .putExtra(EXTRA_TASK_ID, taskId)

    /**
     * Opens the app with the quick-add sheet up, ready for a title — filed into [projectId] and
     * starting from [text] when a widget scoped to a project or a tag asks for it.
     */
    fun openQuickAdd(context: Context, projectId: String? = null, text: String? = null): Intent {
        // Every preset is its own URI, for the reason the class comment gives: two project
        // widgets' "+" buttons differing only in an extra would both file into whichever project
        // was composed last.
        val preset = Uri.Builder().apply {
            if (projectId != null) appendQueryParameter("project", projectId)
            if (text != null) appendQueryParameter("text", text)
        }.build().encodedQuery
        return base(context, "cadence://widget/quick-add" + (preset?.let { "?$it" } ?: ""))
            .putExtra(EXTRA_QUICK_ADD, true)
            .apply {
                if (projectId != null) putExtra(EXTRA_QUICK_ADD_PROJECT, projectId)
                if (text != null) putExtra(EXTRA_QUICK_ADD_TEXT, text)
            }
    }

    /**
     * Opens [WidgetListConfigActivity] for an already placed list widget — the "choose another
     * list" door for a widget whose project or tag was deleted, and for a launcher that placed it
     * without running the configuration step at all.
     */
    fun configureList(context: Context, appWidgetId: Int): Intent =
        Intent(context, WidgetListConfigActivity::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            data = Uri.parse("cadence://widget/configure/$appWidgetId")
            // NEW_TASK only, landing in the activity's own `taskAffinity=""` task — the same
            // arrangement as [toggleTask], so the picker neither resumes nor reorders the app.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }

    /**
     * The route [intent] asks the app to start on, or `null` for the default.
     *
     * Allowlisted rather than trusted: `MainActivity` is exported, so any app can hand it this
     * extra, and a `NavHost` given a start destination it has no composable for throws during
     * composition. Only the shapes [openRoute] is ever called with get through — the top-level
     * lists, and a project or tag by id.
     */
    fun startRoute(intent: Intent?): String? {
        val route = intent?.getStringExtra(EXTRA_ROUTE) ?: return null
        if (route in TOP_LEVEL_ROUTES) return route
        val id = route.substringAfter('/', missingDelimiterValue = "")
        val idOk = id.isNotBlank() && '/' !in id
        return when {
            idOk && route == Routes.project(id) -> route
            idOk && route == Routes.tag(id) -> route
            else -> null
        }
    }

    private val TOP_LEVEL_ROUTES = setOf(Routes.TODAY, Routes.UPCOMING, Routes.INBOX)

    /**
     * `CLEAR_TOP` without `SINGLE_TOP`, and `MainActivity` deliberately left on the default
     * `standard` launch mode: the activity is then torn down and recreated, so `onCreate` runs
     * and reads this intent. Handing a warm activity a new intent instead would need
     * `onNewIntent` *and* a way to redirect a `NavHost` whose start destination is already
     * fixed — which is the crash the reminder deep link was fixed for once already.
     */
    private fun base(context: Context, uri: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(uri)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
}
