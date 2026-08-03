package moe.chenxy.oppopods.utils

import moe.chenxy.oppopods.config.DeviceArtworkSelector
import moe.chenxy.oppopods.config.PodImageResource

/**
 * Public CDN coordinates extracted from Sony Sound Connect's official model catalog.
 *
 * Sound Connect falls back to ModelColor.DEFAULT when the device does not report a color;
 * the entries below follow the same rule (color 0x00 when present). Keeping the catalog in
 * the module avoids a runtime dependency on Sound Connect and avoids shipping its API key.
 */
internal object SonyOfficialArtworkCatalog {
    fun resolve(deviceName: String): SonyOfficialNetworkCandidate? {
        val entry = entriesByName[deviceName.trim().uppercase()] ?: return null
        return SonyOfficialNetworkCandidate(
            selector = DeviceArtworkSelector(
                vendorId = "sony",
                productId = listOf(entry.modelId, entry.modelNumber)
                    .filter(String::isNotBlank)
                    .joinToString(":"),
                colorId = entry.colorId,
                model = entry.modelName,
            ),
            resourceUrls = buildMap {
                put(PodImageResource.DETAIL, entry.imageUrl)
                entry.animationUrl.takeIf(String::isNotBlank)?.let {
                    put(PodImageResource.HERO_ANIMATION, it)
                }
            },
        )
    }

    private val entriesByName = listOf(
        SonyCatalogEntry("1000X THE COLLEXION", "0x31", "0x0D", "0x00", "https://hpc-image.data-gateway.seeds.services/e477bc88-6a30-419a-ae8a-210d38f416b1.png", "https://hpc-image.data-gateway.seeds.services/10b37a90-d45e-473a-9cb9-283ed1120174.png"),
        SonyCatalogEntry("BRAVIA Theatre U", "0x23", "0x01", "0x00", "https://hpc-image.data-gateway.seeds.services/0d20c30b-18e4-4086-a787-57e2c2f028b4.png"),
        SonyCatalogEntry("INZONE Buds", "0x34", "0x00", "0x00", "https://hpc-image.data-gateway.seeds.services/30e48f50-1854-43d3-81d9-b4fe9917063d.png"),
        SonyCatalogEntry("INZONE H9 II", "0x35", "0x00", "0x00", "https://hpc-image.data-gateway.seeds.services/2acb2089-dced-4eec-80db-e679dbaa9104.png", "https://hpc-image.data-gateway.seeds.services/62330af2-ebd2-47dd-a1ee-1c357298a3dc.png"),
        SonyCatalogEntry("LinkBuds", "0x32", "0x05", "0x00", "https://hpc-image.data-gateway.seeds.services/2e309fdb-c274-43d6-850b-35b269807504.png"),
        SonyCatalogEntry("LinkBuds Clip", "0x32", "0x0F", "0x00", "https://hpc-image.data-gateway.seeds.services/264be38a-7367-4cfd-812e-50115663fa5b.png", "https://hpc-image.data-gateway.seeds.services/cba5db7c-d391-447f-9d8d-63a3e3adfa7d.png"),
        SonyCatalogEntry("LinkBuds Fit", "0x32", "0x0B", "0x00", "https://hpc-image.data-gateway.seeds.services/2fb83182-4ac1-432f-8b59-74520ee2611e.png", "https://hpc-image.data-gateway.seeds.services/b4a6cb59-835c-46fe-8645-8bdbb8651433.png"),
        SonyCatalogEntry("LinkBuds Open", "0x32", "0x0A", "0x00", "https://hpc-image.data-gateway.seeds.services/eeb73a46-5f33-4999-8bb9-818db1ef17b7.png", "https://hpc-image.data-gateway.seeds.services/b19a723c-b574-4c76-a55b-00959cf89e3b.png"),
        SonyCatalogEntry("LinkBuds S", "0x32", "0x06", "0x00", "https://hpc-image.data-gateway.seeds.services/c3e8e3f3-e66f-4725-8f20-65fedcdb0e30.png"),
        SonyCatalogEntry("LinkBuds Speaker", "0x01", "0x1A", "0x00", "https://hpc-image.data-gateway.seeds.services/d67e2cb0-8ab6-4ed0-ba00-0bfef15dbd2d.png", "https://hpc-image.data-gateway.seeds.services/95f846e3-b23d-45db-867f-80a4f38c163f.png"),
        SonyCatalogEntry("LinkBuds UC", "0x32", "0x07", "0x00", "https://hpc-image.data-gateway.seeds.services/c457e1e6-eed4-4735-ac24-cd0f5dea6845.png"),
        SonyCatalogEntry("MDR-XB950B1", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/a51fccef-059b-4b42-8ad2-183dca40b9a3.png"),
        SonyCatalogEntry("MDR-XB950N1", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/3c01fa68-26cd-4acb-b0d4-ec4ff9a9a889.png"),
        SonyCatalogEntry("SRS-NS7", "0x01", "0x13", "0x00", "https://hpc-image.data-gateway.seeds.services/37f70d87-d871-44e0-a204-d50a9a78b51a.png"),
        SonyCatalogEntry("SRS-NS7R", "0x01", "0x14", "0x00", "https://hpc-image.data-gateway.seeds.services/a9d064da-4bd9-4e89-a7dd-41c0bbb59b43.png"),
        SonyCatalogEntry("ULT FIELD 1", "0x01", "0x19", "0x00", "https://hpc-image.data-gateway.seeds.services/1b181b22-8aa1-4d50-8c6f-bf0127942cc6.png"),
        SonyCatalogEntry("ULT FIELD 3", "0x01", "0x1B", "0x00", "https://hpc-image.data-gateway.seeds.services/5d9a841c-be34-4717-b1bb-57fc5321c69f.png", "https://hpc-image.data-gateway.seeds.services/ed62e565-e879-4de8-9066-2a98936f5d57.png"),
        SonyCatalogEntry("ULT FIELD 5", "0x01", "0x1C", "0x00", "https://hpc-image.data-gateway.seeds.services/dea7a0b5-8928-43fa-8dc1-9375fd79b159.png", "https://hpc-image.data-gateway.seeds.services/6ff5956c-3123-49a9-8345-6bdee45c2c4e.png"),
        SonyCatalogEntry("ULT FIELD 7", "0x13", "0x07", "0x00", "https://hpc-image.data-gateway.seeds.services/2004971f-766a-4ab5-8dd4-9509f13cec8e.png"),
        SonyCatalogEntry("ULT TOWER 10", "0x13", "0x08", "0x00", "https://hpc-image.data-gateway.seeds.services/7ad63f4a-d409-4f78-840a-75269104ccd8.png"),
        SonyCatalogEntry("ULT TOWER 9", "0x13", "0x09", "0x00", "https://hpc-image.data-gateway.seeds.services/25e610b9-67da-4ba4-9531-4bf09ec66d32.png", "https://hpc-image.data-gateway.seeds.services/5d296b8f-d57b-4861-a36b-d3d8ef2f4396.png"),
        SonyCatalogEntry("ULT TOWER 9AC", "0x13", "0x0A", "0x00", "https://hpc-image.data-gateway.seeds.services/031e7e51-0f55-4473-8725-9dbcada133de.png", "https://hpc-image.data-gateway.seeds.services/2f648220-ea08-41fb-9ec8-bcbe20bdea2c.png"),
        SonyCatalogEntry("ULT WEAR", "0x31", "0x0A", "0x00", "https://hpc-image.data-gateway.seeds.services/a2c9308f-44db-4f22-96b2-d85f1df8821a.png"),
        SonyCatalogEntry("WF-1000X", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/9782a224-00db-48e1-8913-b794cd1c8e70.png"),
        SonyCatalogEntry("WF-1000XM3", "0x32", "0x00", "0x00", "https://hpc-image.data-gateway.seeds.services/4357c544-a39a-4c45-8dd0-b75fa3db07d3.png"),
        SonyCatalogEntry("WF-1000XM4", "0x32", "0x03", "0x00", "https://hpc-image.data-gateway.seeds.services/91924b74-3e6a-43b4-9655-08020d513951.png"),
        SonyCatalogEntry("WF-1000XM5", "0x32", "0x09", "0x00", "https://hpc-image.data-gateway.seeds.services/b195e9ac-6901-442f-8dc9-e0d32c1de12d.png"),
        SonyCatalogEntry("WF-1000XM6", "0x32", "0x0E", "0x00", "https://hpc-image.data-gateway.seeds.services/428e2f01-b043-48aa-96bc-01a924dbe531.png", "https://hpc-image.data-gateway.seeds.services/d62477ff-ca08-4d1a-9e18-d9160f54d388.png"),
        SonyCatalogEntry("WF-C500", "0x32", "0x04", "0x00", "https://hpc-image.data-gateway.seeds.services/d47218b4-950a-44b5-91ec-a8ca675af615.png"),
        SonyCatalogEntry("WF-C510", "0x32", "0x0C", "0x00", "https://hpc-image.data-gateway.seeds.services/ec4790f5-ed06-4a7f-bb44-646a3139a237.png", "https://hpc-image.data-gateway.seeds.services/78fa677d-738e-4888-b786-fff492ba6156.png"),
        SonyCatalogEntry("WF-C700N", "0x32", "0x08", "0x00", "https://hpc-image.data-gateway.seeds.services/01701f9a-56b8-4c8c-aa63-671700d12c90.png"),
        SonyCatalogEntry("WF-C710N", "0x32", "0x0D", "0x00", "https://hpc-image.data-gateway.seeds.services/7e1a6abd-b1f0-4504-a871-998e980c5b00.png", "https://hpc-image.data-gateway.seeds.services/05e4e8bf-77c8-43da-a508-85d60914e648.png"),
        SonyCatalogEntry("WF-H800", "0x32", "0x01", "0x00", "https://hpc-image.data-gateway.seeds.services/26b0f7fc-7533-4a38-8149-2669cc7b2cb1.png"),
        SonyCatalogEntry("WF-SP700N", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/8671f9d6-8c44-4f28-95fe-a918530eed19.png"),
        SonyCatalogEntry("WF-SP800N", "0x32", "0x02", "0x00", "https://hpc-image.data-gateway.seeds.services/0596b4ea-b189-4bd9-af19-5c4e97ed02fc.png"),
        SonyCatalogEntry("WF-SP900", "0x41", "0x00", "0x00", "https://hpc-image.data-gateway.seeds.services/7bf17dd9-d2bc-4440-a7cc-9fed0910fb93.png"),
        SonyCatalogEntry("WH-1000XM2", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/23b6830a-cdaa-43f5-8ba4-d650b868f60a.png"),
        SonyCatalogEntry("WH-1000XM3", "0x31", "0x00", "0x00", "https://hpc-image.data-gateway.seeds.services/dc832d01-d2a4-4c66-83c7-6708ef1e1749.png"),
        SonyCatalogEntry("WH-1000XM4", "0x31", "0x05", "0x00", "https://hpc-image.data-gateway.seeds.services/8ea91f30-6237-4b4e-b261-f26a9d199cf7.png"),
        SonyCatalogEntry("WH-1000XM5", "0x31", "0x07", "0x00", "https://hpc-image.data-gateway.seeds.services/4e2e1840-5028-461c-bacf-26e8b2bfc2a7.png", "https://hpc-image.data-gateway.seeds.services/28a92570-d2b8-400c-be23-8d9b52c7e62e.png"),
        SonyCatalogEntry("WH-1000XM6", "0x31", "0x0B", "0x00", "https://hpc-image.data-gateway.seeds.services/d02be2aa-7fc9-4a98-88ea-ec04984bf361.png", "https://hpc-image.data-gateway.seeds.services/725d34b5-5fc8-483b-a477-8c017873928a.png"),
        SonyCatalogEntry("WH-CH520", "0x31", "0x09", "0x00", "https://hpc-image.data-gateway.seeds.services/2a5c0a58-65fe-4039-b281-232b7415dc43.png"),
        SonyCatalogEntry("WH-CH700N", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/c892e193-4aad-430c-b3ba-5c5e70de6cca.png"),
        SonyCatalogEntry("WH-CH720N", "0x31", "0x08", "0x00", "https://hpc-image.data-gateway.seeds.services/c26f383f-79e9-4b97-84a3-29bb4007598d.png"),
        SonyCatalogEntry("WH-H800", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/18670fd6-66a5-4ae5-a108-a67755517731.png"),
        SonyCatalogEntry("WH-H810", "0x31", "0x04", "0x00", "https://hpc-image.data-gateway.seeds.services/8effc3ed-82c1-4d70-bdf8-d91d93beb8c8.png"),
        SonyCatalogEntry("WH-H900N", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/8ccf177f-a10a-4794-b29d-be64ccec5240.png"),
        SonyCatalogEntry("WH-H910N", "0x31", "0x03", "0x00", "https://hpc-image.data-gateway.seeds.services/1a780a62-74bf-4754-b6a4-dfe7f2c2c19c.png"),
        SonyCatalogEntry("WH-XB700", "0x31", "0x01", "0x00", "https://hpc-image.data-gateway.seeds.services/bf6dede3-d31d-419b-92e7-1d2109f48f68.png"),
        SonyCatalogEntry("WH-XB900N", "0x31", "0x02", "0x00", "https://hpc-image.data-gateway.seeds.services/899244b6-aee4-4d87-b287-fae964dba1f1.png"),
        SonyCatalogEntry("WH-XB910N", "0x31", "0x06", "0x00", "https://hpc-image.data-gateway.seeds.services/078b29ea-61e8-4c9c-a11f-e4eadb792036.png"),
        SonyCatalogEntry("WI-1000X", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/837a98fd-ec11-4801-812b-2d3827f04ab3.png"),
        SonyCatalogEntry("WI-1000XM2", "0x33", "0x01", "0x00", "https://hpc-image.data-gateway.seeds.services/6486bef9-9ebb-4223-97f9-bea90a3c5fed.png"),
        SonyCatalogEntry("WI-C100", "0x33", "0x02", "0x00", "https://hpc-image.data-gateway.seeds.services/06e0432c-d063-426a-9fd4-4fa8897e4b6a.png"),
        SonyCatalogEntry("WI-C600N", "0x33", "0x00", "0x00", "https://hpc-image.data-gateway.seeds.services/5856a6ca-9d89-42f8-896a-34a652848858.png"),
        SonyCatalogEntry("WI-H700", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/3148c452-cbd0-4382-b07a-a640aa97f97f.png"),
        SonyCatalogEntry("WI-SP600N", "", "", "0x00", "https://hpc-image.data-gateway.seeds.services/cce4a9a0-7388-457b-9428-3c3ea23d65ac.png"),
    ).associateBy { it.modelName.uppercase() }

    private data class SonyCatalogEntry(
        val modelName: String,
        val modelId: String,
        val modelNumber: String,
        val colorId: String,
        val imageUrl: String,
        val animationUrl: String = "",
    )
}
