package de.andi1984.cadence.widget

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.BandHeading
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.DayProgress
import de.andi1984.cadence.ui.Routes
import de.andi1984.cadence.ui.TaskList
import de.andi1984.cadence.ui.TaskView
import de.andi1984.cadence.ui.dayProgress
import de.andi1984.cadence.ui.taskList
import java.time.LocalDate

/**
 * Which slice of the task list a [TaskListWidget] shows.
 *
 * Adding a widget is adding an entry here plus a three-line subclass and a provider-info XML —
 * the list chrome, the rows, the empty state and the quick-add button are written once. The
 * *scope* is the variable; the presentation is not.
 *
 * A scope is a list of [TaskView]s and two strings, deliberately: `ui/TaskLists.kt` already
 * decides which tasks a list shows and in what order, so a widget names views rather than
 * deriving one. Deriving is the drift that refactor was made to end — a widget filtering for
 * itself would be a sixth copy of rules that had already gone out of step between two screens.
 * More than one view is *concatenation*, never filtering: the agenda is the Today screen's bands
 * followed by the Upcoming screen's, each exactly as its screen draws it.
 *
 * What comes with that for free is the user's own `showCompleted` and `sortMode`, handled per
 * view the way each screen handles it. `showCompleted` earns a second keep here: without it a
 * ticked task would simply vanish from the widget, which reads as though the row had been
 * deleted rather than completed. With it on — the default — the circle fills in and the title
 * strikes through, so a tap visibly *did* something. A widget nobody can see respond to a tap is
 * a widget nobody believes is interactive.
 */
enum class TaskListScope(
    @StringRes val titleRes: Int,
    @StringRes val emptyRes: Int,
    val views: List<TaskView>,
    /** The screen the header opens — the one this widget mirrors. */
    val route: String,
    /** Whether the header draws [dayProgress] — only where "the day" is the unit of work. */
    val showsProgress: Boolean,
) {

    /** What [de.andi1984.cadence.ui.today.TodayScreen] shows: overdue first, then due today. */
    TODAY(
        R.string.widget_today_title,
        R.string.widget_today_empty,
        listOf(TaskView.Today),
        Routes.TODAY,
        showsProgress = true,
    ),

    /** What [de.andi1984.cadence.ui.inbox.InboxScreen] shows: unfiled, parents speaking for
     *  their steps. The Inbox is a place, not a plan — half-done means nothing there. */
    INBOX(
        R.string.widget_inbox_title,
        R.string.widget_inbox_empty,
        listOf(TaskView.Inbox),
        Routes.INBOX,
        showsProgress = false,
    ),

    /**
     * Today and Upcoming as one scrolling agenda — overdue, today, then one labelled band per
     * day — for a home screen that wants the week rather than the day. The header opens Today,
     * where the agenda starts.
     */
    AGENDA(
        R.string.widget_agenda_title,
        R.string.widget_agenda_empty,
        listOf(TaskView.Today, TaskView.Upcoming),
        Routes.TODAY,
        showsProgress = false,
    ),
}

/**
 * The views' lists end to end — the one place more than one [TaskView] becomes one [TaskList].
 * Each view keeps its own bands, so a band never mixes two screens' rules.
 */
internal fun CadenceUiState.widgetList(views: List<TaskView>, today: LocalDate): TaskList =
    TaskList(views.flatMap { taskList(it, today).bands })

/**
 * Everything around the rows that differs between list widgets: the words, where a tap goes, and
 * whether the day's progress is drawn. The rows, bands, cards and empty state are
 * [TaskListContent]'s and the same for every list.
 */
internal class ListChrome(
    val title: String,
    val emptyText: String,
    /** Where the title opens — the screen this widget mirrors. */
    val open: Intent,
    /** Where the "+" and the empty state go — quick-add, preset for the widget's scope. */
    val quickAdd: Intent,
    /** The day's progress, drawn under the header; `null` where there is no day to speak of. */
    val progress: DayProgress?,
)

/**
 * A scrolling day view for the home screen: every overdue and due-today task reachable without
 * opening the app, tickable in place, under a header that says how the day stands.
 *
 * Which tasks, in what order, is [de.andi1984.cadence.ui.taskList]'s answer rather than one of
 * this widget's own — bands included: the Overdue block above the day is the same split the
 * Today screen draws, and the section labels here are that structure made visible, not a new
 * one. The header counts what is still open and, on Today, draws the day's progress, because
 * the question a widget answers from arm's length is "how am I doing" before it is "what next".
 *
 * **The rows scroll, and the price of that is known and paid.** A Glance `LazyColumn` is a
 * launcher `ListView`; its rows are RemoteViews collection items, which own no `PendingIntent`
 * of their own — so every tap in a row leaves by `actionStartActivity`, the one route a
 * collection reliably delivers ([WidgetToggleActivity] is the invisible landing for the circle;
 * `actionRunCallback` from a row silently never arrived on a real device). Below Android 12 the
 * items are served from an in-memory store that dies with the process — which is why frame one
 * being drawn from a real snapshot ([widgetSnapshot]) matters doubly here: the "ready" frame the
 * item service waits for must already contain the list. The scroll position resets when the
 * list content changes; for a widget updated by writes and midnight, that is rare enough to
 * accept in exchange for reaching the whole day.
 *
 * [SizeMode.Single], because a scrolling list is the same composition at every size — it simply
 * shows more or fewer rows — so no resize or rotation ever needs the process for a new layout.
 *
 * The session mechanics — the snapshot-then-flow pair, the midnight rearm, the staleness-guarded
 * sync — live once in [CadenceStateWidget]; this class only ever says what to draw from the state
 * it is handed.
 */
abstract class TaskListWidget(private val scope: TaskListScope) : CadenceStateWidget() {

    override val sizeMode = SizeMode.Single

    @Composable
    override fun Content(context: Context, state: CadenceUiState?, today: LocalDate) {
        val chrome = ListChrome(
            title = context.getString(scope.titleRes),
            emptyText = context.getString(scope.emptyRes),
            open = WidgetIntents.openRoute(context, scope.route),
            quickAdd = WidgetIntents.openQuickAdd(context),
            progress = if (scope.showsProgress) state?.dayProgress(today) else null,
        )
        TaskListContent(context, chrome, state?.widgetList(scope.views, today), today)
    }
}

class CadenceTodayWidget : TaskListWidget(TaskListScope.TODAY)

class CadenceInboxWidget : TaskListWidget(TaskListScope.INBOX)

class CadenceAgendaWidget : TaskListWidget(TaskListScope.AGENDA)

/** What one `LazyColumn` position draws: a task card, or the label above a band. */
private sealed interface ListEntry {
    data class Heading(val text: String, val isOverdue: Boolean) : ListEntry
    data class Card(val task: Task) : ListEntry
}

/**
 * The list flattened for a `LazyColumn`, labels spelled out.
 *
 * Labels are drawn only when there is a split to explain: a day with nothing overdue, or the
 * Inbox, is one run of cards and a "Today" label over the only thing on screen would be the
 * widget reading its own title aloud. Counts ride on the labels because the collapsed band is
 * exactly what a glance cannot count.
 */
private fun listEntries(context: Context, list: TaskList, today: LocalDate): List<ListEntry> {
    val bands = list.bands.filter { it.rows.isNotEmpty() }
    // A day band is labelled even alone: an agenda holding only Friday's work must not read as
    // today's.
    val labelled = bands.size > 1 || bands.any { it.heading is BandHeading.Day }
    return buildList {
        bands.forEach { band ->
            if (labelled) {
                val label = bandLabel(context, band.heading, today)
                add(ListEntry.Heading("$label · ${band.rows.size}", band.heading == BandHeading.Overdue))
            }
            band.rows.forEach { row -> add(ListEntry.Card(row.task)) }
        }
    }
}

/**
 * A band's heading in words — the same words the screen the band comes from uses. `None` only
 * ever needs a label as the due-today half of the Today list: every other single-band list is
 * drawn unlabelled.
 */
private fun bandLabel(context: Context, heading: BandHeading, today: LocalDate): String = when (heading) {
    BandHeading.Overdue -> context.getString(R.string.widget_overdue)
    BandHeading.None -> context.getString(R.string.widget_due_today)
    BandHeading.Everything -> context.getString(R.string.widget_band_all)
    BandHeading.Ungrouped -> context.getString(R.string.widget_band_ungrouped)
    is BandHeading.Named -> heading.section.name
    is BandHeading.Day -> dayLabel(context, heading.date, today)
}

@Composable
internal fun TaskListContent(
    context: Context,
    chrome: ListChrome,
    /** `null` only when there is no container to read from — drawn as the header alone. */
    list: TaskList?,
    today: LocalDate,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp),
    ) {
        Header(context, chrome, list)
        when {
            list == null -> Spacer(GlanceModifier.fillMaxSize())
            list.tasks.isEmpty() -> EmptyState(chrome)
            else -> {
                val entries = listEntries(context, list, today)
                LazyColumn(modifier = GlanceModifier.fillMaxSize().padding(bottom = 8.dp)) {
                    itemsIndexed(
                        entries,
                        // Stable ids keep an update from re-animating every row: a card keeps
                        // its task's id — an Int hash, so always within ±2^31 — and a heading
                        // takes a positive slot shifted past the whole Int range, where no card's
                        // hash can collide with it. Nothing here may go below -2^62: Glance
                        // reserves everything beneath that for its own implicit ids and rejects
                        // it with a `require`, and a throwing composition is not a broken row but
                        // a whole tile showing the launcher's "cannot display content" layout.
                        // (`Long.MIN_VALUE + index` sat in that range — every widget with an
                        // Overdue heading rendered as exactly that error.)
                        itemId = { index, entry ->
                            when (entry) {
                                is ListEntry.Card -> entry.task.id.hashCode().toLong()
                                is ListEntry.Heading -> (index + 1L) shl 32
                            }
                        },
                    ) { _, entry ->
                        when (entry) {
                            is ListEntry.Heading -> BandLabel(entry)
                            is ListEntry.Card -> TaskWidgetRow(
                                context,
                                entry.task,
                                today,
                                toggleAction = actionStartActivity(
                                    WidgetIntents.toggleTask(context, entry.task.id),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BandLabel(entry: ListEntry.Heading) {
    Text(
        text = entry.text,
        maxLines = 1,
        modifier = GlanceModifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
        style = TextStyle(
            color = if (entry.isOverdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/**
 * Scope label small, the open count large, quick-add as a filled round button — and, where the
 * chrome carries one, a hairline of the day's progress under it. The count line answers the
 * arm's-length question; the title opens the screen the widget mirrors.
 */
@Composable
private fun Header(context: Context, chrome: ListChrome, list: TaskList?) {
    val open = list?.openCount ?: 0
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, end = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity(chrome.open)),
            ) {
                Text(
                    text = chrome.title,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = if (list == null || open == 0) {
                        context.getString(R.string.widget_all_done)
                    } else {
                        context.resources.getQuantityString(R.plurals.widget_open_count, open, open)
                    },
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Box(
                modifier = GlanceModifier
                    .size(44.dp)
                    .clickable(actionStartActivity(chrome.quickAdd)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(36.dp)
                        .background(GlanceTheme.colors.primary)
                        .cornerRadius(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_add),
                        contentDescription = context.getString(R.string.widget_add_task),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                        modifier = GlanceModifier.size(20.dp),
                    )
                }
            }
        }
        // Drawn from [dayProgress], not from the rows below: with Show completed off a finished
        // task leaves the list, and a bar counting the list's done rows sat at zero all day.
        val progress = chrome.progress
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator(
                progress = progress.fraction,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(horizontal = 16.dp),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surfaceVariant,
            )
            Spacer(GlanceModifier.height(6.dp))
        }
    }
}

/**
 * The empty state is the invitation, so it opens quick-add rather than the app: a widget showing
 * "nothing due today" is exactly when someone wants to add something.
 */
@Composable
private fun EmptyState(chrome: ListChrome) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity(chrome.quickAdd))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_circle_check),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(36.dp),
        )
        Spacer(GlanceModifier.height(8.dp))
        Text(
            text = chrome.emptyText,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
