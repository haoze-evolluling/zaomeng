package top.wkbin.zaomeng.feature.relations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.zaomeng.data.api.RelationItemDto

class RelationGraphTest {
    @Test
    fun graphNodesDeduplicateRelationParticipants() {
        val nodes = relationGraphNodes(
            listOf(
                RelationItemDto(pairKey = "a_b", characters = listOf("甲", "乙")),
                RelationItemDto(pairKey = "b_c", characters = listOf("乙", "丙")),
            ),
        )

        assertEquals(listOf("甲", "乙", "丙"), nodes.map { it.name })
        assertTrue(nodes.all { it.x in 0f..1f && it.y in 0f..1f })
    }
}
