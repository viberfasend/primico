package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.Reminder
import de.andi1984.cadence.domain.reminder.ReminderSource
import de.andi1984.cadence.domain.reminder.ReminderTrigger
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The desktop's stand-in for AlarmManager: a coroutine that polls the current task list and
 * fires a tray balloon for whatever just came due.
 *
 * Two things are worth pinning down. A reminder must fire **once** — the poll runs every thirty
 * seconds and a task stays due forever once it is — and the reconcile must actually forget the
 * tasks it is no longer given, or a deleted row keeps a reminder that fires for something that
 * does not exist. What to arm, drop or leave alone between two reconciles is `:core`'s
 * `ReminderReconciler`, keyed by slot (`lead:N`, `d1@09:00#0`), and the tests about *that* — a
 * lead dropped from Settings, an alarm moved into the future, a reminder edited into a new slot —
 * live in `ReminderReconcilerTest`; this file is about the poll and the balloon.
 *
 * The poll interval is virtual (`StandardTestDispatcher` on `runTest`'s scheduler), but "due" is
 * decided against the wall clock inside the scheduler, so the fixtures are a day either side of
 * today rather than a few milliseconds.
 *
 * `advanceTimeBy` rather than `advanceUntilIdle`: the poll loop is a `while (true)`, so there is
 * always another task scheduled and "until idle" never arrives.
 *
 * The scheduler runs on **`runTest`'s `backgroundScope`**, and that is load-bearing rather than
 * tidy. `runTest` drains the shared `TestScheduler` once the test body returns, and a `while
 * (true) { poll(); delay(30s) }` loop on a scope of our own always has one more `delay` queued —
 * so the drain never finishes and the test process spins at 100% CPU forever. It was the whole
 * of `:app-desktop:test`, and on a runner it burned the job's time limit rather than failing.
 * `backgroundScope` is cancelled *before* that drain, which is exactly what it exists for.
 */
class DesktopReminderSchedulerTest {

    private val fired = mutableListOf<String>()

    /** The balloon wording the shell supplies; here a plain one, so the lead form keeps its
     *  "title — 10m" shape and everything else fires as the bare title. */
    private val labelFor: (String, ReminderSource) -> String = { title, source ->
        when (source) {
            is ReminderSource.Lead -> if (source.minutes > 0) "$title — ${source.minutes}m" else title
            is ReminderSource.Entry -> title
        }
    }

    private fun TestScope.scheduler(): DesktopReminderScheduler =
        DesktopReminderScheduler(
            scope = backgroundScope,
            trayIcon = null,
            notify = { message -> fired += message },
            labelFor = labelFor,
        )

    /** Reminders are on for every test here: "off" is an empty plan the reconciler answers, and
     *  that rule is pinned in `:core`, not against the poll. */
    private fun DesktopReminderScheduler.sync(tasks: List<Task>, leadMinutes: List<Int>) =
        sync(tasks, leadMinutes, enabled = true)

    /** One poll, without letting the loop's `delay` run. */
    private fun TestScope.poll() = runCurrent()

    /** The next poll, thirty seconds of virtual time later. */
    private fun TestScope.nextPoll() = advanceTimeBy(POLL_INTERVAL_MILLIS + 1)

    private val sameDayNoon = Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.NOON))

    private fun task(
        id: String,
        title: String = id,
        due: LocalDate?,
        reminders: List<Reminder> = listOf(sameDayNoon),
        dueTime: LocalTime? = null,
        done: Boolean = false,
    ) = Task(
        id = id,
        title = title,
        dueDate = due,
        dueTime = dueTime,
        reminders = reminders,
        completedAt = if (done) Instant.EPOCH else null,
    )

    private fun yesterday() = LocalDate.now().minusDays(1)

    private fun tomorrow() = LocalDate.now().plusDays(1)

    @Test
    fun `a task whose reminder has passed fires on the next poll`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", title = "Water the plants", due = yesterday())), emptyList())
        poll()

        assertEquals(listOf("Water the plants"), fired)
    }

    @Test
    fun `it fires once and only once, however many polls follow`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday())), emptyList())
        poll()
        nextPoll()
        nextPoll()
        nextPoll()

        assertEquals(listOf("a"), fired)
    }

    @Test
    fun `a reminder still in the future stays quiet`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = tomorrow())), emptyList())
        poll()
        nextPoll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `a task with no reminders never fires, due or not`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday(), reminders = emptyList())), emptyList())
        poll()
        nextPoll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `a finished task never fires`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday(), done = true)), emptyList())
        poll()
        nextPoll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `a task the reconcile no longer lists is forgotten and never fires`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = tomorrow())), emptyList())
        poll()
        // The row was deleted, or its reminder was cleared: sync is handed the list without it,
        // and that is what has to drop it from every map the poll reads.
        scheduler.sync(emptyList(), emptyList())
        scheduler.sync(listOf(task("a", due = yesterday())), emptyList())
        nextPoll()

        // Re-listed with a reminder now in the past, so it does fire — once.
        assertEquals(listOf("a"), fired)
    }

    @Test
    fun `cancel drops a task that is already due before the poll reaches it`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday()), task("b", due = yesterday())), emptyList())
        scheduler.cancel("a")
        poll()
        nextPoll()

        assertEquals(listOf("b"), fired)
    }

    @Test
    fun `several tasks due at once each fire once`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(
            listOf(
                task("a", due = yesterday()),
                task("b", due = yesterday()),
                task("c", due = tomorrow()),
            ),
            emptyList(),
        )
        poll()
        nextPoll()

        assertEquals(listOf("a", "b"), fired.sorted())
    }

    @Test
    fun `the title that fires is the one from the latest reconcile`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", title = "Old name", due = tomorrow())), emptyList())
        poll()
        scheduler.sync(listOf(task("a", title = "New name", due = yesterday())), emptyList())
        nextPoll()

        assertEquals(listOf("New name"), fired)
    }

    // ── The task's own reminders ─────────────────────────────────────────────────

    @Test
    fun `a days-before reminder fires on its own slot, ahead of the due day`() = runTest {
        val scheduler = scheduler()
        // Due tomorrow, reminder yesterday at noon: already past, so the next poll fires it.
        val reminder = Reminder(ReminderTrigger.DaysBeforeDue(2, LocalTime.NOON))

        scheduler.sync(listOf(task("a", due = tomorrow(), reminders = listOf(reminder))), emptyList())
        poll()
        nextPoll()

        assertEquals(listOf("a"), fired)
    }

    @Test
    fun `a fixed-date reminder fires on an undated task`() = runTest {
        val scheduler = scheduler()
        val reminder = Reminder(ReminderTrigger.At(LocalDateTime.now().minusMinutes(1)))

        scheduler.sync(listOf(task("a", due = null, reminders = listOf(reminder))), emptyList())
        poll()
        nextPoll()

        assertEquals(listOf("a"), fired)
    }

    @Test
    fun `editing a reminder moves it to a new slot, the old alarm is gone and the new one fires`() = runTest {
        val scheduler = scheduler()
        val before = Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.NOON))
        val after = Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(13, 0)))

        scheduler.sync(listOf(task("a", due = yesterday(), reminders = listOf(before))), emptyList())
        // Edited while already due: the old slot is no longer planned (cancelled), the new slot
        // is armed at 13:00 — still in the past relative to the poll, so it fires once.
        scheduler.sync(listOf(task("a", due = yesterday(), reminders = listOf(after))), emptyList())
        poll()
        nextPoll()
        nextPoll()

        assertEquals(listOf("a"), fired)
    }

    // ── Lead-time notifications ─────────────────────────────────────────────────────

    @Test
    fun `a due time overdue by more than every configured lead fires once per lead`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(
            listOf(task("a", due = yesterday(), reminders = emptyList(), dueTime = LocalTime.NOON)),
            listOf(20, 10),
        )
        poll()

        assertEquals(listOf("a — 10m", "a — 20m"), fired.sorted())
    }

    @Test
    fun `a task reminder and a due-time lead both fire for the same task, independently`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(
            listOf(task("a", due = yesterday(), dueTime = LocalTime.of(13, 0))),
            listOf(10),
        )
        poll()

        assertEquals(listOf("a", "a — 10m"), fired.sorted())
    }

    @Test
    fun `no lead minutes configured means a due time alone never fires`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(
            listOf(task("a", due = yesterday(), reminders = emptyList(), dueTime = LocalTime.NOON)),
            emptyList(),
        )
        poll()

        assertEquals(emptyList<String>(), fired)
    }

    private companion object {
        /** Mirrors the scheduler's own interval; it is private there and this is a test that has
         *  to step past exactly one of them. */
        const val POLL_INTERVAL_MILLIS = 30_000L
    }
}
