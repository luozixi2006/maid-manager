package com.miniichat.tasks

import android.app.Application
import com.miniichat.data.Assistant
import com.miniichat.tasks.agent.OfficeText
import com.miniichat.proactive.ProactivePolicy
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CompanionFeaturesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun rejects(block: () -> Unit) { try { block(); fail("Should reject") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
    @Test fun directorySelectionPersistsAndFindsUnfamiliarNames() {
        File(folder.root, "Documents/Project_2026/Materials").mkdirs()
        File(folder.root, "Download/课程").mkdirs()
        File(folder.root, "Android/data/private").mkdirs()
        val result = FolderSelection.children(folder.root, "", "materials")
        assertEquals(listOf("Documents/Project_2026/Materials"), result)
        assertTrue(FolderSelection.children(folder.root, "", "private").isEmpty())
        assertEquals("下载 / 课程", FolderSelection.label("Download/课程"))
        val app = RuntimeEnvironment.getApplication()
        FolderSelection.remember(app, result.single())
        assertEquals(result.single(), FolderSelection.saved(app))
        rejects { FolderSelection.safeDirectory(folder.root, "../private") }
        rejects { FolderSelection.safeDirectory(folder.root, "Android/data") }
        rejects { FolderSelection.safeDirectory(folder.root, "Documents/missing") }
    }
    @Test fun fileEventsIncludeNonPdfAndNeverReadContent() {
        File(folder.root, "photo.jpg").writeText("image bytes")
        File(folder.root, "plan.docx").writeText("not parsed during polling")
        File(folder.root, "music.mp3").writeText("private contents")
        File(folder.root, ".maid-recovery").mkdir()
        File(folder.root, ".maid-recovery/hidden").writeText("secret")
        val signature = TriggerEngine.fileSignature(folder.root)
        assertTrue(signature.contains("photo.jpg:")); assertTrue(signature.contains("plan.docx:")); assertTrue(signature.contains("music.mp3:"))
        assertFalse(signature.contains("private contents")); assertFalse(signature.contains("hidden"))
    }
    @Test fun personaConsentHasNoHiddenMasterGateAndPreservesLegacyOff() {
        val legacy = Assistant("p", "小夏", proactiveEnabled = true)
        assertFalse(legacy.canContact(false)); assertTrue(legacy.canContact(true))
        assertTrue(legacy.copy(proactiveConsentVersion = 1).canContact(false))
        assertFalse(legacy.copy(proactiveConsentVersion = 1, proactiveEnabled = false).canContact(true))
        assertFalse(Assistant("new", "新角色").canContact(true))
        assertTrue(ProactivePolicy.initialDelayMillis(0.5) in 300_000..900_000)
    }
    private fun office(name: String, entries: Map<String, String>): File = File(folder.root, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }
    @Test fun readsWordPowerPointAndExcelSharedStrings() {
        assertTrue(OfficeText.read(office("a.docx", mapOf("word/document.xml" to "<doc><p><t>中文会议纪要</t></p></doc>"))).contains("中文会议纪要"))
        assertTrue(OfficeText.read(office("a.pptx", mapOf("ppt/slides/slide1.xml" to "<slide><p><t>方案摘要</t></p></slide>"))).contains("方案摘要"))
        val sheet = office("a.xlsx", mapOf("xl/sharedStrings.xml" to "<sst><si><t>课程名称</t></si></sst>", "xl/worksheets/sheet1.xml" to "<sheet><row><c t=\"s\"><v>0</v></c><c><v>123</v></c></row></sheet>"))
        assertTrue(OfficeText.read(sheet).contains("课程名称 123"))
    }
    @Test fun rejectsUnsafeXmlAndZipExpansion() {
        rejects { OfficeText.read(office("unsafe.docx", mapOf("word/document.xml" to "<!DOCTYPE x><doc/>"))) }
        rejects { OfficeText.read(office("large.docx", mapOf("word/document.xml" to "<doc>" + "x".repeat(1100000) + "</doc>"))) }
    }
}
