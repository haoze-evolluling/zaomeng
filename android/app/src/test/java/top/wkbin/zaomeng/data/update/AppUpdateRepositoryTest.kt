package top.wkbin.zaomeng.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test
    fun newerReleaseVersionsAreDetected() {
        assertTrue(isNewerVersion("v1.1", "1.0.1"))
        assertTrue(isNewerVersion("1.2.0", "1.1.9"))
        assertTrue(isNewerVersion("v2", "1.9.9"))
    }

    @Test
    fun currentOrOlderReleaseVersionsAreIgnored() {
        assertFalse(isNewerVersion("v1.1", "1.1.0"))
        assertFalse(isNewerVersion("1.0.9", "1.1.0"))
        assertFalse(isNewerVersion("preview", "1.1.0"))
    }
}
