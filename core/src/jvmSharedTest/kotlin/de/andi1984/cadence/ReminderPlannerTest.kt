package de.andi1984.cadence

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.Reminder
import de.andi1984.cadence.domain.reminder.ReminderInstant
import de.andi1984.cadence.domain.reminder.ReminderPlanner
import de.andi1984.cadence.domain.reminder.ReminderRepeat
import de.andi1984.cadence.domain.reminder.ReminderSource
import de.andi1984.cadence.domain.reminder.ReminderTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderPlannerTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 8, 12)

    private fun task(
        id: String = "a",
        due: LocalDate? = today,
        dueTime: LocalTime? = null,
        reminders: List<Reminder> = emptyList(),
        done: Boolean = false,
    ) = Task(
        id = id,
        title = id,
        dueDate = due,
        dueTime = dueTime,
        reminders = reminders,
        completedAt = if (done) Instant.EPOCH else null,
    )

    private fun at(time: LocalTime, date: LocalDate = today): Instant =
        date.atTime(time).toInstant(ZoneOffset.UTC)

    // ── Device leads ─────────────────────────────────────────────────────────────

    @Test
    fun `one alarm per configured lead minutes, counting back from due time, with lead slots`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(dueTime = LocalTime.of(18, 0))),
            leadMinutes = listOf(20, 10, 5),
            zone = zone,
        )

        val expected = setOf(
            ReminderInstant("a", "lead:20", at(LocalTime.of(17, 40)), ReminderSource.Lead(20)),
            ReminderInstant("a", "lead:10", at(LocalTime.of(17, 50)), ReminderSource.Lead(10)),
            ReminderInstant("a", "lead:5", at(LocalTime.of(17, 55)), ReminderSource.Lead(5)),
        )
        assertEquals(expected, plan.toSet())
    }

    @Test
    fun `an instant already in the past is still reported, not filtered here`() {
        // Filtering by "now" is the platform scheduler's job, and the two disagree about what a
        // past instant means: Android drops it (AlarmManager cannot fire retroactively), the
        // desktop fires it on the next poll. The planner stays neutral between the two.
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(due = today.minusDays(1), dueTime = LocalTime.of(9, 0))),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertEquals(1, plan.size)
        assertEquals(at(LocalTime.of(8, 50), today.minusDays(1)), plan.single().triggerAt)
    }

    @Test
    fun `no due time means no lead-based alarms, lead list or not`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(dueTime = null)),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a repeated lead value is not scheduled twice`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(dueTime = LocalTime.of(18, 0))),
            leadMinutes = listOf(10, 10),
            zone = zone,
        )

        assertEquals(1, plan.size)
    }

    // ── The task's own reminders ─────────────────────────────────────────────────

    @Test
    fun `days before the due date fires at the wall-clock time on the day counted back, with a dN-at-HHmm slot`() {
        val reminder = Reminder(ReminderTrigger.DaysBeforeDue(2, LocalTime.of(9, 0)))

        val plan = ReminderPlanner.plan(listOf(task(reminders = listOf(reminder))), emptyList(), zone)

        assertEquals(
            listOf(ReminderInstant("a", "d2@09:00#0", at(LocalTime.of(9, 0), today.minusDays(2)), ReminderSource.Entry(reminder, 0))),
            plan,
        )
    }

    @Test
    fun `days before across a DST change keeps the wall-clock time, which a minute offset would not`() {
        // Europe/Berlin springs forward on 2026-03-29. A reminder 4 days before a 2026-04-01 due
        // date lands on 2026-03-28 at 09:00 Berlin — still CET, so 08:00 UTC. Counting back 4×24×60
        // minutes from the due moment instead would land on 07:00 UTC, i.e. 08:00 local: an hour
        // early, exactly the slip a minute offset cannot avoid.
        val berlin = ZoneId.of("Europe/Berlin")
        val due = LocalDate.of(2026, 4, 1)
        val reminder = Reminder(ReminderTrigger.DaysBeforeDue(4, LocalTime.of(9, 0)))

        val plan = ReminderPlanner.plan(
            listOf(task(due = due, reminders = listOf(reminder))),
            emptyList(),
            berlin,
        )

        val expected = LocalDate.of(2026, 3, 28).atTime(LocalTime.of(9, 0)).atZone(berlin).toInstant()
        assertEquals(listOf(expected), plan.map { it.triggerAt })
        assertEquals(Instant.parse("2026-03-28T08:00:00Z"), plan.single().triggerAt)
    }

    @Test
    fun `same-day reminder is the legacy on-the-due-day shape, with a d0 slot`() {
        val reminder = Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)))

        val plan = ReminderPlanner.plan(listOf(task(reminders = listOf(reminder))), emptyList(), zone)

        assertEquals(at(LocalTime.of(9, 0)), plan.single().triggerAt)
        assertEquals("d0@09:00#0", plan.single().slot)
    }

    @Test
    fun `minutes before the due time needs both a due date and a due time`() {
        val reminder = Reminder(ReminderTrigger.BeforeDueTime(30))

        val withBoth = ReminderPlanner.plan(
            listOf(task(dueTime = LocalTime.of(18, 0), reminders = listOf(reminder))),
            emptyList(),
            zone,
        )
        assertEquals(listOf(at(LocalTime.of(17, 30))), withBoth.map { it.triggerAt })
        assertEquals("m30#0", withBoth.single().slot)

        // No due time: nothing to count back from, so nothing fires.
        assertTrue(
            ReminderPlanner.plan(listOf(task(reminders = listOf(reminder))), emptyList(), zone).isEmpty(),
        )
        // No due date either: the moment is undefined.
        assertTrue(
            ReminderPlanner.plan(
                listOf(task(due = null, dueTime = LocalTime.of(18, 0), reminders = listOf(reminder))),
                emptyList(),
                zone,
            ).isEmpty(),
        )
    }

    @Test
    fun `a fixed date-time fires on an undated task, as t…#0`() {
        // The one kind that works without a due date — the fix for the old dead-reminder bug,
        // where an undated task accepted a reminderTime that could never fire.
        val reminder = Reminder(ReminderTrigger.At(LocalDateTime.of(2026, 10, 26, 9, 0)))

        val plan = ReminderPlanner.plan(
            listOf(task(due = null, reminders = listOf(reminder))),
            emptyList(),
            zone,
        )

        assertEquals(
            listOf(
                ReminderInstant(
                    "a",
                    "t2026-10-26T09:00#0",
                    LocalDateTime.of(2026, 10, 26, 9, 0).toInstant(ZoneOffset.UTC),
                    ReminderSource.Entry(reminder, 0),
                ),
            ),
            plan,
        )
    }

    @Test
    fun `a days-before reminder on an undated task never fires`() {
        val reminder = Reminder(ReminderTrigger.DaysBeforeDue(1, LocalTime.of(9, 0)))

        assertTrue(
            ReminderPlanner.plan(listOf(task(due = null, reminders = listOf(reminder))), emptyList(), zone).isEmpty(),
        )
    }

    // ── Repeat (nag) ─────────────────────────────────────────────────────────────

    @Test
    fun `a repeat with a count expands into one instant per re-fire, slots suffixed with the index`() {
        val reminder = Reminder(
            ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)),
            ReminderRepeat(10, 2),
        )

        val plan = ReminderPlanner.plan(listOf(task(reminders = listOf(reminder))), emptyList(), zone)

        assertEquals(
            listOf(at(LocalTime.of(9, 0)), at(LocalTime.of(9, 10)), at(LocalTime.of(9, 20))),
            plan.map { it.triggerAt },
        )
        assertEquals(listOf("d0@09:00#0", "d0@09:00#1", "d0@09:00#2"), plan.map { it.slot })
    }

    @Test
    fun `a repeat without a count is capped at MAX_NAG instants`() {
        val reminder = Reminder(
            ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)),
            ReminderRepeat(5, null),
        )

        val plan = ReminderPlanner.plan(listOf(task(reminders = listOf(reminder))), emptyList(), zone)

        assertEquals(ReminderPlanner.MAX_NAG, plan.size)
        assertEquals(at(LocalTime.of(9, 0)).plusSeconds(5L * 60 * (ReminderPlanner.MAX_NAG - 1)), plan.last().triggerAt)
    }

    @Test
    fun `a repeat count beyond the cap is capped too`() {
        val reminder = Reminder(
            ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)),
            ReminderRepeat(1, 100),
        )

        val plan = ReminderPlanner.plan(listOf(task(reminders = listOf(reminder))), emptyList(), zone)

        assertEquals(ReminderPlanner.MAX_NAG, plan.size)
    }

    // ── The shared rules ─────────────────────────────────────────────────────────

    @Test
    fun `no due date and no fixed reminder means nothing to schedule at all`() {
        val plan = ReminderPlanner.plan(
            listOf(task(due = null, dueTime = LocalTime.of(18, 0))),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a finished task never schedules`() {
        val plan = ReminderPlanner.plan(
            listOf(
                task(
                    dueTime = LocalTime.of(18, 0),
                    reminders = listOf(Reminder(ReminderTrigger.DaysBeforeDue(1, LocalTime.of(9, 0)))),
                    done = true,
                ),
            ),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `identical reminders on one task collapse to one alarm`() {
        val reminder = Reminder(ReminderTrigger.DaysBeforeDue(1, LocalTime.of(9, 0)))

        val plan = ReminderPlanner.plan(
            listOf(task(reminders = listOf(reminder, reminder.copy(), reminder))),
            emptyList(),
            zone,
        )

        assertEquals(1, plan.size)
    }

    @Test
    fun `a due time lead and a task reminder both firing produce independent alarms`() {
        val reminder = Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)))

        val plan = ReminderPlanner.plan(
            listOf(task(dueTime = LocalTime.of(18, 0), reminders = listOf(reminder))),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertEquals(2, plan.size)
        assertEquals(setOf("lead:10", "d0@09:00#0"), plan.map { it.slot }.toSet())
    }
}
