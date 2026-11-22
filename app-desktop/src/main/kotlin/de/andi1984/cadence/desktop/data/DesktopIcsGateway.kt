package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.domain.export.IcsCodec
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.platform.IcsFailure
import de.andi1984.cadence.ui.platform.IcsGateway
import de.andi1984.cadence.ui.platform.IcsOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Writes ICS files on plain `java.nio` paths on the desktop.
 * The shape mirrors `DesktopBackupIo` deliberately.
 */
class DesktopIcsGateway(
    private val repository: CadenceRepository,
) : IcsGateway {

    override suspend fun export(target: BackupTarget, includeCompleted: Boolean): IcsOutcome =
        withContext(Dispatchers.IO) {
            val tasks = repository.tasks.first()
            val exportedAt = Instant.now()
            val icsContent = IcsCodec.encode(tasks, exportedAt, includeCompleted)

            runCatching {
                val file = File(target.value)
                file.parentFile?.mkdirs()
                // A temp file + atomic rename, not a direct write: a crash mid-write must not leave
                // a half-written file where a good export used to be.
                val tmp = File(file.parentFile ?: file.absoluteFile.parentFile, "${file.name}.tmp")
                tmp.writeText(icsContent, Charsets.UTF_8)
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
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
