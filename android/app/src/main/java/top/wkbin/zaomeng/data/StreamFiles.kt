package top.wkbin.zaomeng.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal const val STREAM_BUFFER_SIZE = 64 * 1024

internal fun copyStream(
    source: InputStream,
    destination: OutputStream,
    bufferSize: Int = STREAM_BUFFER_SIZE,
): Long {
    require(bufferSize > 0) { "bufferSize must be positive." }
    val buffer = ByteArray(bufferSize)
    var totalBytes = 0L
    while (true) {
        val count = source.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val value = source.read()
            if (value < 0) break
            destination.write(value)
            totalBytes += 1
        } else {
            destination.write(buffer, 0, count)
            totalBytes += count
        }
    }
    return totalBytes
}

internal fun streamToTempFile(
    source: InputStream,
    directory: File,
    prefix: String = "zaomeng-export-",
    suffix: String = ".zip",
): StreamedTempFile {
    check(directory.exists() || directory.mkdirs()) { "无法准备导出缓存目录。" }
    val file = File.createTempFile(prefix, suffix, directory)
    return try {
        val byteCount = file.outputStream().buffered().use { destination ->
            copyStream(source, destination)
        }
        StreamedTempFile(file, byteCount)
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}

internal data class StreamedTempFile(
    val file: File,
    val byteCount: Long,
)
