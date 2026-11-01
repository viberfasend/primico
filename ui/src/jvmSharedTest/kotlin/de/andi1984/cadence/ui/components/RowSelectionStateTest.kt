package de.andi1984.cadence.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which row the keyboard is on.
 *
 * The order is read off where the rows *are*, not off any list, because nothing in `:ui` has one
 * list — seven screens draw rows in bands, under headers, with checklists spliced in. These pin
 * the two things that follow from that: the order is vertical, and a row that leaves the screen
 * takes the selection with it rather than leaving `x` pointed at something invisible.
 */
class RowSelectionStateTest {

    private fun state(vararg rows: Pair<String, Float>) = RowSelectionState().apply {
        rows.forEach { (id, top) -> register(id, top) }
    }

    @Test
    fun `the order is the order the rows are drawn in, not the order they registered`() {
        // A `LazyColumn` composes in whatever order it likes, and a band drawn later can sit
        // higher up the screen.
        val selection = state("bottom" to 300f, "top" to 10f, "middle" to 150f)

        assertEquals(listOf("top", "middle", "bottom"), selection.visibleIds())
    }

    @Test
    fun `moving down from nothing selects the first row, and up selects the last`() {
        val selection = state("a" to 0f, "b" to 50f)

        selection.moveBy(1)
        assertEquals("a", selection.selectedId)

        selection.select(null)
        selection.moveBy(-1)
        assertEquals("b", selection.selectedId)
    }

    @Test
    fun `the selection stops at the ends rather than wrapping`() {
        val selection = state("a" to 0f, "b" to 50f)
        selection.select("b")

        selection.moveBy(1)
        assertEquals("b", selection.selectedId)

        selection.select("a")
        selection.moveBy(-1)
        assertEquals("a", selection.selectedId)
    }

    @Test
    fun `a row that leaves the screen takes the selection with it`() {
        val selection = state("a" to 0f, "b" to 50f)
        selection.select("b")

        selection.unregister("b")

        // Otherwise the next `x` completes a task nobody can see.
        assertNull(selection.selectedId)
        assertEquals(listOf("a"), selection.visibleIds())
    }

    @Test
    fun `a row leaving while another is selected changes nothing`() {
        val selection = state("a" to 0f, "b" to 50f)
        selection.select("a")

        selection.unregister("b")

        assertEquals("a", selection.selectedId)
    }

    @Test
    fun `moving in an empty list does nothing`() {
        val selection = RowSelectionState()

        selection.moveBy(1)

        assertNull(selection.selectedId)
    }

    // ── Multi-select (#40) ─────────────────────────────────────────────────────────

    @Test
    fun `toggle flips one row in and out of the set and moves the cursor to it`() {
        val selection = state("a" to 0f, "b" to 50f, "c" to 100f)

        selection.toggle("b")
        selection.toggle("c")

        assertEquals(setOf("b", "c"), selection.selectedIds)
        assertEquals("c", selection.selectedId)
        assertTrue(selection.isSelecting)

        selection.toggle("b")

        assertEquals(setOf("c"), selection.selectedIds)
        assertEquals("b", selection.selectedId)
    }

    @Test
    fun `shift extends the set from the cursor to the clicked row in drawn order, either direction`() {
        val selection = state("a" to 0f, "b" to 50f, "c" to 100f, "d" to 150f)
        selection.select("b")

        selection.extendTo("d")
        assertEquals(setOf("b", "c", "d"), selection.selectedIds)

        selection.extendTo("a")
        assertEquals(setOf("a", "b", "c", "d"), selection.selectedIds)
        assertEquals("a", selection.selectedId)
    }

    @Test
    fun `select all takes every drawn row, and clear empties the set but keeps the cursor`() {
        val selection = state("a" to 0f, "b" to 50f)
        selection.select("a")

        selection.selectAllVisible()
        assertEquals(setOf("a", "b"), selection.selectedIds)

        selection.clearSelection()
        assertTrue(selection.selectedIds.isEmpty())
        assertFalse(selection.isSelecting)
        assertEquals("a", selection.selectedId)
    }

    @Test
    fun `a plain click on a row outside the set clears the set, and one inside keeps it`() {
        val selection = state("a" to 0f, "b" to 50f, "c" to 100f)
        selection.toggle("a")
        selection.toggle("b")

        selection.select("b")
        assertEquals(setOf("a", "b"), selection.selectedIds)

        selection.select("c")
        assertTrue(selection.selectedIds.isEmpty())
        assertEquals("c", selection.selectedId)
    }

    @Test
    fun `a row leaving the screen leaves the set as well as the cursor`() {
        val selection = state("a" to 0f, "b" to 50f)
        selection.toggle("a")
        selection.toggle("b")

        selection.unregister("b")

        assertEquals(setOf("a"), selection.selectedIds)
        assertNull(selection.selectedId)
    }
}
