package de.andi1984.cadence.ui.format

import androidx.compose.runtime.Composable
import de.andi1984.cadence.domain.reminder.Reminder
import de.andi1984.cadence.domain.reminder.ReminderSource
import de.andi1984.cadence.domain.reminder.ReminderTrigger
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate

/**
 * Turning a [Reminder] into text. No prose lives in the domain: the words are these resources, in
 * `values` and `values-de`, and the domain only says *what* the reminder is.
 *
 * Two audiences read a reminder. The task detail's reminder rows describe the reminder itself —
 * "2 days before at 09:00" — through the `@Composable` [describeReminder]. A notification fired
 * for it describes the *task* at the moment of firing — "Due in 2 days" — through
 * [notificationLabel], which the shells call from non-composable context (Android's receiver reads
 * its own `res/`, the desktop's poll reads these resources through the suspend accessors).
 */

/** The row label for one reminder: what it is, plus its repeat if it has one. */
@Composable
fun describeReminder(reminder: Reminder, today: LocalDate): String {
    val trigger = when (val t = reminder.trigger) {
        is ReminderTrigger.BeforeDueTime -> pluralStringResource(
            Res.plurals.reminder_minutes_before,
            t.minutes,
            t.minutes,
        )
        is ReminderTrigger.DaysBeforeDue -> when (t.days) {
            0 -> stringResource(Res.string.reminder_on_due_day_at, formatTime(t.at))
            else -> pluralStringResource(
                Res.plurals.reminder_days_before_at,
                t.days,
                t.days,
                formatTime(t.at),
            )
        }
        is ReminderTrigger.At -> stringResource(
            Res.string.reminder_at_date_time,
            relativeDate(t.dateTime.toLocalDate(), today),
            formatTime(t.dateTime.toLocalTime()),
        )
    }
    val repeat = reminder.repeat ?: return trigger
    val count = repeat.count
    return if (count != null) {
        stringResource(Res.string.reminder_repeat_times, trigger, repeat.everyMinutes, count)
    } else {
        stringResource(Res.string.reminder_repeat_until_done, trigger, repeat.everyMinutes)
    }
}

/**
 * The notification's content line, worded from the trigger: "Due in 2 days" / "Due tomorrow" /
 * "Due today" / "Overdue" for a days-before reminder, the lead plurals for minutes before the due
 * moment, a plain "Reminder" for a fixed date-time. Suspend rather than `@Composable` because the
 * desktop's poll fires from a coroutine, not from composition.
 */
suspend fun notificationLabel(title: String, source: ReminderSource): String = when (source) {
    is ReminderSource.Lead -> if (source.minutes > 0) {
        "$title — ${getPluralString(Res.plurals.reminder_lead_content_text, source.minutes, source.minutes)}"
    } else {
        title
    }
    is ReminderSource.Entry -> when (val t = source.reminder.trigger) {
        is ReminderTrigger.DaysBeforeDue -> when {
            t.days > 0 -> "$title — ${getPluralString(Res.plurals.reminder_due_in_days, t.days, t.days)}"
            t.days == 0 -> "$title — ${getString(Res.string.reminder_content_text)}"
            else -> "$title — ${getPluralString(Res.plurals.reminder_overdue, -t.days, -t.days)}"
        }
        is ReminderTrigger.BeforeDueTime -> if (t.minutes > 0) {
            "$title — ${getPluralString(Res.plurals.reminder_lead_content_text, t.minutes, t.minutes)}"
        } else {
            "$title — ${getString(Res.string.reminder_content_text)}"
        }
        is ReminderTrigger.At -> "$title — ${getString(Res.string.reminder_at_content_text)}"
    }
}
