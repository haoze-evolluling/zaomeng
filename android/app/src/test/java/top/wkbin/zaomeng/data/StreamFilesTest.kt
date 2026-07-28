package top.wkbin.zaomeng.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamFilesTest {
    @Test
    fun `copyStream copies multiple buffers and reports exact size`() {
        val bytes = ByteArray(STREAM_BUFFER_SIZE * 2 + 317) { index -> (index % 251).toByte() }
        val destination = ByteArrayOutputStream()

        val copied = copyStream(ByteArrayInputStream(bytes), destination)

        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, destination.toByteArray())
    }

    @Test
    fun `copyStream never requests an unbounded read`() {
        val source = BoundedReadInputStream(ByteArray(STREAM_BUFFER_SIZE + 11) { 7 })
        val destination = ByteArrayOutputStream()

        copyStream(source, destination)

        assertEquals(STREAM_BUFFER_SIZE, source.largestRequestedRead)
    }

    @Test
    fun `streamToTempFile removes a partial file when reading fails`() {
        val directory = Files.createTempDirectory("zaomeng-stream-test-").toFile()
        try {
            assertThrows(IOException::class.java) {
                streamToTempFile(FailingInputStream(), directory)
            }

            assertFalse(directory.listFiles().orEmpty().any())
        } finally {
            directory.deleteRecursively()
        }
    }
}

private class BoundedReadInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    var largestRequestedRead: Int = 0
        private set

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(length <= STREAM_BUFFER_SIZE) { "Requested an oversized buffer." }
        largestRequestedRead = maxOf(largestRequestedRead, length)
        return delegate.read(buffer, offset, length)
    }
}

private class FailingInputStream : InputStream() {
    private var delivered = false

    override fun read(): Int = throw IOException("forced source failure")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (delivered) throw IOException("forced source failure")
        delivered = true
        buffer[offset] = 1
        return 1
    }
}
