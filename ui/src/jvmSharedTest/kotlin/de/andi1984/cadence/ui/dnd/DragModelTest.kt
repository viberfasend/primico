package de.andi1984.cadence.ui.dnd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * What a drop *means*. The gesture is a composable and goes untested, per this module's line; the
 * rules are plain functions and every one of them is here, because "the row lit up and nothing
 * happened" is the failure this kernel exists to make impossible.
 */
class DragModelTest {

    private val today = LocalDate.of(2026, 8, 21)

    private fun task(
        id: String,
        projectId: String? = null,
        sectionId: String? = null,
        due: LocalDate? = null,
        tagIds: List<String> = emptyList(),
    ) = Task(
        id = id,
        title = id,
        projectId = projectId,
        sectionId = sectionId,
        dueDate = due,
        tagIds = tagIds,
    )

    private fun project(id: String, parentId: String? = null) =
        Project(id = id, name = id, parentId = parentId)

    private val state = CadenceUiState(
        projects = listOf(
            project("home"),
            project("work"),
            project("finance", parentId = "home"),
            project("childless"),
        ),
        sections = listOf(Section(id = "band", projectId = "work", name = "Band")),
        tags = listOf(
            Tag(id = "errand", name = "Errand"),
            Tag(id = "waiting", name = "Waiting", sortOrder = 1),
        ),
    )

    private fun drop(payload: DragPayload, target: DropTarget) = resolveDrop(payload, target, state)

    // ── A task ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a task dropped on the project it is already in does nothing`() {
        val intent = drop(
            DragPayload.TaskDrag(task("t", projectId = "home")),
            DropTarget.IntoProject("home"),
        )

        // Rejected rather than a write that changes nothing: the hover highlight reads this, and
        // a row that lights up promises something will happen.
        assertEquals(DropIntent.Rejected, intent)
    }

    @Test
    fun `a task dropped on another project moves, and loses its band`() {
        val intent = drop(
            DragPayload.TaskDrag(task("t", projectId = "home", sectionId = "old-band")),
            DropTarget.IntoProject("work"),
        )

        // A band belongs to one project, so it cannot travel with the task.
        assertEquals(DropIntent.MoveTask(listOf("t"), "work", null), intent)
    }

    @Test
    fun `a task dropped on the Inbox row clears its project`() {
        val intent = drop(
            DragPayload.TaskDrag(task("t", projectId = "home")),
            DropTarget.IntoProject(null),
        )

        assertEquals(DropIntent.MoveTask(listOf("t"), null, null), intent)
    }

    @Test
    fun `a task dropped on a band of a project it is not in moves and groups in one gesture`() {
        val intent = drop(
            DragPayload.TaskDrag(task("t", projectId = "home")),
            DropTarget.IntoSection("work", "band"),
        )

        // Not a rejection: the band names its project, so the gesture is unambiguous.
        assertEquals(DropIntent.MoveTask(listOf("t"), "work", "band"), intent)
    }

    @Test
    fun `a task dropped on the band it is already in does nothing`() {
        val intent = drop(
            DragPayload.TaskDrag(task("t", projectId = "work", sectionId = "band")),
            DropTarget.IntoSection("work", "band"),
        )

        assertEquals(DropIntent.Rejected, intent)
    }

    @Test
    fun `a task dropped on a day takes that date, and on its own day does nothing`() {
        val dragged = DragPayload.TaskDrag(task("t", due = today))

        assertEquals(
            DropIntent.RescheduleTask(listOf("t"), today.plusDays(1)),
            drop(dragged, DropTarget.OntoDate(today.plusDays(1))),
        )
        assertEquals(DropIntent.Rejected, drop(dragged, DropTarget.OntoDate(today)))
    }

    // ── A selection of tasks (#40) ───────────────────────────────────────────────────

    @Test
    fun `a selection dropped on a project moves only the rows not already filed there`() {
        val selection = DragPayload.TaskDrag(
            listOf(task("a", projectId = "home"), task("b"), task("c", projectId = "work")),
        )

        val intent = drop(selection, DropTarget.IntoProject("work"))

        // "c" is already in Work: rewriting it would be a write that changes nothing, so the
        // intent carries the other two — and the drop still lights up, because something moves.
        assertEquals(DropIntent.MoveTask(listOf("a", "b"), "work", null), intent)
    }

    @Test
    fun `a selection dropped on the project every row is already in does nothing`() {
        val selection = DragPayload.TaskDrag(
            listOf(task("a", projectId = "home"), task("b", projectId = "home")),
        )

        assertEquals(DropIntent.Rejected, drop(selection, DropTarget.IntoProject("home")))
    }

    @Test
    fun `a selection dropped on a tag labels only the rows missing it`() {
        val selection = DragPayload.TaskDrag(
            listOf(task("a", tagIds = listOf("errand")), task("b")),
        )

        assertEquals(
            DropIntent.TagTask(listOf("b"), "errand"),
            drop(selection, DropTarget.IntoTag("errand")),
        )
        assertEquals(
            DropIntent.Rejected,
            drop(DragPayload.TaskDrag(listOf(task("a", tagIds = listOf("errand")))), DropTarget.IntoTag("errand")),
        )
    }

    @Test
    fun `a selection dropped on a day reschedules only the rows with a different date`() {
        val selection = DragPayload.TaskDrag(listOf(task("a", due = today), task("b")))

        // Both rows move: "a" is due today, not tomorrow, so it is affected too; only a row
        // already on the target day would be left out.
        assertEquals(
            DropIntent.RescheduleTask(listOf("a", "b"), today.plusDays(1)),
            drop(selection, DropTarget.OntoDate(today.plusDays(1))),
        )
    }

    @Test
    fun `a selection dropped between two rows lands as one block in the order the rows were drawn`() {
        val selection = DragPayload.TaskDrag(
            listOf(task("b", projectId = "home"), task("d", projectId = "home")),
        )

        val intent = drop(
            selection,
            DropTarget.Between(OrderedList.Tasks("home"), index = 2, orderedIds = listOf("a", "b", "c", "d")),
        )

        // The gap at index 2 is the one just below "b": the block's own slots are removed before
        // it lands, so [b, d] takes b's place and keeps its drawn order: a, b, d, c.
        assertEquals(
            DropIntent.ReorderTasks(listOf("a", "b", "d", "c")),
            intent,
        )
    }

    @Test
    fun `a selection dropped between two rows of a list it is not in moves and orders in one intent`() {
        val selection = DragPayload.TaskDrag(listOf(task("x"), task("y")))

        val intent = drop(
            selection,
            DropTarget.Between(OrderedList.Tasks("home"), index = 1, orderedIds = listOf("a", "b", "c")),
        )

        assertEquals(
            DropIntent.ReorderTasks(
                listOf("a", "x", "y", "b", "c"),
                move = DropIntent.MoveTask(listOf("x", "y"), "home", null),
            ),
            intent,
        )
    }

    // ── Ordering ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a task dropped between two rows of its own list is a plain reorder`() {
        val intent = drop(
            DragPayload.TaskDrag(task("c", projectId = "home")),
            DropTarget.Between(OrderedList.Tasks("home"), index = 1, orderedIds = listOf("a", "b", "c")),
        )

        assertEquals(DropIntent.ReorderTasks(listOf("a", "c", "b"), move = null), intent)
    }

    @Test
    fun `a task dropped into the gap it already occupies does nothing`() {
        val dragged = DragPayload.TaskDrag(task("b", projectId = "home"))
        val list = listOf("a", "b", "c")

        // The gap above `b` and the gap below it are both where `b` already is — an index that
        // counts gaps in the list *as drawn* has two names for one position.
        assertEquals(
            DropIntent.Rejected,
            drop(dragged, DropTarget.Between(OrderedList.Tasks("home"), 1, list)),
        )
        assertEquals(
            DropIntent.Rejected,
            drop(dragged, DropTarget.Between(OrderedList.Tasks("home"), 2, list)),
        )
    }

    @Test
    fun `a task dropped between rows of another band moves and orders in one intent`() {
        val intent = drop(
            DragPayload.TaskDrag(task("t", projectId = "home")),
            DropTarget.Between(
                OrderedList.Tasks("work", "band"),
                index = 1,
                orderedIds = listOf("a", "b"),
            ),
        )

        // Filing it and then ordering it would order it against a list it was not yet in.
        assertEquals(
            DropIntent.ReorderTasks(listOf("a", "t", "b"), move = DropIntent.MoveTask(listOf("t"), "work", "band")),
            intent,
        )
    }

    @Test
    fun `reordering Today changes the order and nothing else`() {
        val intent = drop(
            DragPayload.TaskDrag(task("c", projectId = "home")),
            DropTarget.Between(OrderedList.LooseTasks, index = 0, orderedIds = listOf("a", "b", "c")),
        )

        // No move: Today is a question about dates, not a place. A task filed under Home must not
        // leave Home because it was dragged up a line in a list that spans every project.
        assertEquals(DropIntent.ReorderTasks(listOf("c", "a", "b"), move = null), intent)
    }

    @Test
    fun `a task cannot be dropped into a list of projects`() {
        val intent = drop(
            DragPayload.TaskDrag(task("t")),
            DropTarget.Between(OrderedList.Projects(null), 0, listOf("home")),
        )

        assertEquals(DropIntent.Rejected, intent)
    }

    // ── A project ────────────────────────────────────────────────────────────────────

    @Test
    fun `a project dropped on another nests under it`() {
        val intent = drop(
            DragPayload.ProjectDrag(project("childless")),
            DropTarget.IntoProject("work"),
        )

        assertEquals(DropIntent.NestProject("childless", "work"), intent)
    }

    @Test
    fun `a project that already has subprojects cannot become one`() {
        // Nesting is exactly one level, so `home` (parent of `finance`) has nowhere to go.
        assertEquals(
            DropIntent.Rejected,
            drop(DragPayload.ProjectDrag(project("home")), DropTarget.IntoProject("work")),
        )
        // Which also rules out the cycle: dropping a parent on its own child.
        assertEquals(
            DropIntent.Rejected,
            drop(DragPayload.ProjectDrag(project("home")), DropTarget.IntoProject("finance")),
        )
    }

    @Test
    fun `nothing nests under a project that is itself nested`() {
        assertEquals(
            DropIntent.Rejected,
            drop(DragPayload.ProjectDrag(project("childless")), DropTarget.IntoProject("finance")),
        )
    }

    @Test
    fun `a project dropped on itself does nothing`() {
        assertEquals(
            DropIntent.Rejected,
            drop(DragPayload.ProjectDrag(project("work")), DropTarget.IntoProject("work")),
        )
    }

    @Test
    fun `the Inbox row is how a subproject is lifted back to the root list`() {
        assertEquals(
            DropIntent.NestProject("finance", null),
            drop(
                DragPayload.ProjectDrag(project("finance", parentId = "home")),
                DropTarget.IntoProject(null),
            ),
        )
        // A root project dropped there is already where it would land.
        assertEquals(
            DropIntent.Rejected,
            drop(DragPayload.ProjectDrag(project("work")), DropTarget.IntoProject(null)),
        )
    }

    @Test
    fun `a subproject dropped into the root list's gaps is a move, not a reorder`() {
        val intent = drop(
            DragPayload.ProjectDrag(project("finance", parentId = "home")),
            DropTarget.Between(OrderedList.Projects(null), 0, listOf("home", "work")),
        )

        // Renumbering it here would leave it nested but ordered among rows it is not beside.
        assertEquals(DropIntent.Rejected, intent)
    }

    @Test
    fun `projects reorder within the list they are already in`() {
        val intent = drop(
            DragPayload.ProjectDrag(project("work")),
            DropTarget.Between(OrderedList.Projects(null), 0, listOf("home", "work")),
        )

        assertEquals(DropIntent.ReorderProjects(null, listOf("work", "home")), intent)
    }

    // ── A section ────────────────────────────────────────────────────────────────────

    @Test
    fun `a band reorders inside its own project and nowhere else`() {
        val band = Section(id = "band", projectId = "work", name = "Band")

        assertEquals(
            DropIntent.ReorderSections("work", listOf("other", "band")),
            drop(
                DragPayload.SectionDrag(band),
                DropTarget.Between(OrderedList.Sections("work"), 2, listOf("band", "other")),
            ),
        )
        assertEquals(
            DropIntent.Rejected,
            drop(
                DragPayload.SectionDrag(band),
                DropTarget.Between(OrderedList.Sections("home"), 0, listOf("band")),
            ),
        )
        assertEquals(
            DropIntent.Rejected,
            drop(DragPayload.SectionDrag(band), DropTarget.IntoProject("home")),
        )
    }

    // ── A tag ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a task dropped on a tag row is labelled, keeping the labels it has`() {
        val intent = drop(
            DragPayload.TaskDrag(task("t", tagIds = listOf("waiting"))),
            DropTarget.IntoTag("errand"),
        )

        // The intent names one tag, not a list: the ViewModel adds it to what the task already
        // wears, so a drag can never be the thing that dropped a label.
        assertEquals(DropIntent.TagTask(listOf("t"), "errand"), intent)
    }

    @Test
    fun `a task dropped on a tag it already wears does nothing`() {
        val intent = drop(
            DragPayload.TaskDrag(task("t", tagIds = listOf("errand"))),
            DropTarget.IntoTag("errand"),
        )

        // Not a toggle: a drop that removed the label would make one gesture do two opposite
        // things depending on state the pointer cannot see.
        assertEquals(DropIntent.Rejected, intent)
    }

    @Test
    fun `a tag dropped into a gap writes the new order`() {
        val intent = drop(
            DragPayload.TagDrag(Tag(id = "waiting", name = "Waiting")),
            DropTarget.Between(OrderedList.Tags, index = 0, orderedIds = listOf("errand", "waiting")),
        )

        assertEquals(DropIntent.ReorderTags(listOf("waiting", "errand")), intent)
    }

    @Test
    fun `a tag dropped where it already sits does nothing`() {
        val intent = drop(
            DragPayload.TagDrag(Tag(id = "errand", name = "Errand")),
            DropTarget.Between(OrderedList.Tags, index = 0, orderedIds = listOf("errand", "waiting")),
        )

        assertEquals(DropIntent.Rejected, intent)
    }

    @Test
    fun `a tag dropped on a project, a band or another tag list does nothing`() {
        val dragged = DragPayload.TagDrag(Tag(id = "errand", name = "Errand"))

        // A tag holds nothing, so there is nowhere to drop one *into* — including onto a tag row,
        // where the only possible meaning would be a merge (ADR 0004, decision 1).
        assertEquals(DropIntent.Rejected, drop(dragged, DropTarget.IntoProject("home")))
        assertEquals(DropIntent.Rejected, drop(dragged, DropTarget.IntoTag("waiting")))
        assertEquals(
            DropIntent.Rejected,
            drop(dragged, DropTarget.Between(OrderedList.Projects(null), 0, listOf("home"))),
        )
    }

    @Test
    fun `a project dropped on a tag does nothing`() {
        assertEquals(
            DropIntent.Rejected,
            drop(DragPayload.ProjectDrag(project("home")), DropTarget.IntoTag("errand")),
        )
    }

    // ── Hit testing ──────────────────────────────────────────────────────────────────

    @Test
    fun `the innermost zone under the pointer wins`() {
        val pane = DropZone("pane", Rect(0f, 0f, 400f, 400f), DropTarget.IntoProject("work"))
        val band = DropZone("band", Rect(0f, 0f, 400f, 60f), DropTarget.IntoSection("work", "band"))

        val hit = hitTest(
            listOf(pane, band),
            Offset(10f, 10f),
            DragPayload.TaskDrag(task("t", projectId = "home")),
            state,
        )

        assertEquals("band", hit?.first?.key)
        assertEquals(DropIntent.MoveTask(listOf("t"), "work", "band"), hit?.second)
    }

    @Test
    fun `a zone that would do nothing is skipped rather than blocking the one behind it`() {
        // Dragging a project across a task row: the row accepts nothing from a project, and the
        // pane behind it must still be reachable.
        val pane = DropZone("pane", Rect(0f, 0f, 400f, 400f), DropTarget.IntoProject("work"))
        val row = DropZone("row", Rect(0f, 0f, 400f, 60f), DropTarget.OntoDate(today))

        val hit = hitTest(
            listOf(pane, row),
            Offset(10f, 10f),
            DragPayload.ProjectDrag(project("childless")),
            state,
        )

        assertEquals("pane", hit?.first?.key)
    }

    @Test
    fun `a pointer over nothing hits nothing`() {
        val pane = DropZone("pane", Rect(0f, 0f, 100f, 100f), DropTarget.IntoProject("work"))

        assertNull(
            hitTest(listOf(pane), Offset(500f, 500f), DragPayload.TaskDrag(task("t")), state),
        )
    }
}
