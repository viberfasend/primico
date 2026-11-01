package de.andi1984.cadence.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * Which row the keyboard is on.
 *
 * A desktop application can be driven without the pointer, and "the selected task" is the noun
 * every one-key action needs: `x` completes *it*, `1` prioritises *it*, `t` dates *it*. Nothing
 * in `:ui` knows what a "list" is — seven screens draw rows in their own arrangements — so the
 * selection is not an index into anything. Rows **register where they are on screen** and the
 * order is read off their vertical positions, which is the order the user sees and the only one
 * that stays right across bands, headers and an expanded checklist.
 *
 * The same shape as the drag kernel's zone registry next door, and for the same reason.
 */
@Stable
class RowSelectionState {

    var selectedId: String? by mutableStateOf(null)
        private set

    /**
     * The multi-select set, beside the cursor (#40). Non-empty means the list is in selection
     * mode and the action bar is up; the cursor [selectedId] stays the row `j`/`k` move and the
     * row a plain click opens, so the two never fight.
     */
    var selectedIds: Set<String> by mutableStateOf(emptySet())
        private set

    /** Whether the list is in selection mode — the action bar replaces the header while this is on. */
    val isSelecting: Boolean get() = selectedIds.isNotEmpty()

    /** Row id to its top edge in root coordinates. */
    private val rows = mutableStateMapOf<String, Float>()

    fun isSelected(id: String): Boolean = selectedId == id

    fun isMultiSelected(id: String): Boolean = id in selectedIds

    internal fun register(id: String, top: Float) {
        rows[id] = top
    }

    internal fun unregister(id: String) {
        rows.remove(id)
        // A row that scrolled away or was deleted must not stay selected: the next `x` would act
        // on something nobody can see.
        if (selectedId == id) selectedId = null
        if (id in selectedIds) selectedIds = selectedIds - id
    }

    /**
     * A plain click: the cursor moves, and unless the click landed on a row that is already in
     * the set, the set is cleared — one row acted on is the cursor's business, ten are the set's.
     */
    fun select(id: String?) {
        selectedId = id
        if (id == null || id !in selectedIds) selectedIds = emptySet()
    }

    /** Ctrl/Cmd+click (or `x` on the cursor's row, or a long-press on Android): flip one row's membership. */
    fun toggle(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        selectedId = id
    }

    /** Shift+click: everything between the cursor and [id], inclusive, joins the set. */
    fun extendTo(id: String) {
        val ids = visibleIds()
        val to = ids.indexOf(id)
        if (to < 0) return
        val from = selectedId?.let(ids::indexOf)?.takeIf { it >= 0 } ?: 0
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        selectedIds = selectedIds + ids.subList(lo, hi + 1)
        selectedId = id
    }

    /** Ctrl/Cmd+A: every row currently drawn. */
    fun selectAllVisible() {
        selectedIds = visibleIds().toSet()
    }

    /** Esc: leave selection mode, keep the cursor where it is. */
    fun clearSelection() {
        selectedIds = emptySet()
    }

    /** The rows currently drawn, top to bottom. */
    fun visibleIds(): List<String> = rows.entries.sortedBy { it.value }.map { it.key }

    /**
     * Moves the selection [delta] rows down (or up, for a negative delta).
     *
     * With nothing selected this takes the first row going down and the last going up, so `j` on
     * a fresh screen selects something rather than doing nothing. It stops at the ends rather
     * than wrapping: wrapping from the bottom of a long list to the top reads as a jump to
     * somewhere else entirely.
     */
    fun moveBy(delta: Int) {
        val ids = visibleIds()
        if (ids.isEmpty()) return
        val current = selectedId?.let(ids::indexOf) ?: -1
        selectedId = when {
            current < 0 -> if (delta > 0) ids.first() else ids.last()
            else -> ids[(current + delta).coerceIn(0, ids.lastIndex)]
        }
    }
}

val LocalRowSelection = staticCompositionLocalOf { RowSelectionState() }

/**
 * Registers this row as selectable and draws its state: a ring for the keyboard cursor, a filled
 * background for the multi-select set (#40).
 *
 * A ring rather than a filled background for the cursor, because a task row already uses its
 * background for "overdue" and two meanings on one surface is how a list stops being readable;
 * the set gets the fill instead, since many rows wearing it at once is exactly the picture
 * "these are the ones the bar acts on" needs.
 */
@Composable
fun Modifier.selectableRow(id: String): Modifier {
    val selection = LocalRowSelection.current

    DisposableEffect(id) {
        onDispose { selection.unregister(id) }
    }

    val ring = if (selection.isSelected(id)) {
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
    } else {
        Modifier
    }
    val fill = if (selection.isMultiSelected(id)) {
        Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
    } else {
        Modifier
    }

    return this
        .onGloballyPositioned { selection.register(id, it.boundsInRoot().top) }
        .then(fill)
        .then(ring)
}
