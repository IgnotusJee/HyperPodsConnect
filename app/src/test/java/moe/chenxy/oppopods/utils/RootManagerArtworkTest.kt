package moe.chenxy.oppopods.utils

import moe.chenxy.oppopods.config.PodImageResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootManagerArtworkTest {
    @Test
    fun parsesVerifiedAir5sEquipmentRow() {
        assertEquals(
            MelodyEquipmentRecord("06C810", "1", "OPPO Enco Air5s"),
            RootManager.parseMelodyEquipmentRow("06C810|1|OPPO Enco Air5s"),
        )
    }

    @Test
    fun rejectsMalformedOfficialIdentifiers() {
        assertNull(RootManager.parseMelodyEquipmentRow("../../data|1|OPPO Enco Air5s"))
        assertNull(RootManager.parseMelodyEquipmentRow("06C810|blue|OPPO Enco Air5s"))
        assertNull(RootManager.parseMelodyEquipmentRow("06C810|1|"))
    }

    @Test
    fun parsesOptionalControlArtworkBySemanticRole() {
        val resources = RootManager.parseMelodyArtworkConfig(
            """{
                "detailImageRes":{"path":"res/image/img_detail.png"},
                "boxImageRes":{"path":"res/image/img_box.png"},
                "capsuleVideoRes":{"path":"res/video/capsule.webp"}
            }""".trimIndent(),
        )

        assertEquals("res/image/img_detail.png", resources[PodImageResource.DETAIL])
        assertEquals("res/image/img_box.png", resources[PodImageResource.BOX])
        assertEquals("res/video/capsule.webp", resources[PodImageResource.CAPSULE_ANIMATION])
        assertNull(resources[PodImageResource.LEFT])
    }

    @Test
    fun parsesDetailAnimationAndRejectsTraversal() {
        assertEquals(
            "res/raw/detail_model.webp",
            RootManager.parseMelodyDetailConfig(
                """{"modelWebp":{"path":"res/raw/detail_model.webp"}}""",
            )[PodImageResource.HERO_ANIMATION],
        )
        assertEquals(
            emptyMap<PodImageResource, String>(),
            RootManager.parseMelodyDetailConfig(
                """{"modelWebp":{"path":"../../detail_model.webp"}}""",
            ),
        )
    }
}
