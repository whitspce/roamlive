package dev.whitespc.roam.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SafeArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun safeNameNormalizesDirectorySuffix() {
        assertEquals("folder/file.html", safeArchiveName("folder/file.html"))
        assertEquals("folder", safeArchiveName("folder/"))
    }

    @Test
    fun safeNameRejectsTraversalAbsoluteAndAmbiguousPaths() {
        val unsafe = listOf(
            "",
            "/absolute",
            "../outside",
            "folder/../outside",
            "folder/./file",
            "folder//file",
            "folder\\file",
            "folder\nfile",
        )

        unsafe.forEach { name ->
            assertFails { safeArchiveName(name) }
        }
    }

    @Test
    fun resolverKeepsOutputUnderCanonicalRoot() {
        val root = temporaryFolder.newFolder("root")
        val resolved = resolveArchiveFile(root, "nested/file.txt")

        assertTrue(resolved.path.startsWith(root.canonicalPath + File.separator))
        assertEquals("nested/file.txt", resolved.relativeTo(root).invariantSeparatorsPath)
        assertFails { resolveArchiveFile(root, "../outside") }
    }

    @Test
    fun budgetEnforcesActualEntryBytesWhenMetadataIsUnknown() {
        val budget = SafeArchiveBudget(maxEntries = 2, maxEntryBytes = 4, maxTotalBytes = 10)
        budget.begin(ZipEntry("entry"))

        assertFails {
            budget.copy(
                ByteArrayInputStream(ByteArray(5)),
                ByteArrayOutputStream(),
            )
        }
    }

    @Test
    fun budgetEnforcesCumulativeArchiveBytes() {
        val budget = SafeArchiveBudget(maxEntries = 2, maxEntryBytes = 8, maxTotalBytes = 6)
        budget.begin(ZipEntry("first"))
        assertEquals(
            4L,
            budget.copy(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream()),
        )
        budget.finish()
        budget.begin(ZipEntry("second"))

        assertFails {
            budget.copy(ByteArrayInputStream(ByteArray(3)), ByteArrayOutputStream())
        }
    }

    @Test
    fun budgetEnforcesCallerSpecificLimitBeforeGrowingOutput() {
        val budget = SafeArchiveBudget(maxEntries = 1, maxEntryBytes = 20, maxTotalBytes = 20)
        budget.begin(ZipEntry("prefs.json"))
        val output = ByteArrayOutputStream()

        assertFails {
            budget.copy(ByteArrayInputStream(ByteArray(6)), output, entryLimitBytes = 5)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun budgetRejectsDuplicateNormalizedNamesAndUnfinishedEntries() {
        val budget = SafeArchiveBudget(maxEntries = 3, maxEntryBytes = 10, maxTotalBytes = 20)
        budget.begin(ZipEntry("folder/"))
        budget.discard(ByteArrayInputStream(ByteArray(0)))

        assertFails { budget.begin(ZipEntry("other")) }
        budget.finish()
        assertFails { budget.begin(ZipEntry("folder")) }
    }

    private fun assertFails(block: () -> Unit) {
        val result = runCatching(block)
        assertTrue("expected validation to fail", result.isFailure)
    }
}
