package de.andi1984.cadence.domain.reminder

import de.andi1984.cadence.domain.model.Task
import java.time.Instant
import java.time.ZoneId

/**
 * One concrete moment a `ReminderScheduler`-shaped platform port should fire a notification for
 * [taskId]. [slot] is the alarm's identity within the task (see [Reminder.slot]); [source] says
 * what the alarm is — a device lead or one of the task's own reminder entries, with the repeat
 * index — which is what a shell needs to word the notification.
 */
data class ReminderInstant(
    val taskId: String,
    val slot: String,
    val triggerAt: Instant,
    val source: ReminderSource,
)

/** What an armed alarm is: a per-device lead counted back from the due time, or one of the task's
 *  own [de.andi1984.cadence.domain.model.Task.reminders] entries (with its repeat index). */
sealed interface ReminderSource {
    data class Lead(val minutes: Int) : ReminderSource
    data class Entry(val reminder: Reminder, val repeatIndex: Int) : ReminderSource
}

/**
 * Turns tasks into the concrete instants a reminder scheduler should arm — pure, so both shells'
 * platform schedulers (Android's AlarmManager, the desktop's poll) share one answer to "when" and
 * it is unit-testable on the JVM instead of copied twice.
 *
 * Two independent sources, both optional, land in the result for a task:
 *  - [Task.dueTime] fires once per entry in [leadMinutes], that many minutes before it — quick
 *    add's "18 Uhr" already fills [Task.dueTime] in, so this list is what turns a time typed into
 *    a task into an actual notification. The slot is `lead:N`.
 *  - each entry of [Task.reminders] fires per its own trigger: [ReminderTrigger.BeforeDueTime]
 *    needs the due moment, [ReminderTrigger.DaysBeforeDue] the due date, [ReminderTrigger.At]
 *    nothing at all — which is why it is also the only kind an undated task can carry. A
 *    [Reminder.repeat] expands into one instant per re-fire, capped at [MAX_NAG] in total.
 *
 * A task that is already done contributes nothing. **Deliberately not filtered by "now"**, unlike
 * [de.andi1984.cadence.domain.recurrence.RecurrenceEngine]'s date math: an instant already in the
 * past is still reported, because the two platform schedulers disagree on what that should mean.
 * AlarmManager cannot fire retroactively, so Android's scheduler discards a past instant and
 * cancels whatever alarm used to be there; the desktop has no such limit and deliberately fires on
 * the very next poll after a reminder that elapsed while the app was closed, exactly as it did
 * before this planner existed. Filtering here would have silently taken that behaviour away from
 * the desktop to give Android something it already does for itself.
 */
object ReminderPlanner {

    /**
     * The cap on how many instants one repeating reminder may produce. A `repeat` with a null
     * count means "until done or dismissed"; the planner still has to bound it, or one nagging
     * reminder would arm an unbounded list of alarms.
     */
    const val MAX_NAG = 12

    fun plan(
        tasks: List<Task>,
        leadMinutes: List<Int>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ReminderInstant> = tasks.flatMap { task -> instantsFor(task, leadMinutes, zone) }

    private fun instantsFor(task: Task, leadMinutes: List<Int>, zone: ZoneId): List<ReminderInstant> {
        if (task.isDone) return emptyList()
        val instants = ArrayList<ReminderInstant>()

        task.dueDate?.let { due ->
            task.dueTime?.let { time ->
                val anchor = due.atTime(time).atZone(zone).toInstant()
                leadMinutes.filter { it > 0 }.distinct().forEach { lead ->
                    instants += ReminderInstant(
                        taskId = task.id,
                        slot = "lead:$lead",
                        triggerAt = anchor.minusSeconds(lead.toLong() * 60),
                        source = ReminderSource.Lead(lead),
                    )
                }
            }
        }

        // `distinct()` is the dedupe rule: identical reminders on one task collapse to one alarm.
        task.reminders.distinct().forEach { reminder ->
            val trigger = reminder.trigger
            val base: Instant? = when (trigger) {
                is ReminderTrigger.BeforeDueTime -> {
                    val due = task.dueDate
                    val time = task.dueTime
                    if (due == null || time == null) {
                        null
                    } else {
                        val anchor = due.atTime(time).atZone(zone).toInstant()
                        anchor.minusSeconds(trigger.minutes.toLong() * 60)
                    }
                }
                is ReminderTrigger.DaysBeforeDue -> task.dueDate?.let { due ->
                    // Whole days at a wall-clock time: `minusDays` on the date, not a minute
                    // offset on an instant, so a DST change in between moves the wall time with
                    // the day instead of the reminder slipping an hour.
                    val date = due.minusDays(trigger.days.toLong())
                    date.atTime(trigger.at).atZone(zone).toInstant()
                }
                is ReminderTrigger.At ->
                    trigger.dateTime.atZone(zone).toInstant()
            }
            if (base == null) return@forEach
            val encoded = ReminderFormat.encode(trigger)
            val repeat = reminder.repeat
            val times = if (repeat == null) {
                1
            } else {
                val wanted = repeat.count?.plus(1) ?: MAX_NAG
                wanted.coerceAtMost(MAX_NAG).coerceAtLeast(1)
            }
            for (index in 0 until times) {
                instants += ReminderInstant(
                    taskId = task.id,
                    slot = "$encoded#$index",
                    triggerAt = if (repeat == null || index == 0) {
                        base
                    } else {
                        base.plusSeconds(repeat.everyMinutes.toLong() * 60 * index)
                    },
                    source = ReminderSource.Entry(reminder, index),
                )
            }
        }

        return instants
    }
}
