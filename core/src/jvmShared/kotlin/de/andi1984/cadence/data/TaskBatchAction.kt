package de.andi1984.cadence.data

import de.andi1984.cadence.domain.model.Priority
import java.time.LocalDate

/**
 * One action applied to many tasks at once — the multi-select bar's vocabulary (#40).
 *
 * Every member is a call the single-row menu already makes; [CadenceRepository.applyToTasks]
 * runs the same rule over a set of ids in one transaction, so a batch is one write, one sync
 * tick and one undo entry rather than N of each. The sealed shape is what lets the ViewModel map
 * an action to its snackbar and the repository to its write without a second `when` growing
 * somewhere else.
 */
sealed interface TaskBatchAction {

    /** Marks every task done — recurrence chains and all, exactly as [CadenceRepository.setCompleted]. */
    data object Complete : TaskBatchAction

    /** Reopens every task — removing the successors their completions spawned. */
    data object Reopen : TaskBatchAction

    /** Tombstones every task and its subtasks, reclaiming their blobs once. */
    data object Delete : TaskBatchAction

    /**
     * Files every task under [projectId] (null is the Inbox) and, when the target project has
     * one, under [sectionId]. A section belongs to one project, so moving to the Inbox clears it.
     */
    data class MoveToProject(val projectId: String?, val sectionId: String? = null) : TaskBatchAction

    data class SetDueDate(val dueDate: LocalDate?) : TaskBatchAction

    data class SetPriority(val priority: Priority) : TaskBatchAction

    /** Add-only: a batch never removes a label a task gained meanwhile. */
    data class AddTag(val tagId: String) : TaskBatchAction

    data class RemoveTag(val tagId: String) : TaskBatchAction
}
