package top.wkbin.zaomeng.feature.importbook

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ImportDocumentKind {
    NovelText,
    RunPackage,
}

data class ImportDocument(
    val fileName: String,
    val bytes: ByteArray,
    val kind: ImportDocumentKind,
    val sourceEncoding: String = "",
)

object ImportDocumentLoader {
    internal const val MAX_NOVEL_BYTES = 24 * 1024 * 1024
    internal const val MAX_PACKAGE_BYTES = 64 * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun load(
        contentResolver: ContentResolver,
        uri: Uri,
        expectedKind: ImportDocumentKind,
    ): ImportDocument = withContext(Dispatchers.IO) {
        val metadata = readMetadata(contentResolver, uri)
        val maxBytes = when (expectedKind) {
            ImportDocumentKind.NovelText -> MAX_NOVEL_BYTES
            ImportDocumentKind.RunPackage -> MAX_PACKAGE_BYTES
        }
        metadata.size?.takeIf { it > maxBytes }?.let {
            throw IllegalArgumentException(fileTooLargeMessage(expectedKind, maxBytes))
        }

        val rawBytes = contentResolver.openInputStream(uri)?.use { input ->
            input.readBytesUpTo(maxBytes, expectedKind)
        } ?: throw IllegalArgumentException("无法读取所选文件，请换一个文件位置后重试。")

        prepareImportDocument(
            displayName = metadata.name ?: uri.lastPathSegment.orEmpty(),
            bytes = rawBytes,
            expectedKind = expectedKind,
        )
    }

    fun prepareImportDocument(
        displayName: String,
        bytes: ByteArray,
        expectedKind: ImportDocumentKind,
    ): ImportDocument {
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("所选文件是空的。")
        }
        return when (expectedKind) {
            ImportDocumentKind.NovelText -> prepareNovel(displayName, bytes)
            ImportDocumentKind.RunPackage -> preparePackage(displayName, bytes)
        }
    }

    private fun prepareNovel(displayName: String, bytes: ByteArray): ImportDocument {
        if (bytes.hasZipSignature()) {
            throw IllegalArgumentException("这个文件是压缩包，请使用“导入书卷包”。")
        }
        val sanitizedName = sanitizeDisplayName(displayName)
        val decoded = decodeNovelText(bytes)
        if (decoded.text.isBlank()) {
            throw IllegalArgumentException("所选 TXT 没有可导入的正文。")
        }
        if ('\u0000' in decoded.text) {
            throw IllegalArgumentException("无法识别 TXT 编码，请先把文件另存为 UTF-8 后重试。")
        }
        val fileName = sanitizedName.ifBlank { "novel.txt" }.let { name ->
            if (name.endsWith(".txt", ignoreCase = true)) name else "$name.txt"
        }
        return ImportDocument(
            fileName = fileName,
            bytes = decoded.text.removePrefix("\uFEFF").toByteArray(Charsets.UTF_8),
            kind = ImportDocumentKind.NovelText,
            sourceEncoding = decoded.encoding,
        )
    }

    private fun preparePackage(displayName: String, bytes: ByteArray): ImportDocument {
        if (!bytes.hasZipSignature()) {
            throw IllegalArgumentException("这不是有效的 ZIP 书卷包，请选择导出的 .zaomeng-run.zip 文件。")
        }
        val sanitizedName = sanitizeDisplayName(displayName)
        val fileName = when {
            sanitizedName.isBlank() -> "imported.zaomeng-run.zip"
            sanitizedName.endsWith(".zip", ignoreCase = true) -> sanitizedName
            else -> "$sanitizedName.zaomeng-run.zip"
        }
        return ImportDocument(
            fileName = fileName,
            bytes = bytes,
            kind = ImportDocumentKind.RunPackage,
        )
    }

    private fun decodeNovelText(bytes: ByteArray): DecodedText {
        if (bytes.startsWith(0xEF, 0xBB, 0xBF)) {
            return DecodedText(
                decodeStrictOrNull(bytes.copyOfRange(3, bytes.size), Charsets.UTF_8)
                    ?: throw unsupportedEncoding(),
                "UTF-8",
            )
        }
        if (bytes.startsWith(0xFF, 0xFE)) {
            return DecodedText(
                decodeStrictOrNull(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
                    ?: throw unsupportedEncoding(),
                "UTF-16 LE",
            )
        }
        if (bytes.startsWith(0xFE, 0xFF)) {
            return DecodedText(
                decodeStrictOrNull(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)
                    ?: throw unsupportedEncoding(),
                "UTF-16 BE",
            )
        }
        decodeStrictOrNull(bytes, Charsets.UTF_8)?.let { return DecodedText(it, "UTF-8") }
        val gb18030 = Charset.forName("GB18030")
        decodeStrictOrNull(bytes, gb18030)?.let { return DecodedText(it, "GB18030") }
        throw unsupportedEncoding()
    }

    private fun decodeStrictOrNull(bytes: ByteArray, charset: Charset): String? = try {
        decodeStrict(bytes, charset)
    } catch (_: CharacterCodingException) {
        null
    }

    @Throws(CharacterCodingException::class)
    private fun decodeStrict(bytes: ByteArray, charset: Charset): String = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun readMetadata(contentResolver: ContentResolver, uri: Uri): DocumentMetadata {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                DocumentMetadata(
                    name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getString),
                    size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong),
                )
            }
        }.getOrNull() ?: DocumentMetadata()
    }

    private fun InputStream.readBytesUpTo(maxBytes: Int, kind: ImportDocumentKind): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw IllegalArgumentException(fileTooLargeMessage(kind, maxBytes))
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun fileTooLargeMessage(kind: ImportDocumentKind, maxBytes: Int): String = when (kind) {
        ImportDocumentKind.NovelText ->
            "TXT 小说过大，Android 客户端当前最多支持 ${maxBytes / 1024 / 1024} MB。"
        ImportDocumentKind.RunPackage ->
            "书卷包过大，Android 客户端当前最多支持 ${maxBytes / 1024 / 1024} MB 的压缩文件。"
    }

    private fun unsupportedEncoding(): IllegalArgumentException =
        IllegalArgumentException("无法识别 TXT 编码，请先把文件另存为 UTF-8 后重试。")

    private fun sanitizeDisplayName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()

    private fun ByteArray.hasZipSignature(): Boolean =
        startsWith(0x50, 0x4B, 0x03, 0x04) ||
            startsWith(0x50, 0x4B, 0x05, 0x06) ||
            startsWith(0x50, 0x4B, 0x07, 0x08)

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index].toInt() and 0xFF == prefix[index] }

    private data class DecodedText(val text: String, val encoding: String)
    private data class DocumentMetadata(val name: String? = null, val size: Long? = null)
}
