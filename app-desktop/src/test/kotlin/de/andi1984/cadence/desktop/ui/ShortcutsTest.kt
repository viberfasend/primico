package de.andi1984.cadence.desktop.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import de.andi1984.cadence.ui.components.HintedAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shortcut table is data, and these are the rules it has to keep as data — the ones a person
 * adding a row would not think to check by pressing keys.
 */
class ShortcutsTest {

    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    /**
     * A key going down, carrying the character it types — what the window's `onKeyEvent` sees.
     *
     * Compose's own test factory, opted into because its public alternative does not exist: the
     * AWT-event conversion the window runs is internal, and a raw `java.awt.event.KeyEvent` cannot
     * be wrapped by hand. If a Compose upgrade breaks this signature, only this helper changes.
     */
    @OptIn(InternalComposeUiApi::class)
    private fun press(
        key: Key,
        char: Char,
        shift: Boolean = false,
        primary: Boolean = false,
    ): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        codePoint = char.code,
        isCtrlPressed = primary && !isMac,
        isMetaPressed = primary && isMac,
        isShiftPressed = shift,
    )

    @Test
    fun `no two rows answer to the same keys`() {
        val bindings = CADENCE_SHORTCUTS.map { it.char ?: Triple(it.key, it.modifiers, null) }
        val duplicates = bindings.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("bound twice: ${duplicates.keys}", duplicates.isEmpty())
    }

    @Test
    fun `every action the window dispatches is reachable from some row`() {
        val unbound = ShortcutAction.entries - CADENCE_SHORTCUTS.map { it.action }.toSet()
        assertTrue("no row reaches: $unbound", unbound.isEmpty())
    }

    @Test
    fun `two different actions never share a name on the sheet`() {
        // The regression: J was "More" and K "Back", borrowed from buttons, so the sheet listed
        // "More" twice — once for the next task and once for Forward.
        val labelsByAction = CADENCE_SHORTCUTS.groupBy({ it.label }, { it.action })
        val shared = labelsByAction.filterValues { it.toSet().size > 1 }
        assertTrue("one name, several actions: $shared", shared.isEmpty())
    }

    @Test
    fun `the cheat sheet opens from a German layout, where a question mark is Shift and sharp s`() {
        // On QWERTZ the key right of 0 is ß, which has no key code of its own; the character
        // it types with Shift is '?' all the same.
        val germanQuestionMark = press(Key.Unknown, '?', shift = true)
        assertEquals(ShortcutAction.ShowShortcuts, shortcutFor(germanQuestionMark))
    }

    @Test
    fun `the cheat sheet still opens from a US layout`() {
        val usQuestionMark = press(Key.Slash, '?', shift = true)
        assertEquals(ShortcutAction.ShowShortcuts, shortcutFor(usQuestionMark))
    }

    @Test
    fun `a question mark typed with the primary modifier held is not the cheat sheet`() {
        assertNull(shortcutFor(press(Key.Slash, '?', shift = true, primary = true)))
    }

    @Test
    fun `a bare slash is not the cheat sheet`() {
        assertNull(shortcutFor(press(Key.Slash, '/')))
    }

    @Test
    fun `every hint a screen can ask for names keys the window answers to`() {
        HintedAction.entries.forEach { hinted ->
            assertNotNull("no keys for $hinted", DesktopShortcutHints.keysFor(hinted))
        }
    }

    @Test
    fun `a hint is written exactly as the cheat sheet writes it`() {
        val quickAdd = CADENCE_SHORTCUTS.first { it.action == ShortcutAction.QuickAdd }
        assertEquals(quickAdd.combination(), DesktopShortcutHints.keysFor(HintedAction.QuickAdd))
        assertEquals(if (isMac) "⌘N" else "Ctrl+N", quickAdd.combination())
    }

    @Test
    fun `an action with two rows is hinted by its letter, not its arrow`() {
        assertEquals("J", keysFor(ShortcutAction.SelectNext))
    }
}
