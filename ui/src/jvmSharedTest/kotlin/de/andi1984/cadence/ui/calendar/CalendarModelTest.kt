package de.andi1984.cadence.ui.calendar

import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.settings.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure unit tests for [CalendarModel]. No Compose runtime, no Android dependencies.
 *
 * Tests cover:
 * - Grid construction for various months and locales
 * - First day of week from locale
 * - Task grouping by day
 * - Day task info computation
 */
class CalendarModelTest {

    private val today = LocalDate.of(2026, 11, 22) // A Sunday

    // ── Grid construction ────────────────────────────────────────────────────────────

    @Test
    fun `grid has exactly 6 weeks`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        assertEquals(6, grid.weeks.size)
    }

    @Test
    fun `each week has exactly 7 days`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        for (week in grid.weeks) {
            assertEquals(7, week.days.size)
        }
    }

    @Test
    fun `November 2026 starts on a Sunday in US locale`() {
        // November 1, 2026 is a Sunday
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        
        // First day of week for US is Sunday
        assertEquals(DayOfWeek.SUNDAY, grid.firstDayOfWeek)
        
        // First cell of first week should be November 1 (Sunday)
        val firstDay = grid.weeks[0].days[0]
        assertEquals(1, firstDay.dayNumber)
        assertTrue(firstDay.isInMonth)
        assertEquals(LocalDate.of(2026, 11, 1), firstDay.date)
    }

    @Test
    fun `November 2026 starts on a Monday in German locale`() {
        // November 1, 2026 is a Sunday, but German locale has Monday as first day of week
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.GERMANY, today)
        
        // First day of week for Germany is Monday
        assertEquals(DayOfWeek.MONDAY, grid.firstDayOfWeek)
        
        // First cell should be October 26 (Monday of the week containing Nov 1)
        val firstDay = grid.weeks[0].days[0]
        assertEquals(LocalDate.of(2026, 10, 26), firstDay.date)
        assertFalse(firstDay.isInMonth)
        
        // Sunday (Nov 1) should be in position 6 (0-indexed) = day 7 in 1-indexed
        val sundayCell = grid.weeks[0].days[6]
        assertEquals(1, sundayCell.dayNumber)
        assertTrue(sundayCell.isInMonth)
    }

    @Test
    fun `February 2026 has 28 days and fits in grid`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 2), Locale.US, today)
        
        // Count days that belong to February
        val febDays = grid.monthDays
        assertEquals(28, febDays.size)
        
        // All should be in month
        assertTrue(febDays.all { it.isInMonth })
    }

    @Test
    fun `month with 31 days still has only 31 days in month`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 1), Locale.US, today)
        
        val janDays = grid.monthDays
        assertEquals(31, janDays.size)
        assertTrue(janDays.all { it.isInMonth })
    }

    @Test
    fun `today is marked correctly`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        
        // Find November 22, 2026 (a Sunday)
        // In US locale with Sunday as first day, it should be at [0][0] (first week, first day)
        // But wait, Nov 1 is Sunday, so Nov 22 is the 4th Sunday = week 3, day 0
        val weekIndex = 3
        val dayIndex = 0
        val todayCell = grid.weeks[weekIndex].days[dayIndex]
        
        assertEquals(22, todayCell.dayNumber)
        assertTrue(todayCell.isToday)
    }

    @Test
    fun `padding days have actual dates but are not in month`() {
        // January 2026 starts on a Thursday in US locale (Sunday first day)
        // So the first 3 days of the first week are padding (Dec 28, 29, 30, 31)
        val grid = buildMonthGrid(YearMonth.of(2026, 1), Locale.US, today)
        
        val firstWeek = grid.weeks[0]
        // Jan 1, 2026 is a Thursday, so in US locale (Sunday first) it's day 4 (0-indexed)
        // Days 0, 1, 2, 3 should be padding from December
        for (i in 0 until 4) {
            val day = firstWeek.days[i]
            assertFalse("Day $i should not be in month", day.isInMonth)
            assertNotNull("Day $i should have actual date", day.date)
            assertEquals("Day $i should be in December 2025", 12, day.date.monthValue)
        }
        
        // Day 4 should be Jan 1
        assertTrue(firstWeek.days[4].isInMonth)
        assertEquals(1, firstWeek.days[4].dayNumber)
    }

    @Test
    fun `first day of week from locale is respected`() {
        // Test with Israeli locale which has Sunday as first day
        val israelLocale = Locale("he", "IL")
        val grid = buildMonthGrid(YearMonth.of(2026, 11), israelLocale, today)
        
        assertEquals(DayOfWeek.SUNDAY, grid.firstDayOfWeek)
    }

    // ── Month display names ──────────────────────────────────────────────────────────

    @Test
    fun `month display name in English`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        assertEquals("Nov", grid.monthDisplayName(TextStyle.SHORT))
        assertEquals("November", grid.monthDisplayName(TextStyle.FULL))
    }

    @Test
    fun `month display name in German`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.GERMANY, today)
        assertEquals("Nov.", grid.monthDisplayName(TextStyle.SHORT))
        assertEquals("November", grid.monthDisplayName(TextStyle.FULL))
    }

    @Test
    fun `year month display name`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        assertEquals("Nov 2026", grid.yearMonthDisplayName(TextStyle.SHORT))
    }

    // ── Navigation ──────────────────────────────────────────────────────────────────

    @Test
    fun `next month from November is December`() {
        val novGrid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        val decGrid = novGrid.nextMonth()
        
        assertEquals(YearMonth.of(2026, 12), decGrid.yearMonth)
    }

    @Test
    fun `previous month from January is December of previous year`() {
        val janGrid = buildMonthGrid(YearMonth.of(2026, 1), Locale.US, today)
        val decGrid = janGrid.previousMonth()
        
        assertEquals(YearMonth.of(2025, 12), decGrid.yearMonth)
    }

    @Test
    fun `today month returns current month`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        val todayGrid = grid.todayMonth(today)
        
        assertEquals(YearMonth.from(today), todayGrid.yearMonth)
        assertTrue(todayGrid.isCurrentMonth(today))
    }

    @Test
    fun `isCurrentMonth returns false for different month`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 10), Locale.US, today)
        assertFalse(grid.isCurrentMonth(today))
    }

    // ── Task grouping ───────────────────────────────────────────────────────────────

    @Test
    fun `tasks are grouped by due date`() {
        val tasks = listOf(
            task("t1", due = LocalDate.of(2026, 11, 1)),
            task("t2", due = LocalDate.of(2026, 11, 1)),
            task("t3", due = LocalDate.of(2026, 11, 2)),
            task("t4", due = null), // No due date, should be filtered out
        )
        
        val byDay = groupTasksByDay(tasks, SortMode.IMPORTANCE)
        
        assertEquals(2, byDay.size)
        assertEquals(2, byDay[LocalDate.of(2026, 11, 1)]?.size)
        assertEquals(1, byDay[LocalDate.of(2026, 11, 2)]?.size)
        assertNull(byDay[LocalDate.of(2026, 11, 3)])
    }

    @Test
    fun `tasks without due date are excluded from grouping`() {
        val tasks = listOf(
            task("t1", due = null),
            task("t2", due = null),
        )
        
        val byDay = groupTasksByDay(tasks, SortMode.IMPORTANCE)
        
        assertTrue(byDay.isEmpty())
    }

    @Test
    fun `tasks are sorted within each day`() {
        val tasks = listOf(
            task("low", due = LocalDate.of(2026, 11, 1), priority = Priority.P4),
            task("high", due = LocalDate.of(2026, 11, 1), priority = Priority.P1),
            task("med", due = LocalDate.of(2026, 11, 1), priority = Priority.P2),
        )
        
        val byDay = groupTasksByDay(tasks, SortMode.IMPORTANCE)
        val nov1Tasks = byDay[LocalDate.of(2026, 11, 1)]!!
        
        // Should be sorted by priority (importance first)
        assertEquals("high", nov1Tasks[0].id)
        assertEquals("med", nov1Tasks[1].id)
        assertEquals("low", nov1Tasks[2].id)
    }

    // ── Day task info ───────────────────────────────────────────────────────────────

    @Test
    fun `day task info for day with no tasks`() {
        val tasksByDay = emptyMap<LocalDate, List<Task>>()
        val date = LocalDate.of(2026, 11, 15)
        
        val info = DayTaskInfo.forDay(date, emptyList(), today)
        
        assertEquals(date, info.date)
        assertEquals(0, info.openCount)
        assertEquals(0, info.overdueCount)
        assertTrue(info.displayTitles.isEmpty())
        assertFalse(info.hasMore)
    }

    @Test
    fun `day task info counts open tasks correctly`() {
        val date = LocalDate.of(2026, 11, 15)
        val tasks = listOf(
            task("open1", due = date, isDone = false),
            task("open2", due = date, isDone = false),
            task("done1", due = date, isDone = true),
        )
        
        val info = DayTaskInfo.forDay(date, tasks, today)
        
        assertEquals(2, info.openCount)
    }

    @Test
    fun `day task info counts overdue tasks when date is before today`() {
        val date = LocalDate.of(2026, 11, 15) // Before today (2026-11-22)
        val tasks = listOf(
            task("open1", due = date, isDone = false),
            task("open2", due = date, isDone = false),
            task("done1", due = date, isDone = true),
        )
        
        val info = DayTaskInfo.forDay(date, tasks, today)
        
        assertEquals(2, info.overdueCount)
    }

    @Test
    fun `day task info does not count overdue tasks when date is today or after`() {
        val date = LocalDate.of(2026, 11, 22) // Today
        val tasks = listOf(
            task("open1", due = date, isDone = false),
            task("open2", due = date, isDone = false),
        )
        
        val info = DayTaskInfo.forDay(date, tasks, today)
        
        assertEquals(0, info.overdueCount)
    }

    @Test
    fun `day task info returns up to 3 titles`() {
        val date = LocalDate.of(2026, 11, 15)
        val tasks = listOf(
            task("Task 1", due = date, isDone = false),
            task("Task 2", due = date, isDone = false),
            task("Task 3", due = date, isDone = false),
            task("Task 4", due = date, isDone = false),
            task("Task 5", due = date, isDone = false),
        )
        
        val info = DayTaskInfo.forDay(date, tasks, today)
        
        assertEquals(3, info.displayTitles.size)
        assertEquals("Task 1", info.displayTitles[0])
        assertEquals("Task 2", info.displayTitles[1])
        assertEquals("Task 3", info.displayTitles[2])
        assertTrue(info.hasMore)
    }

    @Test
    fun `day task info does not show more than available tasks`() {
        val date = LocalDate.of(2026, 11, 15)
        val tasks = listOf(
            task("Task 1", due = date, isDone = false),
            task("Task 2", due = date, isDone = false),
        )
        
        val info = DayTaskInfo.forDay(date, tasks, today)
        
        assertEquals(2, info.displayTitles.size)
        assertFalse(info.hasMore)
    }

    @Test
    fun `day task info excludes done tasks from display`() {
        val date = LocalDate.of(2026, 11, 15)
        val tasks = listOf(
            task("Done 1", due = date, isDone = true),
            task("Done 2", due = date, isDone = true),
            task("Open 1", due = date, isDone = false),
        )
        
        val info = DayTaskInfo.forDay(date, tasks, today)
        
        assertEquals(1, info.displayTitles.size)
        assertEquals("Open 1", info.displayTitles[0])
    }

    // ── getDayTaskInfoMap ────────────────────────────────────────────────────────────

    @Test
    fun `getDayTaskInfoMap returns info for all days in grid`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        val tasksByDay = groupTasksByDay(
            listOf(
                task("t1", due = LocalDate.of(2026, 11, 5)),
                task("t2", due = LocalDate.of(2026, 11, 10)),
            ),
            SortMode.IMPORTANCE
        )
        
        val infoMap = getDayTaskInfoMap(grid, tasksByDay, today)
        
        // Should have info for all days that are in the month
        assertEquals(grid.monthDays.size, infoMap.size)
        
        // Check specific dates
        assertNotNull(infoMap[LocalDate.of(2026, 11, 5)])
        assertNotNull(infoMap[LocalDate.of(2026, 11, 10)])
    }

    // ── getDayTaskInfo ─────────────────────────────────────────────────────────────

    @Test
    fun `getDayTaskInfo returns null for padding day`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 1), Locale.US, today)
        val tasksByDay = emptyMap<LocalDate, List<Task>>()
        
        // First few days of first week are padding
        val paddingDay = grid.weeks[0].days[0]
        assertNotNull(paddingDay.date)
        assertFalse(paddingDay.isInMonth)
        
        val info = getDayTaskInfo(paddingDay, tasksByDay, today)
        assertNull(info)
    }

    @Test
    fun `getDayTaskInfo returns info for valid day`() {
        val grid = buildMonthGrid(YearMonth.of(2026, 11), Locale.US, today)
        val tasksByDay = groupTasksByDay(
            listOf(task("t1", due = LocalDate.of(2026, 11, 5))),
            SortMode.IMPORTANCE
        )
        
        val day = grid.weeks.flatMap { it.days }.first { it.dayNumber == 5 }
        assertNotNull(day.date)
        
        val info = getDayTaskInfo(day, tasksByDay, today)
        assertNotNull(info)
        assertEquals(LocalDate.of(2026, 11, 5), info!!.date)
        assertEquals(1, info!!.openCount)
    }

    // ── Helper ─────────────────────────────────────────────────────────────────────

    private fun task(
        id: String,
        due: LocalDate? = null,
        isDone: Boolean = false,
        priority: Priority = Priority.P3,
        title: String? = null,
    ): Task = Task(
        id = id,
        title = title ?: id,
        dueDate = due,
        completedAt = if (isDone) Instant.now() else null,
        priority = priority,
    )
}
