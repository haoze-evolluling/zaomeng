package top.wkbin.zaomeng.feature.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val MaxPluginPackageBytes = 10 * 1024 * 1024
private const val MaxPluginPackageFiles = 500

internal fun ContentResolver.displayName(uri: Uri): String =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "plugin.zip"

internal fun ContentResolver.readPluginZip(uri: Uri): Pair<String, ByteArray> {
    val bytes = openInputStream(uri)?.use { it.readLimitedBytes(MaxPluginPackageBytes) }
        ?: error("无法读取所选插件包。")
    require(bytes.size <= MaxPluginPackageBytes) { "插件包不能超过 10 MB。" }
    return displayName(uri) to bytes
}

internal fun ContentResolver.packPluginDirectory(treeUri: Uri): Pair<String, ByteArray> {
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootName = queryDocumentName(treeUri, rootId).ifBlank { "plugin" }
    val output = ByteArrayOutputStream()
    var fileCount = 0
    var totalBytes = 0
    ZipOutputStream(output).use { zip ->
        fun appendDirectory(documentId: String, prefix: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idColumn)
                    val name = safeEntryName(cursor.getString(nameColumn))
                    val mimeType = cursor.getString(typeColumn)
                    val path = if (prefix.isBlank()) name else "$prefix/$name"
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        appendDirectory(childId, path)
                    } else {
                        fileCount += 1
                        require(fileCount <= MaxPluginPackageFiles) { "插件目录文件不能超过 500 个。" }
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val bytes = openInputStream(childUri)?.use {
                            it.readLimitedBytes(MaxPluginPackageBytes - totalBytes)
                        } ?: error("无法读取插件文件：$path")
                        totalBytes += bytes.size
                        require(totalBytes <= MaxPluginPackageBytes) { "插件目录内容不能超过 10 MB。" }
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            } ?: error("无法读取所选插件目录。")
        }
        appendDirectory(rootId, "")
    }
    require(fileCount > 0) { "所选插件目录为空。" }
    return "$rootName.zip" to output.toByteArray()
}

private fun ContentResolver.queryDocumentName(treeUri: Uri, documentId: String): String {
    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    return query(
        documentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }.orEmpty()
}

private fun safeEntryName(value: String): String {
    val name = value.trim()
    require(name.isNotBlank() && name != "." && name != "..") { "插件目录包含无效文件名。" }
    require('/' !in name && '\\' !in name && ':' !in name) { "插件目录包含不安全文件名：$name" }
    return name
}

private fun InputStream.readLimitedBytes(limit: Int): ByteArray {
    require(limit >= 0) { "插件内容超过 10 MB。" }
    val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "插件内容超过 10 MB。" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
