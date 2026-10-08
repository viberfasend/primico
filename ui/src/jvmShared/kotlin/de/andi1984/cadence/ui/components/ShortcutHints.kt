package de.andi1984.cadence.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/**
 * The controls `:ui` draws that a shell may also have bound to a key.
 *
 * `:ui` cannot see the desktop's shortcut table — it lives in `:app-desktop`, next to the window
 * that dispatches from it — so this names the *verbs* a screen offers, and the shell answers which
 * keys reach each one. That keeps the table the only place a binding is written down: a hint that
 * names a key the window does not answer to cannot be drawn, because the hint is read from the
 * same row the dispatcher matches.
 */
enum class HintedAction {
    QuickAdd,
    Search,
    Settings,
    SyncNow,
    OpenTask,
    ToggleTask,
    DeleteTask,
    DueToday,
    DueTomorrow,
    DueNextWeek,
    NoDueDate,
}

/**
 * "Ctrl+F" for an action the shell bound, `null` for one it did not.
 *
 * The default answers `null` for everything, which is Android: a phone has no keyboard to hint
 * at, so every tooltip and key chip below simply is not drawn there — the same "Android leaves it
 * at its default" arrangement [RowInteractions] and [LocalRowSelection] use (ADR 0003, decision 3).
 */
fun interface ShortcutHints {
    fun keysFor(action: HintedAction): String?
}

val LocalShortcutHints = staticCompositionLocalOf { ShortcutHints { null } }

/** The keys bound to [action] in this shell, or `null`. */
@Composable
fun shortcutKeys(action: HintedAction): String? = LocalShortcutHints.current.keysFor(action)

/** [ShortcutTooltip] for a control `:ui` draws, its keys looked up from the shell. */
@Composable
fun ShortcutTooltip(label: String, action: HintedAction, content: @Composable () -> Unit) =
    ShortcutTooltip(label = label, keys = shortcutKeys(action), content = content)

/**
 * A hover tooltip naming a control and the keys that do the same thing — "Search  Ctrl+F".
 *
 * Where [keys] is `null` this draws [content] and nothing else, so a screen can wrap every button
 * unconditionally and Android, which binds nothing, is left exactly as it was. Icon-only header
 * buttons are where this matters most: they carry no visible label at all, and hovering is how a
 * pointer user finds out both what one does and that a key does it faster.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutTooltip(label: String, keys: String?, content: @Composable () -> Unit) {
    if (keys == null) {
        content()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label)
                    Text(
                        text = keys,
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
            }
        },
        state = rememberTooltipState(),
    ) {
        content()
    }
}
