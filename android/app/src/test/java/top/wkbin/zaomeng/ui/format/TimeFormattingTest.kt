package top.wkbin.zaomeng.ui.format

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormattingTest {
    @Test
    fun `utc timestamp is displayed in the device time zone`() = withLocaleAndTimeZone {
        assertEquals(
            "2026-07-27 10:15",
            "2026-07-27T02:15:30.123456Z".toLocalDateTimeDisplay(),
        )
    }

    @Test
    fun `explicit offset is converted to the device time zone`() = withLocaleAndTimeZone {
        assertEquals(
            "2026-07-27 10:15",
            "2026-07-27T04:15:30+02:00".toLocalDateTimeDisplay(),
        )
    }

    @Test
    fun `invalid timestamp returns caller fallback`() = withLocaleAndTimeZone {
        assertEquals("未知", "not-a-time".toLocalDateTimeDisplay("未知"))
    }

    private fun withLocaleAndTimeZone(block: () -> Unit) {
        val oldLocale = Locale.getDefault()
        val oldTimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            block()
        } finally {
            Locale.setDefault(oldLocale)
            TimeZone.setDefault(oldTimeZone)
        }
    }
}
