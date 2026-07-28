package top.wkbin.zaomeng.feature.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolOptionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun associationsPreferReadySuggestionAndPreserveFallbackDirection() {
        val payload = json.parseToJsonElement(
            """
            {
              "options": [
                {
                  "label": "追问旧事",
                  "direction": "让甲追问乙隐瞒的过去",
                  "suggestion": "你一直没有告诉我，那天究竟发生了什么？"
                },
                {
                  "label": "观察反应",
                  "direction": "让甲先观察乙的神情"
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val options = payload.extractAssociationOptions(sessionMode = "observe")

        assertEquals("narration", options[0].messageKind)
        assertEquals("你一直没有告诉我，那天究竟发生了什么？", options[0].value)
        assertEquals("", options[0].suggestionDirection)
        assertEquals("让甲先观察乙的神情", options[1].suggestionDirection)
    }

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
