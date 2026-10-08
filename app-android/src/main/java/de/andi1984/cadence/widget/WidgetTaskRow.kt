package de.andi1984.cadence.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/**
 * One task row, shared by every list widget so they read as one family rather than as unrelated
 * designs.
 *
 * The row is a rounded card on the widget surface — the shape the launcher's own M3 widgets
 * (Tasks, Keep) settled on, because on a home screen a bare list reads as wallpaper while cards
 * read as touchable. An overdue card is tinted with the error container, the widget's version of
 * the red block the Today screen pins above the day; the completion ring wears the priority
 * colour. Neither colour stands alone (`CLAUDE.md`, UI conventions): the meta line always spells
 * out [de.andi1984.cadence.domain.model.Priority.shortLabel], and "Overdue" is written next to
 * it. `cornerRadius` clips on Android 12+ and quietly draws square corners below — a degrade,
 * not a break.
 *
 * **The toggle is the caller's to wire**, because where the row sits decides which route a tap
 * can take at all. Inside a `LazyColumn` a row is a RemoteViews collection item, and only
 * `actionStartActivity` reliably escapes one — `WidgetIntents.toggleTask`, the invisible
 * [WidgetToggleActivity]. On a plain surface (the next-task widget) [ToggleTaskCallback]'s
 * broadcast is the better route: no window, no activity start. [TaskListWidget] and
 * [CadenceNextTaskWidget] each pass their own.
 */
@Composable
fun TaskWidgetRow(
    context: Context,
    task: Task,
    today: LocalDate,
    toggleAction: Action,
    modifier: GlanceModifier = GlanceModifier,
    card: Boolean = true,
) {
    val overdue = task.isOverdue(today)
    val surface = when {
        !card -> null
        overdue -> GlanceTheme.colors.errorContainer
        else -> GlanceTheme.colors.secondaryContainer
    }
    val titleColor = when {
        task.isDone -> GlanceTheme.colors.onSurfaceVariant
        overdue && card -> GlanceTheme.colors.onErrorContainer
        else -> GlanceTheme.colors.onSurface
    }

    // The 2dp frame is the gap between cards; the card itself is the inner Row.
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
        var row = GlanceModifier.fillMaxWidth().height(48.dp)
        if (surface != null) row = row.background(surface).cornerRadius(14.dp)
        Row(modifier = row, verticalAlignment = Alignment.CenterVertically) {
            Box(
                // 44dp of touch target around a 26dp circle — the same figure
                // `ui/components/TaskRow.kt`'s CompletionCircle keeps, and the same reason.
                modifier = GlanceModifier.size(44.dp).clickable(toggleAction),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(
                        if (task.isDone) R.drawable.ic_widget_circle_check else R.drawable.ic_widget_circle_ring,
                    ),
                    contentDescription = context.getString(
                        if (task.isDone) R.string.widget_mark_open else R.string.widget_mark_done,
                        task.title,
                    ),
                    colorFilter = ColorFilter.tint(
                        if (task.isDone) {
                            GlanceTheme.colors.onSurfaceVariant
                        } else {
                            widgetPriorityColor(task.priority)
                        },
                    ),
                    modifier = GlanceModifier.size(26.dp),
                )
            }
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .padding(end = 12.dp)
                    .clickable(actionStartActivity(WidgetIntents.openTask(context, task.id))),
            ) {
                Text(
                    text = task.title,
                    maxLines = 1,
                    style = TextStyle(
                        color = titleColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        // Struck through and dimmed once done, so the tap that completed it
                        // reads as "finished" rather than as the row having been removed.
                        textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.priority.shortLabel,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (task.isDone) {
                                GlanceTheme.colors.onSurfaceVariant
                            } else {
                                widgetPriorityColor(task.priority)
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        text = " · " + dueLabel(context, task, today),
                        maxLines = 1,
                        style = TextStyle(color = metaColor(task, today, card), fontSize = 11.sp),
                    )
                }
            }
        }
    }
}

private fun dueLabel(context: Context, task: Task, today: LocalDate): String {
    val dueDate = task.dueDate
    val dueTime = task.dueTime
    return when {
        task.isDone -> context.getString(R.string.widget_done)
        task.isOverdue(today) -> context.getString(R.string.widget_overdue)
        dueDate == null -> context.getString(R.string.widget_no_due_date)
        dueDate == today && dueTime != null -> timeLabel(context, dueTime)
        dueDate == today -> context.getString(R.string.widget_due_today)
        dueTime != null ->
            context.getString(R.string.widget_day_at_time, dayLabel(context, dueDate, today), timeLabel(context, dueTime))
        else -> dayLabel(context, dueDate, today)
    }
}

/**
 * The language the widget's words are in. Android 13's per-app picker can set Primico apart from
 * the system, and `context.getString` already follows it — `Locale.getDefault()` would format the
 * dates in one language and the labels around them in another.
 */
internal fun widgetLocale(context: Context): Locale = context.resources.configuration.locales[0]

/**
 * Locale-formatted by java.time, the same source the quick-add grammar trusts for weekday names —
 * a pattern of our own here would be a second clock format to keep in step with the app's.
 */
internal fun timeLabel(context: Context, time: LocalTime): String =
    time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(widgetLocale(context)))

/**
 * A future day the way someone says it: "Tomorrow", a weekday within the coming week, a date
 * beyond that. It used to be `LocalDate.toString()` — ISO `2026-10-09` on a home screen, in
 * either language.
 */
internal fun dayLabel(context: Context, date: LocalDate, today: LocalDate): String {
    val locale = widgetLocale(context)
    return when {
        date == today -> context.getString(R.string.widget_due_today)
        date == today.plusDays(1) -> context.getString(R.string.widget_tomorrow)
        date.isAfter(today) && date.isBefore(today.plusDays(7)) ->
            date.dayOfWeek.getDisplayName(DateTextStyle.FULL, locale)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

@Composable
private fun metaColor(task: Task, today: LocalDate, card: Boolean): ColorProvider = when {
    task.isDone -> GlanceTheme.colors.onSurfaceVariant
    task.isOverdue(today) -> if (card) GlanceTheme.colors.onErrorContainer else GlanceTheme.colors.error
    else -> GlanceTheme.colors.onSurfaceVariant
}
