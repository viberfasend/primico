package de.andi1984.cadence.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.HintedAction
import de.andi1984.cadence.ui.components.ShortcutTooltip
import de.andi1984.cadence.ui.components.SyncControls
import de.andi1984.cadence.ui.components.TaskListHeader
import de.andi1984.cadence.ui.components.SyncRefreshBox
import de.andi1984.cadence.ui.dnd.DropTarget
import de.andi1984.cadence.ui.dnd.cadenceDropTarget
import de.andi1984.cadence.ui.dnd.dropHighlight
import de.andi1984.cadence.ui.format.pluralTasks
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.action_back
import de.andi1984.cadence.ui.resources.action_search
import de.andi1984.cadence.ui.resources.action_settings
import de.andi1984.cadence.ui.resources.shortcut_forward
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.theme.CadenceColors
import de.andi1984.cadence.ui.theme.CadenceDensity
import de.andi1984.cadence.ui.theme.CompactDensity
import de.andi1984.cadence.ui.theme.LocalCadenceColors
import de.andi1984.cadence.ui.theme.LocalCadenceDensity
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The month grid calendar screen.
 *
 * Shows a grid of days for a month, with:
 * - Day number
 * - Open count (number of tasks due on that day)
 * - Overdue accent from LocalCadenceColors for days in the past with open tasks
 * - Up to three task titles
 * - Density-aware via LocalCadenceDensity (compact vs comfortable)
 * - Today marked
 * - Month switchable (previous/next buttons)
 * - Tapping a day with tasks opens that day's list
 * - Day cell is a drop target (desktop only) - dropping a task sets its due date
 *
 * See ADR 0008 for the design decisions.
 */
@Composable
fun MonthGridScreen(
    state: CadenceUiState,
    today: LocalDate,
    onTaskClick: (Task) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onToggle: (Task) -> Unit,
    onReschedule: (List<String>, LocalDate) -> Unit,
    onSortChange: (SortMode) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    syncControls: SyncControls = SyncControls(),
) {
    val sortMode = state.settings.sortMode
    val density = LocalCadenceDensity.current
    val cadenceColors = LocalCadenceColors.current
    
    // Current month being displayed
    var currentMonth by remember { mutableStateOf(YearMonth.from(today)) }
    
    // Build the grid for the current month
    val grid = remember(currentMonth) {
        buildMonthGrid(
            yearMonth = currentMonth,
            locale = Locale.getDefault(),
            today = today,
        )
    }
    
    // Group tasks by day
    val tasksByDay = remember(state.tasks, sortMode) {
        groupTasksByDay(state.tasks, sortMode)
    }
    
    // Get task info for each day in the grid
    val dayTaskInfoMap = remember(grid, tasksByDay, today) {
        getDayTaskInfoMap(grid, tasksByDay, today)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TaskListHeader(
            title = grid.yearMonthDisplayName(),
            subtitle = pluralTasks(grid.monthDays.count { day ->
                val info = dayTaskInfoMap[day.date]
                info != null && info.openCount > 0
            }),
        ) {
            // Previous month button
            val backLabel = stringResource(Res.string.action_back)
            IconButton(onClick = {
                currentMonth = currentMonth.minusMonths(1)
                onPreviousMonth()
            }) {
                Icon(AppIcons.ArrowBack, contentDescription = backLabel)
            }
            
            // Next month button
            val forwardLabel = stringResource(Res.string.shortcut_forward)
            IconButton(onClick = {
                currentMonth = currentMonth.plusMonths(1)
                onNextMonth()
            }) {
                Icon(AppIcons.ChevronRight, contentDescription = forwardLabel)
            }
            
            // Search button
            val searchLabel = stringResource(Res.string.action_search)
            ShortcutTooltip(searchLabel, HintedAction.Search) {
                IconButton(onClick = onSearch) {
                    Icon(AppIcons.Search, contentDescription = searchLabel)
                }
            }
            
            // Settings button
            val settingsLabel = stringResource(Res.string.action_settings)
            IconButton(onClick = onSettings) {
                Icon(AppIcons.Settings, contentDescription = settingsLabel)
            }
        }

        SyncRefreshBox(status = state.sync.status, controls = syncControls) {
            // Weekday header row
            WeekdayHeader(grid = grid)
            
            // Month grid
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 4.dp,
                    bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(grid.weeks) { week ->
                    if (week.hasMonthDays) {
                        WeekRow(
                            week = week,
                            dayTaskInfoMap = dayTaskInfoMap,
                            today = today,
                            onDayClick = onDayClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The weekday header row showing the abbreviated day names.
 */
@Composable
private fun WeekdayHeader(grid: MonthGrid) {
    val firstDayOfWeek = grid.firstDayOfWeek
    val locale = grid.locale
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (i in 0 until 7) {
            val dayOfWeek = firstDayOfWeek.plus(i.toLong())
            val dayName = dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
            
            Text(
                text = dayName,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * A row of day cells for one week.
 */
@Composable
private fun WeekRow(
    week: CalendarWeek,
    dayTaskInfoMap: Map<LocalDate, DayTaskInfo>,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (day in week.days) {
            DayCell(
                day = day,
                taskInfo = dayTaskInfoMap[day.date],
                today = today,
                modifier = Modifier.weight(1f),
                onDayClick = onDayClick,
            )
        }
    }
}

/**
 * A single day cell in the calendar grid.
 */
@Composable
private fun DayCell(
    day: CalendarDay,
    taskInfo: DayTaskInfo?,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate) -> Unit,
) {
    val cadenceColors = LocalCadenceColors.current
    val density = LocalCadenceDensity.current
    
    val date = day.date
    val isToday = day.isToday
    val isInMonth = day.isInMonth
    val hasTasks = taskInfo?.openCount ?: 0 > 0
    val isOverdue = date.isBefore(today) && taskInfo?.overdueCount ?: 0 > 0
    
    // Cell content
    val cellContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Day number
            val dayTextColor = when {
                !isInMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                isToday -> MaterialTheme.colorScheme.onPrimary
                isOverdue -> cadenceColors.onOverdue
                else -> MaterialTheme.colorScheme.onSurface
            }
            
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = dayTextColor,
            )
            
            // Task count and titles
            if (taskInfo != null && taskInfo.openCount > 0) {
                val showCompact = density == CompactDensity
                
                if (showCompact) {
                    // Compact mode: just show the count
                    val countColor = if (isOverdue) {
                        cadenceColors.overdueAccent
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(countColor)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = taskInfo.openCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                } else {
                    // Comfortable mode: show up to 3 titles
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (taskInfo.displayTitles.isNotEmpty()) {
                            for ((index, title) in taskInfo.displayTitles.withIndex()) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isOverdue) {
                                        cadenceColors.overdueAccent
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                            
                            if (taskInfo.hasMore) {
                                Text(
                                    text = "+${taskInfo.openCount - taskInfo.displayTitles.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Cell background and border
    val backgroundColor = when {
        !isInMonth -> Color.Transparent
        isToday -> MaterialTheme.colorScheme.primary
        isOverdue -> cadenceColors.overdueRow
        else -> Color.Transparent
    }
    
    val borderColor = when {
        isToday -> MaterialTheme.colorScheme.primary
        hasTasks && !isToday -> MaterialTheme.colorScheme.outlineVariant
        else -> Color.Transparent
    }
    
    // Drop target for desktop (no-op on Android)
    val dropKey = "day:${date.toString()}"
    val dropTarget = DropTarget.OntoDate(date)
    
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(width = if (isToday) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .cadenceDropTarget(dropKey, dropTarget)
            .clickable { onDayClick(date) },
        contentAlignment = Alignment.Center,
    ) {
        // Apply drop highlight
        val highlightModifier = Modifier.dropHighlight(dropKey, shape = RoundedCornerShape(8.dp))
        Box(modifier = highlightModifier) {
            cellContent()
        }
    }
}
