package de.andi1984.cadence.domain.reminder

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * One reminder on a task: a [trigger] saying when it fires, plus an optional [repeat] that
 * re-fires it. A task carries any number of these ([de.andi1984.cadence.domain.model.Task.reminders]);
 * the three trigger kinds answer the same question — "when?" — in the three shapes a person means:
 *
 *  - [ReminderTrigger.BeforeDueTime] counts back from the due *moment*, so it needs both a due
 *    date and a due time. Negative minutes fire after it.
 *  - [ReminderTrigger.DaysBeforeDue] counts back whole *days* from the due date at a wall-clock
 *    time — the right shape for an all-day task, and the one that stays correct across a DST
 *    change, which a minute offset is not. `days = 0` is "on the due day", the legacy
 *    `reminderTime` behaviour.
 *  - [ReminderTrigger.At] is a fixed local date-time ("floating": re-read in the device's zone),
 *    and the only kind that works on a task with no due date.
 *
 * Location reminders are deliberately left out: Android would need Play Services geofencing the
 * app does not use, and the desktop could never fire them. The trigger is a sealed type and every
 * codec skips unknown kinds, so a `Location` can be added later without breaking the format.
 *
 * Snooze, reschedule and "overdue → today" need no code per kind: the relative triggers are
 * computed from the due date at plan time, so they follow it automatically, while [At] stays
 * fixed because that is what absolute means.
 */
data class Reminder(val trigger: ReminderTrigger, val repeat: ReminderRepeat? = null)

sealed interface ReminderTrigger {
    /** N minutes before (negative = after) the due *moment*. Needs dueDate + dueTime. */
    data class BeforeDueTime(val minutes: Int) : ReminderTrigger

    /** N days before (negative = after) the due *date*, at a wall-clock [at] time. */
    data class DaysBeforeDue(val days: Int, val at: LocalTime) : ReminderTrigger

    /** A fixed local date-time. Works on undated tasks. */
    data class At(val dateTime: LocalDateTime) : ReminderTrigger
}

/**
 * Re-fires the reminder every [everyMinutes] up to [count] more times; a null [count] means
 * "until the task is done or the reminder is dismissed", which the planner caps at
 * [ReminderPlanner.MAX_NAG] instants.
 */
data class ReminderRepeat(val everyMinutes: Int, val count: Int?)

/**
 * The packed, single-reminder encoding shared by the storage codec
 * ([de.andi1984.cadence.data.db.ReminderCodec]) and the alarm slot [ReminderPlanner] derives from
 * it: `m30` (minutes before the due time), `d1@09:00` (days before the due date at a time),
 * `t2026-10-26T09:00` (a fixed date-time), each with an optional `~r10x3` repeat suffix
 * (`~r10` for "until done"). The characters `=`, `,`, `|`, `#` never occur in one, which is what
 * the column format and the slot suffix rely on.
 *
 * Malformed input decodes to null, never throws — a broken column costs a task its reminders,
 * never the whole list.
 */
object ReminderFormat {

    private const val REPEAT = "~r"
    private const val REPEAT_COUNT = "x"

    fun encode(trigger: ReminderTrigger): String = when (trigger) {
        is ReminderTrigger.BeforeDueTime -> "m${trigger.minutes}"
        is ReminderTrigger.DaysBeforeDue -> "d${trigger.days}@${trigger.at}"
        is ReminderTrigger.At -> "t${trigger.dateTime}"
    }

    fun encode(reminder: Reminder): String {
        val repeat = reminder.repeat?.let { r ->
            if (r.count != null) "$REPEAT${r.everyMinutes}$REPEAT_COUNT${r.count}" else "$REPEAT${r.everyMinutes}"
        }.orEmpty()
        return encode(reminder.trigger) + repeat
    }

    fun decode(raw: String): Reminder? {
        val (triggerPart, repeatPart) = if (raw.contains(REPEAT)) {
            raw.substringBefore(REPEAT) to raw.substringAfter(REPEAT)
        } else {
            raw to null
        }
        val trigger = when {
            triggerPart.startsWith("m") -> triggerPart.removePrefix("m").toIntOrNull()
                ?.let { ReminderTrigger.BeforeDueTime(it) }
            triggerPart.startsWith("d") -> {
                val body = triggerPart.removePrefix("d")
                val at = body.substringAfter('@', "").takeIf { it.isNotEmpty() }?.let {
                    runCatching { LocalTime.parse(it) }.getOrNull()
                } ?: return null
                body.substringBefore('@').toIntOrNull()?.let { ReminderTrigger.DaysBeforeDue(it, at) }
            }
            triggerPart.startsWith("t") -> triggerPart.removePrefix("t").let {
                runCatching { LocalDateTime.parse(it) }.getOrNull()?.let { dt -> ReminderTrigger.At(dt) }
            }
            else -> null
        } ?: return null
        val repeat = repeatPart?.let { r ->
            val every = r.substringBefore(REPEAT_COUNT).toIntOrNull() ?: return null
            val count = if (r.contains(REPEAT_COUNT)) {
                r.substringAfter(REPEAT_COUNT).toIntOrNull() ?: return null
            } else {
                null
            }
            ReminderRepeat(every, count)
        }
        return Reminder(trigger, repeat)
    }
}

/**
 * The deterministic identity of one armed alarm: the trigger's packed encoding plus the repeat
 * index (`d1@09:00#0`, `d1@09:00#1`, …). Editing a reminder changes the encoding, so the old slot
 * is simply no longer planned and the reconciler cancels it while the new one is armed — no stored
 * ids needed. Device lead alarms use `lead:N`.
 */
fun Reminder.slot(repeatIndex: Int = 0): String = "${ReminderFormat.encode(trigger)}#$repeatIndex"

/**
 * What a recurring task hands its reminders to the next occurrence: the relative triggers carry
 * over unchanged — they are computed from the due date at plan time, so they follow it with no
 * code — while an [ReminderTrigger.At] shifts by the same day span as the due date (the rule the
 * subtask dates already follow). An `At` that is already in the past after the shift is dropped:
 * an absolute reminder that has passed is history, not something to fire again.
 */
fun List<Reminder>.shiftedByDays(days: Long, now: Instant, zone: ZoneId): List<Reminder> {
    if (days == 0L) return this
    val nowLocal = LocalDateTime.ofInstant(now, zone)
    return mapNotNull { reminder ->
        when (val trigger = reminder.trigger) {
            is ReminderTrigger.At -> {
                val shifted = trigger.dateTime.plusDays(days)
                if (shifted.isAfter(nowLocal)) reminder.copy(trigger = trigger.copy(dateTime = shifted)) else null
            }
            else -> reminder
        }
    }
}
