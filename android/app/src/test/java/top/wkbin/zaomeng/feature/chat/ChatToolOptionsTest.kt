package top.wkbin.zaomeng.feature.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolOptionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun directorOptionsKeepBeatDirectionEffectAndRisk() {
        val payload = json.parseToJsonElement(
            """
            {
              "options": [
                {
                  "title": "雨夜摊牌",
                  "focus": "甲与乙",
                  "beat": "乙拿出那封旧信",
                  "direction": "迫使甲当场回应",
                  "expected_effect": "秘密关系被公开",
                  "risk": "冲突升级过快"
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val option = payload.extractDirectorOptions().single()

        assertEquals("plot", option.messageKind)
        assertEquals("乙拿出那封旧信；迫使甲当场回应", option.value)
        assertTrue(option.description.contains("秘密关系被公开"))
        assertTrue(option.description.contains("冲突升级过快"))
    }
}
