package de.andi1984.cadence.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.Reminder
import de.andi1984.cadence.domain.reminder.ReminderRepeat
import de.andi1984.cadence.domain.reminder.ReminderTrigger
import de.andi1984.cadence.ui.components.CadenceDatePickerDialog
import de.andi1984.cadence.ui.components.CadenceTimePickerDialog
import de.andi1984.cadence.ui.components.SegmentedRow
import de.andi1984.cadence.ui.format.formatTime
import de.andi1984.cadence.ui.format.relativeDate
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.LocalTime

/** The trigger kinds the editor offers, in display order. */
private enum class EditorKind { BEFORE_DUE_TIME, DAYS_BEFORE_DUE, AT }

private val DAY_PRESETS = listOf(0, 1, 2, 7)
private val MINUTE_PRESETS = listOf(5, 15, 30, 60)
private val DEFAULT_AT = LocalTime.of(9, 0)

/**
 * The editor behind "Add reminder" on the task detail: one [Reminder] at a time.
 *
 * Which kinds are offered depends on the task, which is also what fixes the dead-reminder bug of
 * the old single `reminderTime` row — an undated task accepted a time that could never fire:
 *  - "Before the due time" only when the task has a due *time*;
 *  - "Days before the due date" only when it has a due *date*;
 *  - "On a date" always — a fixed date-time works on an undated task.
 *
 * Presets cover the common shapes (same day / 1 / 2 / 7 days before at a chosen time, 5 / 15 / 30 /
 * 60 minutes before); the time and the fixed date are picked with the same pickers the due date
 * and time use. Repeat (nag) lets a reminder re-fire every N minutes until done or a set count.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderEditorDialog(
    task: Task,
    initial: Reminder?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Reminder) -> Unit,
) {
    val kinds = buildList {
        if (task.dueTime != null) add(EditorKind.BEFORE_DUE_TIME)
        if (task.dueDate != null) add(EditorKind.DAYS_BEFORE_DUE)
        add(EditorKind.AT)
    }
    var kind by remember(initial) {
        mutableStateOf(
            when (initial?.trigger) {
                is ReminderTrigger.BeforeDueTime -> EditorKind.BEFORE_DUE_TIME
                is ReminderTrigger.DaysBeforeDue -> EditorKind.DAYS_BEFORE_DUE
                is ReminderTrigger.At -> EditorKind.AT
                null -> kinds.first()
            }.let { if (it in kinds) it else kinds.first() },
        )
    }
    var minutes by remember(initial) {
        mutableStateOf((initial?.trigger as? ReminderTrigger.BeforeDueTime)?.minutes ?: 30)
    }
    var days by remember(initial) {
        mutableStateOf((initial?.trigger as? ReminderTrigger.DaysBeforeDue)?.days ?: 1)
    }
    var at by remember(initial) {
        mutableStateOf((initial?.trigger as? ReminderTrigger.DaysBeforeDue)?.at ?: DEFAULT_AT)
    }
    var date by remember(initial) {
        mutableStateOf(
            (initial?.trigger as? ReminderTrigger.At)?.dateTime?.toLocalDate()
                ?: (task.dueDate ?: today),
        )
    }
    var time by remember(initial) {
        mutableStateOf((initial?.trigger as? ReminderTrigger.At)?.dateTime?.toLocalTime() ?: DEFAULT_AT)
    }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    // Repeat (nag) state
    var repeatEvery by remember(initial) {
        mutableStateOf(initial?.repeat?.everyMinutes ?: 0)
    }
    var repeatCount by remember(initial) {
        mutableStateOf(initial?.repeat?.count)
    }

    val kindLabels = kinds.map { k ->
        when (k) {
            EditorKind.BEFORE_DUE_TIME -> stringResource(Res.string.reminder_kind_before_due_time)
            EditorKind.DAYS_BEFORE_DUE -> stringResource(Res.string.reminder_kind_days_before_due)
            EditorKind.AT -> stringResource(Res.string.reminder_kind_at)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.reminder_editor_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.reminder_editor_kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SegmentedRow(
                    options = kindLabels,
                    selectedIndex = kinds.indexOf(kind),
                    onSelect = { kind = kinds[it] },
                )
                when (kind) {
                    EditorKind.BEFORE_DUE_TIME -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MINUTE_PRESETS.forEach { preset ->
                                FilterChip(
                                    selected = minutes == preset,
                                    onClick = { minutes = preset },
                                    label = { Text(stringResource(Res.string.reminder_preset_minutes, preset)) },
                                )
                            }
                        }
                    }
                    EditorKind.DAYS_BEFORE_DUE -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DAY_PRESETS.forEach { preset ->
                                val label = when (preset) {
                                    0 -> stringResource(Res.string.reminder_preset_same_day)
                                    1 -> stringResource(Res.string.reminder_preset_1_day)
                                    2 -> stringResource(Res.string.reminder_preset_2_days)
                                    else -> stringResource(Res.string.reminder_preset_1_week)
                                }
                                FilterChip(
                                    selected = days == preset,
                                    onClick = { days = preset },
                                    label = { Text("$label ${stringResource(Res.string.reminder_preset_time_at, formatTime(at))}") },
                                )
                            }
                        }
                        TextButton(onClick = { timePickerOpen = true }) {
                            Text(stringResource(Res.string.reminder_editor_time) + ": " + formatTime(at))
                        }
                    }
                    EditorKind.AT -> {
                        TextButton(onClick = { datePickerOpen = true }) {
                            Text(stringResource(Res.string.reminder_editor_date) + ": " + relativeDate(date, today))
                        }
                        TextButton(onClick = { timePickerOpen = true }) {
                            Text(stringResource(Res.string.reminder_editor_time) + ": " + formatTime(time))
                        }
                    }
                }
                // Repeat (nag) controls
                Text(
                    text = stringResource(Res.string.reminder_repeat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Off
                    FilterChip(
                        selected = repeatEvery == 0,
                        onClick = { 
                            repeatEvery = 0
                            repeatCount = null
                        },
                        label = { Text(stringResource(Res.string.reminder_no_repeat)) },
                    )
                    // Every 5 minutes
                    FilterChip(
                        selected = repeatEvery == 5,
                        onClick = { 
                            repeatEvery = 5
                            if (repeatCount == null) repeatCount = null // Keep as until done
                        },
                        label = { Text(stringResource(Res.string.reminder_repeat_every_5_min)) },
                    )
                    // Every 10 minutes
                    FilterChip(
                        selected = repeatEvery == 10,
                        onClick = { 
                            repeatEvery = 10
                            if (repeatCount == null) repeatCount = null
                        },
                        label = { Text(stringResource(Res.string.reminder_repeat_every_10_min)) },
                    )
                    // Every 30 minutes
                    FilterChip(
                        selected = repeatEvery == 30,
                        onClick = { 
                            repeatEvery = 30
                            if (repeatCount == null) repeatCount = null
                        },
                        label = { Text(stringResource(Res.string.reminder_repeat_every_30_min)) },
                    )
                }
                if (repeatEvery > 0) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Until done
                        FilterChip(
                            selected = repeatCount == null,
                            onClick = { repeatCount = null },
                            label = { Text(stringResource(Res.string.reminder_repeat_until_done_short)) },
                        )
                        // 3 times
                        FilterChip(
                            selected = repeatCount == 3,
                            onClick = { repeatCount = 3 },
                            label = { Text(pluralStringResource(Res.plurals.reminder_repeat_times_short, 3, 3)) },
                        )
                        // 5 times
                        FilterChip(
                            selected = repeatCount == 5,
                            onClick = { repeatCount = 5 },
                            label = { Text(pluralStringResource(Res.plurals.reminder_repeat_times_short, 5, 5)) },
                        )
                        // 10 times
                        FilterChip(
                            selected = repeatCount == 10,
                            onClick = { repeatCount = 10 },
                            label = { Text(pluralStringResource(Res.plurals.reminder_repeat_times_short, 10, 10)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trigger = when (kind) {
                        EditorKind.BEFORE_DUE_TIME -> ReminderTrigger.BeforeDueTime(minutes)
                        EditorKind.DAYS_BEFORE_DUE -> ReminderTrigger.DaysBeforeDue(days, at)
                        EditorKind.AT -> ReminderTrigger.At(date.atTime(time))
                    }
                    val repeat = if (repeatEvery > 0) {
                        ReminderRepeat(repeatEvery, repeatCount)
                    } else {
                        null
                    }
                    onSave(Reminder(trigger, repeat, initial?.acknowledgedAt))
                },
            ) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )

    if (datePickerOpen) {
        CadenceDatePickerDialog(
            initial = date,
            onDismiss = { datePickerOpen = false },
            onPick = { picked -> if (picked != null) date = picked },
        )
    }
    if (timePickerOpen) {
        CadenceTimePickerDialog(
            initial = if (kind == EditorKind.AT) time else at,
            title = stringResource(Res.string.reminder_editor_time),
            onDismiss = { timePickerOpen = false },
            onPick = { picked ->
                if (picked != null) {
                    if (kind == EditorKind.AT) time = picked else at = picked
                }
            },
        )
    }
}
