package com.cloakedcent.app.data.backup

import com.cloakedcent.app.data.security.KeyWrapper
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Erases everything the app has saved, at the user's request.
 *
 * Like a restore, it is recorded first and carried out at the next start, before the
 * database opens, so no connection is open and an interruption simply repeats the job.
 * The database cannot open until the wipe has finished, so a retry can never erase new data.
 *
 * Destroying the Keystore key comes first and is what makes the erasure dependable: the
 * database and receipt images are encrypted, and without the key they cannot be read.
 * Files are also overwritten, but flash storage does not guarantee that reaches the old
 * blocks, so that part is only an extra layer.
 */
object SecureWipe {

    fun schedule(locations: StorageLocations) = RestoreJournal.writeAtomically(locations.wipeMarker, "wipe")

    fun isScheduled(locations: StorageLocations) = locations.wipeMarker.isFile

    /**
     * Carries out a scheduled wipe; returns true when one ran.
     *
     * @param emptied folders whose contents go, but which stay in place (the cache).
     * @param clearAppState removes what is not in a file listed here (settings, notifications).
     * @throws IOException if anything is left behind; the wipe stays scheduled and runs again.
     */
    fun finishIfScheduled(
        locations: StorageLocations,
        wrapper: KeyWrapper,
        emptied: List<File> = emptyList(),
        clearAppState: () -> Unit = {},
    ): Boolean {
        if (!isScheduled(locations)) return false
        wrapper.destroy()
        val temporary = listOf(locations.keyFile, locations.journalFile).map { File(it.path + ".tmp") }
        val targets = locations.items().map { it.live } + temporary +
            listOf(locations.stagingDir, locations.previousDir, locations.journalFile)
        val contents = emptied.flatMap { it.listFiles().orEmpty().asList() }
        (targets + contents).forEach(::shred)
        clearAppState()
        val left = (targets + contents).filter { it.exists() }
        if (left.isNotEmpty()) throw IOException("Secure delete left ${left.size} items behind")
        locations.wipeMarker.delete()
        return true
    }

    private fun shred(target: File) {
        target.walkBottomUp().forEach { file ->
            // An overwrite that fails must not stop the delete; the key is already gone.
            if (file.isFile) runCatching { overwrite(file) }
            file.delete()
        }
    }

    private fun overwrite(file: File) {
        RandomAccessFile(file, "rw").use { out ->
            val zeros = ByteArray(64 * 1024)
            var remaining = out.length()
            while (remaining > 0) {
                val n = minOf(remaining, zeros.size.toLong()).toInt()
                out.write(zeros, 0, n)
                remaining -= n
            }
            out.fd.sync()
        }
    }
}
