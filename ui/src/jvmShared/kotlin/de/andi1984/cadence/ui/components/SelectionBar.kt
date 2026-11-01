package de.andi1984.cadence.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.data.TaskBatchAction
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.format.title
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The header a task-list screen draws: the normal [ScreenHeader], or — while the multi-select set
 * is non-empty (#40) — [SelectionActionBar] in its place.
 *
 * One wrapper rather than a branch in each of the seven screens: the screens already read
 * [LocalRowSelection] for their rows, and the bar only makes sense where rows do.
 */
@Composable
fun TaskListHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val selection = LocalRowSelection.current
    if (selection.isSelecting) {
        SelectionActionBar(modifier = modifier)
    } else {
        ScreenHeader(title = title, subtitle = subtitle, modifier = modifier, actions = actions)
    }
}

/**
 * The bar that replaces a list's header while rows are selected (#40): how many, and one action
 * per thing the row menu already offers. Every action is one call to
 * [RowInteractions.onBatchAction] — a single repository write and one undo entry for the set —
 * followed by clearing the selection, because a bar that stays up after its rows are gone reads
 * as broken.
 */
@Composable
fun SelectionActionBar(modifier: Modifier = Modifier) {
    val selection = LocalRowSelection.current
    val interactions = LocalRowInteractions.current
    val state = interactions.state
    val ids = selection.selectedIds.toList()
    val count = ids.size

    fun run(action: TaskBatchAction) {
        interactions.onBatchAction(ids, action)
        selection.clearSelection()
    }

    val selectedTasks = ids.mapNotNull { id -> state.tasks.firstOrNull { it.id == id } }
    val allDone = selectedTasks.isNotEmpty() && selectedTasks.all { it.isDone }

    val countLabel: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            text = pluralStringResource(Res.plurals.selection_count, count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // One line, always: given whatever width the buttons leave, a wrapping Text broke
            // "1 Aufgabe ausgewählt" into a column of single letters on a phone.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = textModifier,
        )
    }
    val clearButton: @Composable () -> Unit = {
        IconButton(onClick = { selection.clearSelection() }) {
            Icon(AppIcons.Close, contentDescription = stringResource(Res.string.action_clear_selection))
        }
    }

    // Six action buttons plus the close button are 336dp of 48dp touch targets before the count
    // has a single letter — more than a phone is wide. A narrow window therefore draws two rows:
    // close and count on top, the actions spread across the full width beneath. A window that
    // fits all of it keeps the one-row bar.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= SINGLE_ROW_MIN_WIDTH) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                clearButton()
                countLabel(Modifier.weight(1f))
                SelectionActions(::run, allDone, selectedTasks)
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    clearButton()
                    countLabel(Modifier.weight(1f).padding(start = 4.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    SelectionActions(::run, allDone, selectedTasks)
                }
            }
        }
    }
}

/** Below this the bar splits into two rows; see [SelectionActionBar]. */
private val SINGLE_ROW_MIN_WIDTH = 600.dp

/** The bar's action buttons, in the order the row menu lists them, without the close button. */
@Composable
private fun SelectionActions(
    run: (TaskBatchAction) -> Unit,
    allDone: Boolean,
    selectedTasks: List<Task>,
) {
    val interactions = LocalRowInteractions.current
    val state = interactions.state

    var priorityMenu by remember { mutableStateOf(false) }
    var dueMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }

    val completeLabel = stringResource(
        if (allDone) Res.string.task_mark_not_done else Res.string.task_mark_done,
    )
    IconButton(onClick = { run(if (allDone) TaskBatchAction.Reopen else TaskBatchAction.Complete) }) {
        Icon(AppIcons.Check, contentDescription = completeLabel)
    }

    Box {
        IconButton(onClick = { priorityMenu = true }) {
            Icon(AppIcons.Flag, contentDescription = stringResource(Res.string.task_importance))
        }
        DropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
            Priority.entries.forEach { priority ->
                DropdownMenuItem(
                    text = { Text(priority.title()) },
                    trailingIcon = { Text(priority.shortLabel) },
                    onClick = { priorityMenu = false; run(TaskBatchAction.SetPriority(priority)) },
                )
            }
        }
    }

    Box {
        IconButton(onClick = { dueMenu = true }) {
            Icon(AppIcons.EditCalendar, contentDescription = stringResource(Res.string.menu_due_date))
        }
        DropdownMenu(expanded = dueMenu, onDismissRequest = { dueMenu = false }) {
            val today = interactions.today
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.date_today)) },
                onClick = { dueMenu = false; run(TaskBatchAction.SetDueDate(today)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.date_tomorrow)) },
                onClick = { dueMenu = false; run(TaskBatchAction.SetDueDate(today.plusDays(1))) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.menu_next_week)) },
                onClick = { dueMenu = false; run(TaskBatchAction.SetDueDate(today.plusWeeks(1))) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.task_no_due_date)) },
                onClick = { dueMenu = false; run(TaskBatchAction.SetDueDate(null)) },
            )
        }
    }

    Box {
        IconButton(onClick = { projectMenu = true }) {
            Icon(AppIcons.Folder, contentDescription = stringResource(Res.string.task_move_to_project))
        }
        DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.inbox_title)) },
                leadingIcon = { Icon(AppIcons.Inbox, contentDescription = null) },
                onClick = { projectMenu = false; run(TaskBatchAction.MoveToProject(null)) },
            )
            state.projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text(project.name) },
                    leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) },
                    onClick = { projectMenu = false; run(TaskBatchAction.MoveToProject(project.id)) },
                )
            }
        }
    }

    Box {
        IconButton(onClick = { tagMenu = true }) {
            Icon(AppIcons.Tag, contentDescription = stringResource(Res.string.tags_add_to_task))
        }
        DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
            state.tags.forEach { tag ->
                // A tag every selected row already wears is removed, anything else is added:
                // the one gesture the bar offers for labels is a toggle over the set.
                val allWearIt = selectedTasks.isNotEmpty() && selectedTasks.all { tag.id in it.tagIds }
                DropdownMenuItem(
                    text = { Text("@${tag.name}") },
                    leadingIcon = {
                        if (allWearIt) Icon(AppIcons.Check, contentDescription = null)
                    },
                    onClick = {
                        tagMenu = false
                        run(if (allWearIt) TaskBatchAction.RemoveTag(tag.id) else TaskBatchAction.AddTag(tag.id))
                    },
                )
            }
        }
    }

    val deleteLabel = stringResource(Res.string.action_delete)
    IconButton(onClick = { run(TaskBatchAction.Delete) }) {
        Icon(AppIcons.Delete, contentDescription = deleteLabel)
    }
}
