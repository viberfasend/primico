package de.andi1984.cadence.data.ics

import android.content.Context
import android.net.Uri
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.domain.export.IcsCodec
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.platform.IcsFailure
import de.andi1984.cadence.ui.platform.IcsGateway
import de.andi1984.cadence.ui.platform.IcsOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Writes ICS files through the Storage Access Framework on Android.
 * The shape mirrors `BackupIo` deliberately.
 */
class IcsIo(
    private val context: Context,
    private val repository: CadenceRepository,
) : IcsGateway {

    override suspend fun export(target: BackupTarget, includeCompleted: Boolean): IcsOutcome =
        withContext(Dispatchers.IO) {
            val tasks = repository.tasks.first()
            val exportedAt = Instant.now()
            val icsContent = IcsCodec.encode(tasks, exportedAt, includeCompleted)

            runCatching {
                val uri = Uri.parse(target.value)
                // "wt" truncates: overwriting an existing file must not leave a tail of the old one.
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.use { it.write(icsContent.toByteArray(Charsets.UTF_8)) }
                    ?: error("no output stream for $uri")
            }.fold(
                onSuccess = {
                    val datedTaskCount = tasks.count { it.dueDate != null && it.deletedAt == null }
                    IcsOutcome.Exported(
                        tasks = if (includeCompleted) datedTaskCount else {
                            tasks.count { it.dueDate != null && it.deletedAt == null && it.completedAt == null }
                        },
                        exportedAt = exportedAt,
                    )
                },
                onFailure = { IcsOutcome.Failed(IcsFailure.WRITE_FAILED) },
            )
        }
}
