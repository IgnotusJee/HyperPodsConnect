package moe.chenxy.headphones.core.profile

import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.CompatibilityLevel
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.DeviceTopology
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.EqualizerBandKind
import moe.chenxy.headphones.core.feature.EqualizerBandSpec
import moe.chenxy.headphones.core.feature.EqualizerCurveSpec
import moe.chenxy.headphones.core.feature.FeatureCapability
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.ProtocolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceProfileArchiveTest {
    @Test
    fun `schema v1 round trip preserves a profile and app extensions`() {
        val profile = profile()
        val archived = ArchivedDeviceProfile.from(
            profile,
            lastConnectedAtEpochMillis = 123,
            historicalAddresses = setOf("11:22:33:44:55:66", "22:33:44:55:66:77"),
            uiResources = mapOf("leftImage" to "images/left.img"),
            userOverrides = mapOf("noiseControl" to "enabled"),
        )
        val encoded = DeviceProfileArchiveCodec.encode(
            DeviceProfileArchive(exportedAtEpochMillis = 456, profiles = listOf(archived)),
        )

        val imported = DeviceProfileArchiveCodec.decode(encoded)
        val decoded = imported.archive.profiles.single()

        assertNull(imported.migratedFromSchemaVersion)
        assertEquals(
            profile.copy(
                identity = profile.identity.copy(
                    memberAddresses = setOf("22:33:44:55:66:77"),
                ),
            ),
            decoded.toDeviceProfile(),
        )
        assertEquals(setOf("11:22:33:44:55:66", "22:33:44:55:66:77"), decoded.historicalAddresses)
        assertEquals("images/left.img", decoded.uiResources["leftImage"])
        assertEquals("enabled", decoded.userOverrides["noiseControl"])
    }

    @Test
    fun `unversioned archive migrates to v1 and receives a fingerprint`() {
        val current = ArchivedDeviceProfile.from(profile(), 123)
        val legacyJson = DeviceProfileArchiveCodec.encode(
            DeviceProfileArchive(exportedAtEpochMillis = 456, profiles = listOf(current)),
        ).replace("  \"schemaVersion\": 1,\n", "")
            .replace(current.compatibilityFingerprint, "")

        val imported = DeviceProfileArchiveCodec.decode(legacyJson)

        assertEquals(0, imported.migratedFromSchemaVersion)
        assertNotEquals("", imported.archive.profiles.single().compatibilityFingerprint)
        assertEquals(profile(), imported.archive.profiles.single().toDeviceProfile())
    }

    @Test
    fun `schema v1 round trip preserves structured equalizer capability`() {
        val curveSpec = EqualizerCurveSpec(
            bands = listOf(
                EqualizerBandSpec(
                    id = "sony:eq:band:0",
                    displayName = "Band 1",
                    minGain = -10,
                    maxGain = 10,
                    centerFrequencyHz = 400,
                ),
                EqualizerBandSpec(
                    id = "sony:eq:clear-bass",
                    displayName = "Clear Bass",
                    minGain = -10,
                    maxGain = 10,
                    kind = EqualizerBandKind.CLEAR_BASS,
                ),
            ),
            writableSlotIds = setOf("sony:eq:custom-1", "sony:eq:custom-2"),
        )
        val source = profile().copy(
            features = profile().features + (
                FeatureId.EQUALIZER to FeatureCapability(
                    featureId = FeatureId.EQUALIZER,
                    canRead = true,
                    canWrite = true,
                    evidence = EvidenceLevel.VERIFIED,
                    availableOnTransports = setOf(TransportKind.CLASSIC_SPP),
                    equalizerCurveSpec = curveSpec,
                )
                ),
        )
        val encoded = DeviceProfileArchiveCodec.encode(
            DeviceProfileArchive(
                exportedAtEpochMillis = 456,
                profiles = listOf(ArchivedDeviceProfile.from(source, 123)),
            ),
        )

        val decoded = DeviceProfileArchiveCodec.decode(encoded)
            .archive.profiles.single().toDeviceProfile()

        assertEquals(curveSpec, decoded.features[FeatureId.EQUALIZER]?.equalizerCurveSpec)
        assertEquals(source.compatibilityLevel, decoded.compatibilityLevel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `future schema is rejected`() {
        DeviceProfileArchiveCodec.decode(
            """{"schemaVersion":99,"exportedAtEpochMillis":0,"profiles":[]}""",
        )
    }

    private fun profile() = DeviceProfile(
        identity = DeviceIdentity(
            DeviceId("device:test"),
            VendorId.SONY,
            "11:22:33:44:55:66",
        ),
        vendorId = VendorId.SONY,
        model = "LinkBuds S",
        firmware = "4.2.1",
        topology = DeviceTopology.EARBUDS_WITH_CASE,
        transport = TransportKind.BLE_GATT,
        protocol = ProtocolDescriptor("Sony Tandem/MDR", "2", "table1"),
        features = mapOf(
            FeatureId.BATTERY to FeatureCapability(
                FeatureId.BATTERY,
                canRead = true,
                canWrite = false,
                evidence = EvidenceLevel.VERIFIED,
                availableOnTransports = setOf(TransportKind.BLE_GATT),
                requiresReadback = false,
                source = "device-response",
            ),
        ),
        compatibilityLevel = CompatibilityLevel.STABLE,
    )
}
