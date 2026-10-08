package de.andi1984.cadence.ui

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.settings.CadenceSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [dayProgress] — the count the Today widget's progress bar and the progress widget draw.
 *
 * The cases worth pinning are the two ways a count read off the Today *list* went wrong: a
 * finished task leaving the list with `showCompleted` off, and a late task leaving it the moment
 * it was ticked off.
 */
class DayProgressTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 8)

    private fun at(day: LocalDate): Instant = day.atTime(12, 0).toInstant(ZoneOffset.UTC)

    private fun task(id: String, due: LocalDate?, completedOn: LocalDate? = null) = Task(
        id = id,
        title = id,
        dueDate = due,
        completedAt = completedOn?.let(::at),
    )

    private fun progress(vararg tasks: Task, showCompleted: Boolean = true) =
        CadenceUiState(tasks = tasks.toList(), settings = CadenceSettings(showCompleted = showCompleted))
            .dayProgress(today, zone)

    @Test
    fun `counts finished work even with showCompleted off`() {
        val result = progress(
            task("done", due = today, completedOn = today),
            task("open", due = today),
            showCompleted = false,
        )
        assertEquals(DayProgress(done = 1, open = 1, overdue = 0), result)
        assertEquals(0.5f, result.fraction)
    }

    @Test
    fun `a late task ticked off today advances the day instead of shrinking it`() {
        val yesterday = today.minusDays(1)
        assertEquals(
            DayProgress(done = 1, open = 1, overdue = 1),
            progress(
                task("late-done", due = yesterday, completedOn = today),
                task("late-open", due = yesterday),
            ),
        )
    }

    @Test
    fun `work finished on an earlier day, undated or due later is not on today's plate`() {
        val yesterday = today.minusDays(1)
        assertEquals(
            DayProgress(done = 0, open = 0, overdue = 0),
            progress(
                task("old", due = yesterday, completedOn = yesterday),
                task("undated", due = null),
                task("undated-done", due = null, completedOn = today),
                task("early", due = today.plusDays(3), completedOn = today),
                task("later", due = today.plusDays(1)),
            ),
        )
    }

    @Test
    fun `a task due today but finished ahead of time still counts as done today`() {
        assertEquals(
            DayProgress(done = 1, open = 0, overdue = 0),
            progress(task("early-bird", due = today, completedOn = today.minusDays(2))),
        )
    }

    @Test
    fun `an empty day has no fraction to speak of`() {
        val empty = progress()
        assertEquals(0, empty.total)
        assertEquals(0f, empty.fraction)
    }
}
