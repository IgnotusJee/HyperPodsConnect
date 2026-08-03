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

    @Test
    fun parsesSonyCloudRowsAndCollapsesSharedVisualColorIds() {
        val imageUrl = "https://hpc-image.data-gateway.seeds.services/8ea91f30-6237-4b4e-b261-f26a9d199cf7.png"
        val json = """{
            "data":{"HPC":{"getAllCloudModelInfos":[
                {"model_id":"0x31","model_number":"0x05","model_name":"WH-1000XM4",
                 "model_color_id":"0x00","sca_image_image_url":"$imageUrl",
                 "sca_anime_image_url":"","sca_source_color":"FF494948"},
                {"model_id":"0x31","model_number":"0x05","model_name":"WH-1000XM4",
                 "model_color_id":"0x01","sca_image_image_url":"$imageUrl",
                 "sca_anime_image_url":"","sca_source_color":"FF494948"},
                {"model_id":"0x32","model_number":"0x06","model_name":"LinkBuds S",
                 "model_color_id":"0x05","sca_image_image_url":"https://hpc-image.data-gateway.seeds.services/76e4e03e-b1ce-4449-b9d5-1ebe86db9371.png",
                 "sca_anime_image_url":"","sca_source_color":"FFAAB8D8"}
            ]}}
        }""".trimIndent()

        val records = RootManager.parseSonyCloudModelInfo(json, "WH-1000XM4")
        val selected = RootManager.selectSonyCloudModelRecord(
            records = records,
            sourceColor = "FF494948",
            cachedUrls = setOf(imageUrl),
        )

        assertEquals(2, records.size)
        assertEquals(listOf("0x00", "0x01"), selected?.colorIds)
        assertEquals(imageUrl, selected?.imageUrl)
    }

    @Test
    fun readsSonyConnectedColorByAddressAndDecodesSharedPreferenceXml() {
        val connectedJson = """{"devices":[
            {"id":{"address":"11:22:33:44:55:66"},"modelName":"WH-1000XM4",
             "colorInfo":{"sourceColor":{"red":73,"green":73,"blue":72}}},
            {"id":{"address":"AA:BB:CC:DD:EE:FF"},"modelName":"WH-1000XM4",
             "colorInfo":{"sourceColor":{"red":82,"green":104,"blue":141}}}
        ]}""".trimIndent()
        val xml = """<map><string name="devices">${connectedJson.replace("\"", "&quot;")}</string></map>"""
        val decoded = RootManager.extractSharedPreferenceString(xml, "devices")

        assertEquals(
            "FF494948",
            RootManager.parseSonyConnectedDeviceColor(
                json = decoded.orEmpty(),
                expectedModel = "WH-1000XM4",
                deviceAddress = "11-22-33-44-55-66",
            ),
        )
    }

    @Test
    fun rejectsAmbiguousSonyVisualResources() {
        val first = SonyCloudModelRecord(
            "0x31", "0x05", "WH-1000XM4", "0x00",
            "https://hpc-image.data-gateway.seeds.services/8ea91f30-6237-4b4e-b261-f26a9d199cf7.png",
            null, "FF494948",
        )
        val second = first.copy(
            colorId = "0x05",
            imageUrl = "https://hpc-image.data-gateway.seeds.services/47712ab8-1dff-4cd7-a193-c7f978cb74c3.png",
        )

        assertNull(
            RootManager.selectSonyCloudModelRecord(
                records = listOf(first, second),
                sourceColor = null,
                cachedUrls = setOf(first.imageUrl, second.imageUrl),
            ),
        )
        assertNull(
            RootManager.selectSonyCloudModelRecord(
                records = listOf(first),
                sourceColor = "FFAAB8D8",
                cachedUrls = setOf(first.imageUrl),
            ),
        )
    }

    @Test
    fun usesOfficialSonySha1CacheFileName() {
        assertEquals(
            "8dddcc4924069ced14e7fb59bae94a82e4909476",
            RootManager.sonyCacheFileName(
                "https://hpc-image.data-gateway.seeds.services/8ea91f30-6237-4b4e-b261-f26a9d199cf7.png",
            ),
        )
    }
}
