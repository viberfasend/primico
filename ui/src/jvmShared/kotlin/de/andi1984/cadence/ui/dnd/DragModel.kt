package de.andi1984.cadence.ui.dnd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import java.time.LocalDate

/**
 * What a drag *means*, decided in plain Kotlin.
 *
 * This half of the drag kernel touches no Compose runtime and holds no state: a payload, a target
 * and the current [CadenceUiState] go in, an intent comes out, and every rule about what may be
 * dropped where lives in [resolveDrop] rather than being repeated per screen. `DragAndDrop.kt`
 * next door does the gesture, the ghost and the hover highlight; it decides nothing.
 *
 * Why not `Modifier.dragAndDropSource`/`Target`: its payload is a `ClipEntry` wrapping an AWT
 * `Transferable` on the desktop and a `ClipData` on Android, and building one needs an
 * `expect`/`actual` seam this module does not have — `jvmShared` is a single source set feeding
 * both targets. Every drag here is in-process and carries a domain object, none of them want a
 * MIME type, and the platform API has no notion of the insertion caret that is most of what these
 * drags have to show. See `docs/adr/0003-desktop-interaction-model.md`.
 */
sealed interface DragPayload {
    /** The row being dragged, for the "you cannot drop something on itself" rule. */
    val id: String

    /**
     * The rows being dragged: one normally, the whole selection when the drag started on a
     * selected row (#40). [tasks] keeps the payload's order — the order the rows were drawn in —
     * so a drop between two rows can land the block in that same relative order.
     */
    data class TaskDrag(val tasks: List<Task>) : DragPayload {
        constructor(task: Task) : this(listOf(task))

        override val id: String get() = tasks.first().id

        /** Every dragged row's id. */
        val ids: Set<String> get() = tasks.map { it.id }.toSet()
    }

    data class ProjectDrag(val project: Project) : DragPayload {
        override val id: String get() = project.id
    }

    data class SectionDrag(val section: Section) : DragPayload {
        override val id: String get() = section.id
    }

    /** A tag row from the Tags screen. It only ever reorders — a tag holds nothing, so there is
     *  nowhere to drop one *into*. */
    data class TagDrag(val tag: Tag) : DragPayload {
        override val id: String get() = tag.id
    }
}

/** One of the app's hand-ordered lists — the bucket a [DropTarget.Between] inserts into. */
sealed interface OrderedList {
    /** A project's band, or the Inbox (`projectId == null`), or a day in Today. */
    data class Tasks(val projectId: String?, val sectionId: String? = null) : OrderedList

    /**
     * A hand-ordered list of tasks that is *not* a container: Today, a search result.
     *
     * The difference from [Tasks] is what a drop means. Dropping a row into a container's list
     * files it there — that is what "the Inbox" or "this band" is. Today is a question about
     * dates, not a place, so dropping a row inside it may only change the order; a task filed
     * under Home must not quietly leave Home because it was dragged up one line in Today.
     */
    data object LooseTasks : OrderedList

    /** The projects directly under [parentId] — null for the root list. */
    data class Projects(val parentId: String?) : OrderedList

    /** One project's bands. */
    data class Sections(val projectId: String) : OrderedList

    /** Every tag, in the one flat list they live in — no parent and no bucket to name. */
    data object Tags : OrderedList
}

/**
 * A place something can be dropped, as registered by the composable that draws it.
 *
 * A zone registers the target it *would* accept and [resolveDrop] decides whether this particular
 * payload may land there, so a row does not have to know which kinds of thing exist.
 */
sealed interface DropTarget {
    /** A project row, or the Inbox row (`projectId == null`). */
    data class IntoProject(val projectId: String?) : DropTarget

    /** A section band's header or body. `sectionId == null` is the project's ungrouped band. */
    data class IntoSection(val projectId: String, val sectionId: String?) : DropTarget

    /** A day header in Upcoming, or a "no date" affordance (`date == null`). */
    data class OntoDate(val date: LocalDate?) : DropTarget

    /** A tag row — in the desktop sidebar, or anywhere else one is drawn. Dropping a task here
     *  *applies* the label and never removes it: a drag is an act of adding, and a gesture that
     *  did one thing or its opposite depending on invisible state is not one anybody can aim. */
    data class IntoTag(val tagId: String) : DropTarget

    /** The gap between two rows of [list]: the payload lands at [index] of [orderedIds].
     *
     *  [orderedIds] is the list as it is currently drawn, dragged row included — the zone knows
     *  it and the kernel does not, and carrying it here is what lets the intent be self-contained
     *  instead of every screen recomputing the same list on drop. */
    data class Between(val list: OrderedList, val index: Int, val orderedIds: List<String>) : DropTarget
}

/** What the app should actually do — the only thing a shell has to interpret. */
sealed interface DropIntent {

    /**
     * File the tasks under a project and a band. Both are absolute: null means Inbox / ungrouped.
     * [taskIds] is one row normally, the whole dragged selection when the drag started on a
     * selected row (#40) — and only ever the rows that would actually change, so a drop that
     * moves nothing resolves to [Rejected] instead.
     */
    data class MoveTask(
        val taskIds: List<String>,
        val projectId: String?,
        val sectionId: String?,
    ) : DropIntent

    data class RescheduleTask(val taskIds: List<String>, val date: LocalDate?) : DropIntent

    /** Put [tagId] on every listed task, keeping every label they already have. */
    data class TagTask(val taskIds: List<String>, val tagId: String) : DropIntent

    /** Nest a project under [parentId], or (null) lift it back to the root list. */
    data class NestProject(val projectId: String, val parentId: String?) : DropIntent

    /**
     * Write a task list's order.
     *
     * [move] is set when the row came from another list — a drag from one band into a position in
     * the next is one gesture and has to be one intent, or the row is filed and then ordered
     * against a list it was not in yet.
     */
    data class ReorderTasks(val orderedIds: List<String>, val move: MoveTask? = null) : DropIntent

    data class ReorderProjects(val parentId: String?, val orderedIds: List<String>) : DropIntent

    data class ReorderSections(val projectId: String, val orderedIds: List<String>) : DropIntent

    data class ReorderTags(val orderedIds: List<String>) : DropIntent

    /** Nothing would change, or the drop is not allowed. A zone resolving to this is not
     *  highlighted, so an impossible drag never *looks* possible. */
    data object Rejected : DropIntent
}

/**
 * The one place that decides what a drop does.
 *
 * A no-op is [DropIntent.Rejected] on purpose rather than a write that changes nothing: the hover
 * highlight is driven by this, and a row lighting up for a drop that does nothing is a lie about
 * what will happen.
 */
fun resolveDrop(payload: DragPayload, target: DropTarget, state: CadenceUiState): DropIntent =
    when (payload) {
        is DragPayload.TaskDrag -> resolveTaskDrop(payload.tasks, target)
        is DragPayload.ProjectDrag -> resolveProjectDrop(payload.project, target, state)
        is DragPayload.SectionDrag -> resolveSectionDrop(payload.section, target)
        is DragPayload.TagDrag -> resolveTagDrop(payload.tag, target)
    }

private fun resolveTaskDrop(tasks: List<Task>, target: DropTarget): DropIntent =
    when (target) {
        is DropTarget.IntoProject -> {
            // A band belongs to one project, so a task that changes project loses its heading —
            // the same rule `CadenceRepository.moveToProject` applies. Rows already filed there
            // are not in the intent: rewriting them would be a write that changes nothing.
            val affected = tasks.filter { it.projectId != target.projectId }
            if (affected.isEmpty()) DropIntent.Rejected
            else DropIntent.MoveTask(affected.map { it.id }, target.projectId, sectionId = null)
        }

        is DropTarget.IntoSection -> {
            // Dropping on a band of a project the task is not in is a move *and* a grouping, not
            // a rejection: the band names its project, so the gesture is unambiguous.
            val affected = tasks.filter {
                it.projectId != target.projectId || it.sectionId != target.sectionId
            }
            if (affected.isEmpty()) DropIntent.Rejected
            else DropIntent.MoveTask(affected.map { it.id }, target.projectId, target.sectionId)
        }

        is DropTarget.OntoDate -> {
            val affected = tasks.filter { it.dueDate != target.date }
            if (affected.isEmpty()) DropIntent.Rejected
            else DropIntent.RescheduleTask(affected.map { it.id }, target.date)
        }

        // A label every dragged row already wears is a drop that would change nothing, so the row
        // does not light up — the same rule every branch here follows.
        is DropTarget.IntoTag -> {
            val affected = tasks.filter { target.tagId !in it.tagIds }
            if (affected.isEmpty()) DropIntent.Rejected
            else DropIntent.TagTask(affected.map { it.id }, target.tagId)
        }

        is DropTarget.Between -> when (val list = target.list) {
            is OrderedList.Tasks -> {
                val affected = tasks.filter {
                    it.projectId != list.projectId || it.sectionId != list.sectionId
                }
                val move = if (affected.isEmpty()) {
                    null
                } else {
                    DropIntent.MoveTask(affected.map { it.id }, list.projectId, list.sectionId)
                }
                val block = tasks.map { it.id }
                val ordered = insertAt(target.orderedIds, block.toSet(), target.index, blockOrder = block)
                if (ordered == null) DropIntent.Rejected else DropIntent.ReorderTasks(ordered, move)
            }

            // Order only — see [OrderedList.LooseTasks].
            OrderedList.LooseTasks -> {
                val block = tasks.map { it.id }
                val ordered = insertAt(target.orderedIds, block.toSet(), target.index, blockOrder = block)
                if (ordered == null) DropIntent.Rejected else DropIntent.ReorderTasks(ordered)
            }

            is OrderedList.Projects, is OrderedList.Sections, OrderedList.Tags ->
                DropIntent.Rejected
        }
    }

private fun resolveProjectDrop(
    project: Project,
    target: DropTarget,
    state: CadenceUiState,
): DropIntent = when (target) {
    // A project dropped on a project row nests it. `IntoProject(null)` — the Inbox row — lifts it
    // back to the root list instead, which is the only way to un-nest by dragging.
    is DropTarget.IntoProject -> when {
        target.projectId == null ->
            if (project.parentId == null) DropIntent.Rejected
            else DropIntent.NestProject(project.id, null)

        else -> nestOrReject(project, target.projectId, state)
    }

    is DropTarget.Between -> {
        val list = target.list
        if (list !is OrderedList.Projects) {
            DropIntent.Rejected
        } else if (project.parentId != list.parentId) {
            // Dropping a subproject into the root list between two rows is a *move*, and the
            // ordering that follows it is a second gesture. Reordering it here would leave it
            // nested but numbered among rows it is not beside.
            DropIntent.Rejected
        } else {
            val ordered = insertAt(target.orderedIds, setOf(project.id), target.index)
            if (ordered == null) DropIntent.Rejected
            else DropIntent.ReorderProjects(list.parentId, ordered)
        }
    }

    is DropTarget.IntoSection, is DropTarget.OntoDate, is DropTarget.IntoTag ->
        DropIntent.Rejected
}

private fun resolveSectionDrop(section: Section, target: DropTarget): DropIntent {
    val between = target as? DropTarget.Between ?: return DropIntent.Rejected
    val list = between.list as? OrderedList.Sections ?: return DropIntent.Rejected
    if (list.projectId != section.projectId) return DropIntent.Rejected
    val ordered = insertAt(between.orderedIds, setOf(section.id), between.index)
        ?: return DropIntent.Rejected
    return DropIntent.ReorderSections(list.projectId, ordered)
}

/**
 * A tag row dragged: order, and nothing else.
 *
 * There is no `IntoTag` case here on purpose. A tag holds no tasks — membership is the task's
 * column (ADR 0004, decision 1) — so dropping one tag on another could only ever mean "merge
 * them", which is the O(n) rewrite of every labelled task that decision exists to avoid.
 */
private fun resolveTagDrop(tag: Tag, target: DropTarget): DropIntent {
    val between = target as? DropTarget.Between ?: return DropIntent.Rejected
    if (between.list !is OrderedList.Tags) return DropIntent.Rejected
    val ordered = insertAt(between.orderedIds, setOf(tag.id), between.index) ?: return DropIntent.Rejected
    return DropIntent.ReorderTags(ordered)
}

/**
 * Nesting, with the three rules the model does not enforce itself.
 *
 * Projects nest exactly one level (`CadenceUiState.nestingCandidates` states the same rule for the
 * dialog), so a project that already has subprojects cannot become one, and neither can anything
 * dropped on a project that is already nested. A cycle is ruled out too — the repository would
 * refuse it, but a target that lights up and then does nothing is the failure being avoided here.
 */
private fun nestOrReject(project: Project, parentId: String, state: CadenceUiState): DropIntent =
    when {
        parentId == project.id -> DropIntent.Rejected
        project.parentId == parentId -> DropIntent.Rejected
        state.subprojects(project.id).isNotEmpty() -> DropIntent.Rejected
        state.project(parentId)?.parentId != null -> DropIntent.Rejected
        else -> DropIntent.NestProject(project.id, parentId)
    }

/**
 * [orderedIds] with every id in [movedIds] taken out and put back as one block at [index], or null
 * when that is where they already are.
 *
 * [index] counts gaps in the list *as drawn* — index 2 of `[a, b, c]` is the gap between `b` and
 * `c` — so rows moving down have to have their own slots removed before they land, which is what
 * makes dropping a row into the gap just below itself a no-op rather than an off-by-one. The
 * block keeps [blockOrder]'s relative order, which for a multi-select drag (#40) is the order the
 * rows were drawn in.
 */
internal fun insertAt(
    orderedIds: List<String>,
    movedIds: Set<String>,
    index: Int,
    blockOrder: List<String> = movedIds.toList(),
): List<String>? {
    if (movedIds.isEmpty()) return null
    val without = orderedIds.filterNot { it in movedIds }
    // How many of the moved rows sat above the gap: each one shifts the landing point up by one.
    val movedAbove = orderedIds.take(index).count { it in movedIds }
    val to = (index - movedAbove).coerceIn(0, without.size)
    val alreadyThere = without.size + movedIds.size == orderedIds.size &&
        orderedIds.subList(to, to + movedIds.size) == blockOrder
    if (alreadyThere) return null
    return without.toMutableList().apply { addAll(to, blockOrder) }
}

/** A registered drop zone: where it is on screen, and what it would accept. */
data class DropZone(
    val key: Any,
    val bounds: Rect,
    val target: DropTarget,
)

/**
 * The zone under the pointer that would actually do something.
 *
 * **Innermost wins**, measured by area: a section band sits inside a project pane, and the band is
 * what the user is pointing at. A zone whose intent is [DropIntent.Rejected] is skipped rather
 * than blocking the one behind it — dragging a project across a task row must still reach the
 * project pane underneath.
 */
fun hitTest(
    zones: Collection<DropZone>,
    position: Offset,
    payload: DragPayload,
    state: CadenceUiState,
): Pair<DropZone, DropIntent>? = zones
    .asSequence()
    .filter { it.bounds.contains(position) }
    .sortedBy { it.bounds.width * it.bounds.height }
    .mapNotNull { zone ->
        val intent = resolveDrop(payload, zone.target, state)
        if (intent is DropIntent.Rejected) null else zone to intent
    }
    .firstOrNull()
