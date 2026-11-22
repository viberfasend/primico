package de.andi1984.cadence.ui.calendar

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.sortedFor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * Pure model for the month grid calendar view.
 *
 * This file contains no Compose runtime and is fully JVM-testable. It decides:
 * - Which days a grid holds for a month and a locale (first day of week from locale)
 * - Which tasks land in which cell
 * - How tasks are ranked within a cell (by [sortedFor])
 *
 * See ADR 0008 for the design decisions behind this.
 */

/**
 * A day in the calendar grid.
 *
 * [date] is the actual date this cell represents (always non-null).
 * [isInMonth] is true if this cell belongs to the displayed month (false for padding cells).
 * [isToday] is true if this cell represents today's date.
 */
data class CalendarDay(
    /** The date this cell represents (always non-null). */
    val date: LocalDate,
    /** Whether this day is today. */
    val isToday: Boolean,
    /** Whether this day belongs to the displayed month (not a padding day). */
    val isInMonth: Boolean,
    /** The day of week for this cell (1-7, where 1 is the first day of week from locale). */
    val dayOfWeek: Int,
) {
    /** The day number to display (1-31), or null for padding cells. */
    val dayNumber: Int get() = date.dayOfMonth
}

/**
 * A week in the calendar grid, containing 7 [CalendarDay]s.
 */
data class CalendarWeek(val days: List<CalendarDay>) {
    init {
        require(days.size == 7) { "A week must have exactly 7 days" }
    }

    /** Whether this week contains any days from the displayed month. */
    val hasMonthDays: Boolean get() = days.any { it.isInMonth }

    /** Whether this week contains today. */
    val hasToday: Boolean get() = days.any { it.isToday }
}

/**
 * The complete grid for a month, organized into weeks.
 *
 * The grid always has 6 weeks (42 cells) to ensure consistent layout, even for months
 * that span fewer weeks.
 */
data class MonthGrid(
    /** The year and month this grid represents. */
    val yearMonth: YearMonth,
    /** All weeks in this grid (always 6 weeks). */
    val weeks: List<CalendarWeek>,
    /** The first day of week for this locale (1-7, where 1 is the locale's first day). */
    val firstDayOfWeek: DayOfWeek,
    /** The locale used to determine first day of week and month names. */
    val locale: Locale,
) {
    init {
        require(weeks.size == 6) { "A month grid must have exactly 6 weeks" }
    }

    /** The display name of the month (e.g., "January", "Jan"). */
    fun monthDisplayName(style: TextStyle = TextStyle.SHORT): String =
        yearMonth.month.getDisplayName(style, locale)

    /** The display name of the year and month (e.g., "January 2026", "Jan 2026"). */
    fun yearMonthDisplayName(style: TextStyle = TextStyle.SHORT): String =
        "${yearMonth.month.getDisplayName(style, locale)} ${yearMonth.year}"

    /** All days in this grid that belong to the displayed month. */
    val monthDays: List<CalendarDay> get() = weeks.flatMap { it.days }.filter { it.isInMonth }

    /** The date range covered by this grid (from first day to last day, inclusive). */
    val dateRange: ClosedRange<LocalDate> 
        get() {
            val firstDate = weeks.first().days.first().date
            val lastDate = weeks.last().days.last().date
            return firstDate..lastDate
        }
}

/**
 * Tasks grouped by the day they are due on.
 *
 * Each entry maps a date to the tasks due on that date, sorted by [sortedFor].
 * Tasks without a due date are not included.
 */
typealias TasksByDay = Map<LocalDate, List<Task>>

/**
 * Information about tasks for a specific day in the calendar.
 */
data class DayTaskInfo(
    /** The date this info is for. */
    val date: LocalDate,
    /** All tasks due on this date, sorted by [sortedFor]. */
    val tasks: List<Task>,
    /** Number of open (not done) tasks. */
    val openCount: Int,
    /** Number of overdue tasks (tasks due on this date that are before today). */
    val overdueCount: Int,
    /** Up to 3 task titles to display in the cell. */
    val displayTitles: List<String>,
    /** Whether there are more tasks than we can display (more than 3). */
    val hasMore: Boolean,
) {
    companion object {
        private const val MAX_DISPLAY_TITLES = 3

        /**
         * Create task info for a specific day.
         *
         * @param tasks Tasks due on this date, already sorted
         * @param today Today's date
         */
        fun forDay(
            date: LocalDate,
            tasks: List<Task>,
            today: LocalDate,
            maxTitles: Int = MAX_DISPLAY_TITLES,
        ): DayTaskInfo {
            val openCount = tasks.count { !it.isDone }
            val overdueCount = if (date.isBefore(today)) {
                tasks.count { !it.isDone }
            } else {
                0
            }
            val displayTitles = tasks
                .filter { !it.isDone }
                .take(maxTitles)
                .map { it.title }
            val hasMore = tasks.count { !it.isDone } > maxTitles

            return DayTaskInfo(
                date = date,
                tasks = tasks,
                openCount = openCount,
                overdueCount = overdueCount,
                displayTitles = displayTitles,
                hasMore = hasMore,
            )
        }
    }
}

/**
 * Build a month grid for the given year/month and locale.
 *
 * The grid respects the locale's first day of week (e.g., Sunday for US, Monday for most of
 * Europe). The grid always contains 6 weeks to ensure consistent layout.
 *
 * @param yearMonth The year and month to build a grid for
 * @param locale The locale to use for determining first day of week
 * @param today Today's date (used to mark the today cell)
 */
fun buildMonthGrid(
    yearMonth: YearMonth,
    locale: Locale = Locale.getDefault(),
    today: LocalDate = LocalDate.now(),
): MonthGrid {
    // Get first day of week from locale using Calendar
    val calendar = Calendar.getInstance(locale)
    val firstDayOfWeekJava = calendar.firstDayOfWeek
    // Convert java.util.Calendar day of week (1=Sunday, 2=Monday, ...) to java.time.DayOfWeek
    val firstDayOfWeek = when (firstDayOfWeekJava) {
        Calendar.SUNDAY -> DayOfWeek.SUNDAY
        Calendar.MONDAY -> DayOfWeek.MONDAY
        Calendar.TUESDAY -> DayOfWeek.TUESDAY
        Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
        Calendar.THURSDAY -> DayOfWeek.THURSDAY
        Calendar.FRIDAY -> DayOfWeek.FRIDAY
        Calendar.SATURDAY -> DayOfWeek.SATURDAY
        else -> DayOfWeek.MONDAY // Default to Monday
    }
    
    val firstDayOfMonth = yearMonth.atDay(1)
    
    // Calculate the offset from the first day of week
    val dayOfWeekAdjustment = calculateDayOfWeekOffset(firstDayOfMonth, firstDayOfWeek)
    
    // Build all 42 cells (6 weeks x 7 days)
    val cells = mutableListOf<CalendarDay>()
    
    // Start from the first day of the grid (possibly in the previous month)
    val gridStartDate = firstDayOfMonth.minusDays(dayOfWeekAdjustment.toLong())
    
    for (week in 0 until 6) {
        for (dayInWeek in 0 until 7) {
            val cellDate = gridStartDate.plusDays((week * 7 + dayInWeek).toLong())
            val isInMonth = cellDate.monthValue == yearMonth.monthValue && 
                          cellDate.year == yearMonth.year
            val isToday = cellDate == today
            val dayOfWeek = calculateDayOfWeekNumber(cellDate, firstDayOfWeek)
            
            cells.add(
                CalendarDay(
                    date = cellDate,
                    isToday = isToday,
                    isInMonth = isInMonth,
                    dayOfWeek = dayOfWeek,
                )
            )
        }
    }
    
    // Group cells into weeks
    val weeks = cells.chunked(7).map { weekDays ->
        CalendarWeek(weekDays)
    }
    
    return MonthGrid(
        yearMonth = yearMonth,
        weeks = weeks,
        firstDayOfWeek = firstDayOfWeek,
        locale = locale,
    )
}

/**
 * Calculate the offset from the first day of week for a given date.
 *
 * Returns the number of days to subtract from the first day of month to get to the
 * first day of the week that contains the first day of month.
 */
private fun calculateDayOfWeekOffset(date: LocalDate, firstDayOfWeek: DayOfWeek): Int {
    val dateDayOfWeek = date.dayOfWeek
    // Calculate days between firstDayOfWeek and dateDayOfWeek
    // If firstDayOfWeek is Monday (1) and date is Wednesday (3), offset is 2
    // If firstDayOfWeek is Sunday (7) and date is Wednesday (3), offset is 5 (3 - 7 = -4, + 7 = 3, but we want days to go back)
    val offset = (dateDayOfWeek.value - firstDayOfWeek.value + 7) % 7
    return offset
}

/**
 * Calculate the day of week number for a date, where 1 is the first day of week from locale.
 */
private fun calculateDayOfWeekNumber(date: LocalDate, firstDayOfWeek: DayOfWeek): Int {
    val dateDayOfWeek = date.dayOfWeek
    return (dateDayOfWeek.value - firstDayOfWeek.value + 7) % 7 + 1
}

/**
 * Group tasks by their due date.
 *
 * Only tasks with a due date are included. Tasks are sorted by [sortedFor] within each day.
 *
 * @param tasks All tasks to group
 * @param sortMode The sort mode to use for ordering tasks within each day
 */
fun groupTasksByDay(
    tasks: List<Task>,
    sortMode: SortMode = SortMode.IMPORTANCE,
): TasksByDay {
    return tasks
        .filter { it.dueDate != null }
        .groupBy { it.dueDate!! }
        .mapValues { (_, dayTasks) -> dayTasks.sortedFor(sortMode) }
}

/**
 * Get task info for all days in a month grid.
 *
 * @param grid The month grid
 * @param tasksByDay Tasks grouped by day (from [groupTasksByDay])
 * @param today Today's date
 */
fun getDayTaskInfoMap(
    grid: MonthGrid,
    tasksByDay: TasksByDay,
    today: LocalDate = LocalDate.now(),
): Map<LocalDate, DayTaskInfo> {
    val result = mutableMapOf<LocalDate, DayTaskInfo>()
    
    for (week in grid.weeks) {
        for (day in week.days) {
            if (!day.isInMonth) continue
            val date = day.date
            val tasks = tasksByDay[date] ?: emptyList()
            result[date] = DayTaskInfo.forDay(date, tasks, today)
        }
    }
    
    return result
}

/**
 * Get task info for a specific calendar day.
 */
fun getDayTaskInfo(
    day: CalendarDay,
    tasksByDay: TasksByDay,
    today: LocalDate = LocalDate.now(),
): DayTaskInfo? {
    val date = day.date
    // Only return info for days that are in the displayed month
    if (!day.isInMonth) return null
    val tasks = tasksByDay[date] ?: emptyList()
    return DayTaskInfo.forDay(date, tasks, today)
}

/**
 * Navigate to the next month.
 */
fun MonthGrid.nextMonth(): MonthGrid = buildMonthGrid(
    yearMonth = yearMonth.plusMonths(1),
    locale = locale,
)

/**
 * Navigate to the previous month.
 */
fun MonthGrid.previousMonth(): MonthGrid = buildMonthGrid(
    yearMonth = yearMonth.minusMonths(1),
    locale = locale,
)

/**
 * Navigate to today's month.
 */
fun MonthGrid.todayMonth(today: LocalDate = LocalDate.now()): MonthGrid = buildMonthGrid(
    yearMonth = YearMonth.from(today),
    locale = locale,
    today = today,
)

/**
 * Check if this grid is for the current month (the month containing today).
 */
fun MonthGrid.isCurrentMonth(today: LocalDate = LocalDate.now()): Boolean {
    val currentMonth = YearMonth.from(today)
    return yearMonth == currentMonth
}
