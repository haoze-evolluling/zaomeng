package top.wkbin.zaomeng.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelReasoningCapabilitiesTest {
    @Test
    fun capabilitiesAreModelSpecific() {
        assertEquals(
            listOf("auto", "off", "low", "medium", "high", "xhigh"),
            modelReasoningEfforts("openai-compatible", "https://api.deepseek.com", "deepseek-v4-pro"),
        )
        assertEquals(
            listOf("auto", "low", "medium", "high"),
            modelReasoningEfforts("openai", "", "o3-mini"),
        )
        assertEquals(
            listOf("auto", "off"),
            modelReasoningEfforts(
                "openai-compatible",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus",
            ),
        )
        assertEquals(
            listOf("auto"),
            modelReasoningEfforts("openai", "", "gpt-4.1"),
        )
    }
}
