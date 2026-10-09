package com.nestra.remote.core.session

import com.nestra.remote.core.json.Json
import com.nestra.remote.core.json.long
import com.nestra.remote.core.json.str

/**
 * v0.4.0 file browser + transfer: pure logic (no Android API), unit-tested in :core.
 *
 * The native core (libnestra_viewer.so, nestra_files.rs) runs every file operation on a FILE_TRANSFER connection to
 * the PC and reports through onFileEvent(name, json):
 *   file_dir      {"name":"file_dir","value":"{\"id\":0,\"path\":..,\"entries\":[{name,entry_type,size}]}"}
 *   file_error    {"op":"dir|transfer|session","id":N,"message":".."}
 *   job_progress  {"id":N,"finished":B,"total":B,"percent":P}      (percent -1 = total unknown)
 *   job_done      {"id":N}
 *   job_error     {"id":N,"message":"..","cancelled":true?}
 * PC paths: "" = the PC user's home, "/" = the drive list ("C:"), "C:\\dir\\sub" = a folder.
 */
data class RemoteEntry(val name: String, val entryType: Int, val size: Long) {
    // upstream FileType: Dir 0, DirLink 2, DirDrive 3, File 4, FileLink 5
    val isDrive: Boolean get() = entryType == 3
    val isDirectory: Boolean get() = entryType == 0 || entryType == 2
    val isFile: Boolean get() = entryType == 4 || entryType == 5
}

data class RemoteListing(val path: String, val entries: List<RemoteEntry>)

sealed interface FileEvent {
    data class Dir(val listing: RemoteListing) : FileEvent
    data class Error(val op: String, val message: String) : FileEvent
    data class Progress(val id: Int, val finished: Long, val total: Long, val percent: Int) : FileEvent
    data class Done(val id: Int) : FileEvent
    data class JobError(val id: Int, val message: String, val cancelled: Boolean) : FileEvent
    data object Ignored : FileEvent
}

object RemoteFiles {
    /** Longest file name written on either side, in UTF-8 bytes (Android ext4/F2FS limit is 255). */
    const val MAX_NAME_BYTES = 200

    fun parse(name: String, json: String): FileEvent = try {
        val o = Json.obj(json)
        when (name) {
            "file_dir" -> {
                val inner = Json.obj(o.str("value") ?: "{}")
                if ((inner.long("id") ?: 0L) != 0L) FileEvent.Ignored else {
                    @Suppress("UNCHECKED_CAST")
                    val raw = inner["entries"] as? List<Any?> ?: emptyList()
                    val entries = raw.mapNotNull { e ->
                        @Suppress("UNCHECKED_CAST")
                        val m = e as? Map<String, Any?> ?: return@mapNotNull null
                        val n = m.str("name") ?: return@mapNotNull null
                        if (n.isEmpty() || n == "." || n == ".." || n.contains('/') || n.contains('\\')) return@mapNotNull null
                        RemoteEntry(n, (m.long("entry_type") ?: 4L).toInt(), m.long("size") ?: 0L)
                    }
                    FileEvent.Dir(RemoteListing(inner.str("path") ?: "", sort(entries)))
                }
            }
            "file_error" -> FileEvent.Error(o.str("op") ?: "dir", o.str("message") ?: "Unknown error")
            "job_progress" -> FileEvent.Progress(
                (o.long("id") ?: -1L).toInt(), o.long("finished") ?: 0L, o.long("total") ?: 0L, (o.long("percent") ?: -1L).toInt()
            )
            "job_done" -> FileEvent.Done((o.long("id") ?: -1L).toInt())
            "job_error" -> FileEvent.JobError((o.long("id") ?: -1L).toInt(), o.str("message") ?: "Transfer failed", o["cancelled"] == true)
            else -> FileEvent.Ignored
        }
    } catch (e: Json.ParseException) {
        FileEvent.Error("dir", "Unreadable answer from the PC")
    }

    /** Drives first, then folders, then files; case-insensitive by name. */
    fun sort(entries: List<RemoteEntry>): List<RemoteEntry> =
        entries.sortedWith(compareBy<RemoteEntry>({ if (it.isDrive) 0 else if (it.isDirectory) 1 else 2 }, { sortKey(it.name) }, { it.name }))

    /** Same order on every JVM / Android: diacritics folded (ą→a, ż→z, ł→l), case-insensitive. */
    fun sortKey(name: String): String =
        java.text.Normalizer.normalize(name.replace('ł', 'l').replace('Ł', 'L'), java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase(java.util.Locale.ROOT)

    /** The PC path of [entry] inside the listed folder [base]. */
    fun child(base: String, entry: RemoteEntry): String = when {
        entry.isDrive -> entry.name.trimEnd('\\') + "\\"
        base.isEmpty() || base == "/" -> entry.name
        base.endsWith("\\") || base.endsWith("/") -> base + entry.name
        else -> base + "\\" + entry.name
    }

    /** Parent folder; a drive root ("C:\") goes up to the drive list "/". */
    fun parent(path: String): String {
        val p = path.trimEnd('\\', '/')
        if (p.isEmpty() || p == "/" || (p.length == 2 && p[1] == ':')) return "/"
        val cut = maxOf(p.lastIndexOf('\\'), p.lastIndexOf('/'))
        return when {
            cut < 0 -> "/"
            cut <= 2 && p.length > 1 && p[1] == ':' -> p.take(2) + "\\"
            else -> p.substring(0, cut)
        }
    }

    fun title(path: String): String = when (path) { "" -> "PC home"; "/" -> "This PC (drives)"; else -> path }

    /**
     * A file name that is safe on Windows AND Android: no separators / reserved characters / control characters,
     * Unicode (Polish letters, emoji) kept, at most [MAX_NAME_BYTES] UTF-8 bytes with the extension preserved,
     * and different from every name in [existing].
     */
    fun safeName(name: String, existing: Set<String> = emptySet()): String {
        var n = name.map { c -> if (c in "\\/:*?\"<>|" || c.code < 0x20) '_' else c }.joinToString("").trim().trimEnd('.', ' ')
        if (n.isEmpty() || n == "." || n == "..") n = "file"
        val dot = n.lastIndexOf('.')
        val ext = if (dot > 0 && n.length - dot <= 16) n.substring(dot) else ""
        var stem = if (ext.isNotEmpty()) n.substring(0, dot) else n
        fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size
        while (stem.isNotEmpty() && bytes(stem + ext) > MAX_NAME_BYTES) stem = stem.dropLast(1)
        if (stem.isEmpty()) stem = "file"
        val base = stem + ext
        if (base !in existing) return base
        for (i in 2..999) {
            val candidate = "$stem ($i)$ext"
            if (candidate !in existing) return candidate
        }
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", b / 1024.0 / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
    }

    fun progressText(verb: String, name: String, p: FileEvent.Progress): String =
        if (p.percent >= 0) "$verb $name — ${p.percent}% (${formatBytes(p.finished)} of ${formatBytes(p.total)})"
        else "$verb $name — ${formatBytes(p.finished)}"
}
