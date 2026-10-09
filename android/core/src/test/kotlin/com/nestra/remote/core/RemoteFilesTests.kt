package com.nestra.remote.core

import com.nestra.remote.core.json.Json
import com.nestra.remote.core.session.FileEvent
import com.nestra.remote.core.session.RemoteEntry
import com.nestra.remote.core.session.RemoteFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFilesTests {
    private fun dirEvent(id: Int, path: String, entries: List<Map<String, Any?>>): String =
        Json.write(mapOf("name" to "file_dir", "is_local" to "false", "value" to Json.write(mapOf("id" to id, "path" to path, "entries" to entries))))

    @Test fun parsesTheUpstreamDirectoryAnswerWithDrivesFoldersFilesAndUnicode() {
        val json = dirEvent(0, "C:\\Users\\Mazi", listOf(
            mapOf("name" to "zdjęcie wakacje.jpg", "entry_type" to 4, "size" to 2_097_152, "modified_time" to 1),
            mapOf("name" to "Pulpit", "entry_type" to 0, "size" to 0),
            mapOf("name" to "Łódź – raport.zip", "entry_type" to 4, "size" to 20_971_520),
            mapOf("name" to "Dokumenty", "entry_type" to 2, "size" to 0),
            mapOf("name" to "..\\evil", "entry_type" to 4, "size" to 1),        // never shown
            mapOf("name" to "", "entry_type" to 4, "size" to 1),
        ))
        val e = RemoteFiles.parse("file_dir", json) as FileEvent.Dir
        assertEquals("C:\\Users\\Mazi", e.listing.path)
        assertEquals(listOf("Dokumenty", "Pulpit", "Łódź – raport.zip", "zdjęcie wakacje.jpg"), e.listing.entries.map { it.name })
        assertTrue(e.listing.entries[0].isDirectory && e.listing.entries[3].isFile)
        assertEquals(20_971_520L, e.listing.entries[2].size)
        // the file list of a transfer job (id != 0) never replaces the listing
        assertEquals(FileEvent.Ignored, RemoteFiles.parse("file_dir", dirEvent(1001, "C:\\x.zip", listOf(mapOf("name" to "", "entry_type" to 4, "size" to 5)))))
        val drives = RemoteFiles.parse("file_dir", dirEvent(0, "/", listOf(mapOf("name" to "D:", "entry_type" to 3), mapOf("name" to "C:", "entry_type" to 3)))) as FileEvent.Dir
        assertEquals(listOf("C:", "D:"), drives.listing.entries.map { it.name })
        assertTrue(drives.listing.entries.all { it.isDrive })
    }

    @Test fun parsesProgressDoneErrorsAndCancel() {
        assertEquals(FileEvent.Progress(1001, 10_485_760, 20_971_520, 50),
            RemoteFiles.parse("job_progress", """{"id":1001,"finished":10485760,"total":20971520,"percent":50}"""))
        assertEquals(FileEvent.Done(1001), RemoteFiles.parse("job_done", """{"id":1001}"""))
        assertEquals(FileEvent.JobError(1002, "A file with this name already exists; nothing was overwritten", false),
            RemoteFiles.parse("job_error", """{"id":1002,"message":"A file with this name already exists; nothing was overwritten"}"""))
        assertEquals(FileEvent.JobError(1003, "Cancelled", true), RemoteFiles.parse("job_error", """{"id":1003,"message":"Cancelled","cancelled":true}"""))
        assertEquals(FileEvent.Error("dir", "Access is denied. (os error 5)"),
            RemoteFiles.parse("file_error", """{"op":"dir","id":0,"message":"Access is denied. (os error 5)"}"""))
        assertEquals(FileEvent.Error("dir", "Unreadable answer from the PC"), RemoteFiles.parse("file_dir", "not json"))
        assertEquals(FileEvent.Ignored, RemoteFiles.parse("empty_dirs", "{}"))
    }

    @Test fun navigatesHomeDrivesFoldersAndUp() {
        val dir = RemoteEntry("Pulpit", 0, 0); val drive = RemoteEntry("C:", 3, 0)
        assertEquals("C:\\", RemoteFiles.child("/", drive))
        assertEquals("C:\\Users", RemoteFiles.child("C:\\", RemoteEntry("Users", 0, 0)))
        assertEquals("C:\\Users\\Mazi\\Pulpit", RemoteFiles.child("C:\\Users\\Mazi", dir))
        assertEquals("C:\\Users\\Mazi", RemoteFiles.parent("C:\\Users\\Mazi\\Pulpit"))
        assertEquals("C:\\", RemoteFiles.parent("C:\\Users"))
        assertEquals("/", RemoteFiles.parent("C:\\"))
        assertEquals("/", RemoteFiles.parent("C:"))
        assertEquals("/", RemoteFiles.parent("/"))
        assertEquals("/", RemoteFiles.parent(""))
        assertEquals("PC home", RemoteFiles.title("")); assertEquals("This PC (drives)", RemoteFiles.title("/"))
    }

    @Test fun safeNamesKeepUnicodeAndSpacesButNeverSeparatorsOrOverlongNames() {
        assertEquals("Zażółć gęślą jaźń.txt", RemoteFiles.safeName("Zażółć gęślą jaźń.txt"))
        assertEquals("plik ze spacjami w nazwie.txt", RemoteFiles.safeName("plik ze spacjami w nazwie.txt"))
        assertEquals("a_b_c_.txt", RemoteFiles.safeName("a/b\\c:.txt"))
        assertEquals("_.._evil", RemoteFiles.safeName("/../evil"))
        assertEquals("file", RemoteFiles.safeName(".."))
        assertEquals("raport (2).zip", RemoteFiles.safeName("raport.zip", setOf("raport.zip")))
        assertEquals("raport (3).zip", RemoteFiles.safeName("raport.zip", setOf("raport.zip", "raport (2).zip")))
        val long = RemoteFiles.safeName("ą".repeat(300) + ".jpg")
        assertTrue(long.endsWith(".jpg"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= RemoteFiles.MAX_NAME_BYTES)
        assertFalse(RemoteFiles.safeName("x\u0000y").contains('\u0000'))
    }

    @Test fun progressTextIsReadable() {
        assertEquals("Downloading a.zip — 50% (10.0 MB of 20.0 MB)",
            RemoteFiles.progressText("Downloading", "a.zip", FileEvent.Progress(1, 10_485_760, 20_971_520, 50)))
        assertEquals("Uploading a.txt — 1.0 KB", RemoteFiles.progressText("Uploading", "a.txt", FileEvent.Progress(1, 1024, 0, -1)))
        assertEquals("512 B", RemoteFiles.formatBytes(512))
    }
}
