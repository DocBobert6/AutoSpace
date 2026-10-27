package app.autospace.car

import android.content.Context
import java.io.File

/**
 * r92: save states, memory cards, and disc swapping, ported from
 * Mariner's SaveStates, MemoryCard, and DiscSwap for the car screen.
 *
 * Four numbered slots per game plus one auto slot, under
 * filesDir/saves/<gameId>/. Saves go through a temp file and a
 * rename, so a failed save never clobbers a good one. The native
 * save, load, and thumbnail calls sit behind the playback engine:
 * the saveVia/loadVia helpers here take the engine call as a
 * lambda, so this file never touches native code itself.
 */
object GameSaves {
    const val SLOT_COUNT = 4
    const val AUTO_SLOT = -2
    const val NO_SLOT = -1

    fun gameIdFor(libraryId: String?, romPath: String): String =
        libraryId ?: ("rom-" + File(romPath).nameWithoutExtension
            .ifBlank { "game" })

    fun dirFor(filesDir: File, gameId: String): File =
        File(File(filesDir, "saves"), gameId)

    fun stateFileName(slot: Int): String =
        if (slot == AUTO_SLOT) "auto.state" else "slot$slot.state"

    fun thumbFileName(slot: Int): String =
        if (slot == AUTO_SLOT) "auto.png" else "slot$slot.png"

    fun stateFile(context: Context, gameId: String, slot: Int): File =
        File(dirFor(context.filesDir, gameId), stateFileName(slot))

    fun thumbFile(context: Context, gameId: String, slot: Int): File =
        File(dirFor(context.filesDir, gameId), thumbFileName(slot))

    data class SlotInfo(
        val slot: Int,
        val exists: Boolean,
        val modifiedAt: Long,
        val hasThumb: Boolean
    )

    fun slotInfo(context: Context, gameId: String, slot: Int): SlotInfo {
        val state = stateFile(context, gameId, slot)
        return SlotInfo(
            slot,
            state.exists(),
            if (state.exists()) state.lastModified() else 0L,
            thumbFile(context, gameId, slot).exists()
        )
    }

    fun autoExists(context: Context, gameId: String): Boolean =
        stateFile(context, gameId, AUTO_SLOT).exists()

    fun delete(context: Context, gameId: String, slot: Int): Boolean =
        stateFile(context, gameId, slot).delete() or
            thumbFile(context, gameId, slot).delete()

    /** The archive name for an auto save taken at [millis]. */
    fun archiveFileName(millis: Long): String = "auto-$millis.state"

    fun archiveThumbName(millis: Long): String = "auto-$millis.png"

    fun isArchiveName(name: String): Boolean =
        name.startsWith("auto-") && name.endsWith(".state")

    fun archiveMillis(name: String): Long? =
        name.removePrefix("auto-").removeSuffix(".state").toLongOrNull()

    data class AutoArchive(val file: File, val millis: Long)

    /** Auto-save archives, newest first. Archives are never deleted
     * automatically. */
    fun autoArchives(context: Context, gameId: String): List<AutoArchive> {
        val dir = dirFor(context.filesDir, gameId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { isArchiveName(it.name) }
            ?.mapNotNull { file ->
                archiveMillis(file.name)?.let { AutoArchive(file, it) }
            }
            ?.sortedByDescending { it.millis }
            ?: emptyList()
    }

    /** Copy the current auto save into a timestamped archive. */
    fun archiveAuto(context: Context, gameId: String): Boolean {
        val auto = stateFile(context, gameId, AUTO_SLOT)
        if (!auto.exists()) return false
        val millis = System.currentTimeMillis()
        val target = File(
            dirFor(context.filesDir, gameId),
            archiveFileName(millis)
        )
        val thumb = thumbFile(context, gameId, AUTO_SLOT)
        return runCatching {
            target.parentFile?.mkdirs()
            auto.copyTo(target, overwrite = true)
            if (thumb.exists()) {
                thumb.copyTo(
                    File(dirFor(context.filesDir, gameId), archiveThumbName(millis)),
                    overwrite = true
                )
            }
            true
        }.getOrDefault(false)
    }

    fun deleteArchive(archive: AutoArchive): Boolean = archive.file.delete()

    /**
     * Save through the engine into a temp file, then rename over the
     * slot, so a failed save never clobbers a good one. Thumbnails
     * arrive with the engine's frame grab; until then a slot's
     * thumbnail is simply absent.
     */
    fun saveVia(
        context: Context,
        gameId: String,
        slot: Int,
        writeState: (File) -> Boolean
    ): Boolean {
        val dir = dirFor(context.filesDir, gameId).apply { mkdirs() }
        val target = File(dir, stateFileName(slot))
        val tmp = File(dir, stateFileName(slot) + ".tmp")
        if (!writeState(tmp)) {
            tmp.delete()
            return false
        }
        if (target.exists()) target.delete()
        return if (tmp.renameTo(target)) {
            true
        } else {
            tmp.copyTo(target, overwrite = true).also { tmp.delete() }
            true
        }
    }

    /** Load a slot through the engine; false when the slot is empty
     * or the engine refuses. */
    fun loadVia(
        context: Context,
        gameId: String,
        slot: Int,
        readState: (File) -> Boolean
    ): Boolean {
        val state = stateFile(context, gameId, slot)
        if (!state.exists()) return false
        return readState(state)
    }
}

/** The PlayStation memory card: exactly 128 KB, and reads never
 * allocate more than one byte past it. */
object MemoryCard {
    const val SIZE_BYTES = 128 * 1024

    fun isValidSize(bytes: Int): Boolean = bytes == SIZE_BYTES

    /** Read at most SIZE_BYTES + 1 bytes, so an oversized file is
     * detected without ever loading it whole. */
    fun readCapped(bytes: ByteArray): ByteArray =
        bytes.copyOf(minOf(bytes.size, SIZE_BYTES + 1))
}

/** Disc labels for the swap picker: the core's own label when it
 * has one, else "Disc N" with a 1-based number. */
object DiscSwap {
    fun displayLabel(index: Int, coreLabel: String?): String =
        if (!coreLabel.isNullOrBlank()) coreLabel else "Disc ${index + 1}"
}
