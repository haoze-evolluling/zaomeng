package top.wkbin.zaomeng.feature.importbook

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportDocumentLoaderTest {
    @Test
    fun `utf8 novel is kept and missing extension is restored`() {
        val text = "黛玉望着窗外。"

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "content://downloads/红楼梦",
            bytes = text.toByteArray(),
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals("红楼梦.txt", document.fileName)
        assertEquals("UTF-8", document.sourceEncoding)
        assertEquals(text, document.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `novel document reports normalized character and sentence counts`() {
        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "sample.txt",
            bytes = "One. Two! Three;\nFour?".toByteArray(),
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals(22, document.charCount)
        assertEquals(4, document.sentenceCount)
    }

    @Test
    fun `utf16 novel is normalized to utf8`() {
        val text = "宝玉说道：好。"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "红楼梦.txt",
            bytes = utf16,
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals("UTF-16 LE", document.sourceEncoding)
        assertEquals(text, document.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `gb18030 novel is normalized to utf8`() {
        val text = "悟空举起金箍棒。"

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "西游记.txt",
            bytes = text.toByteArray(Charset.forName("GB18030")),
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals("GB18030", document.sourceEncoding)
        assertEquals(text, document.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `epub is extracted into normalized utf8 novel text`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry("OEBPS/chapter-1.xhtml"))
            archive.write(
                "<html><body><h1>第一章</h1><p>宝玉来了。</p></body></html>".toByteArray(),
            )
            archive.closeEntry()
        }

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "红楼梦.epub",
            bytes = output.toByteArray(),
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals("红楼梦.txt", document.fileName)
        assertEquals("EPUB", document.sourceEncoding)
        assertEquals("第一章\n宝玉来了。", document.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `zip package is accepted even when provider omits extension`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "backup",
            bytes = zip,
            expectedKind = ImportDocumentKind.RunPackage,
        )

        assertEquals("backup.zaomeng-run.zip", document.fileName)
        assertArrayEquals(zip, document.bytes)
    }

    @Test
    fun `zip selected as novel gives actionable error`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ImportDocumentLoader.prepareImportDocument(
                displayName = "book.txt",
                bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04),
                expectedKind = ImportDocumentKind.NovelText,
            )
        }

        assertEquals("这个文件是压缩包，请使用“导入书卷包”。", error.message)
    }

    @Test
    fun `plain text selected as package is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ImportDocumentLoader.prepareImportDocument(
                displayName = "book.zaomeng-run.zip",
                bytes = "not a zip".toByteArray(),
                expectedKind = ImportDocumentKind.RunPackage,
            )
        }

        assertEquals("这不是有效的 ZIP 书卷包，请选择导出的 .zaomeng-run.zip 文件。", error.message)
    }

    @Test
    fun `malformed utf16 bom gives readable encoding error`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ImportDocumentLoader.prepareImportDocument(
                displayName = "broken.txt",
                bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41),
                expectedKind = ImportDocumentKind.NovelText,
            )
        }

        assertEquals("无法识别 TXT 编码，请先把文件另存为 UTF-8 后重试。", error.message)
    }

    @Test
    fun `package limit is explicitly an Android compressed file limit`() {
        assertEquals(64 * 1024 * 1024, ImportDocumentLoader.MAX_PACKAGE_BYTES)
        assertEquals(
            "书卷包过大，Android 客户端当前最多支持 64 MB 的压缩文件。",
            ImportDocumentLoader.fileTooLargeMessage(
                ImportDocumentKind.RunPackage,
                ImportDocumentLoader.MAX_PACKAGE_BYTES,
            ),
        )
    }
}
