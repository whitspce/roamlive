package dev.whitespc.roam.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserConfigTest {

    @Test
    fun qualityPresetIdsAreStableAndUnique() {
        assertEquals(
            listOf("data_saver", "recommended", "sharp"),
            VideoQualityPreset.entries.map(VideoQualityPreset::storageId),
        )
        assertEquals(
            VideoQualityPreset.entries.size,
            VideoQualityPreset.entries.map(VideoQualityPreset::storageId).toSet().size,
        )
    }

    @Test
    fun exactQualityPresetsRemainStable() {
        VideoQualityPreset.entries.forEach { preset ->
            assertEquals(
                preset,
                VideoQualityPreset.closest(
                    preset.width,
                    preset.height,
                    preset.fps,
                    preset.bitrateKbps,
                ),
            )
        }
    }

    @Test
    fun legacyFrameRateAndBitrateFollowTheirExactResolution() {
        assertEquals(
            VideoQualityPreset.DATA_SAVER,
            VideoQualityPreset.closest(854, 480, 60, 8_000),
        )
        assertEquals(
            VideoQualityPreset.RECOMMENDED,
            VideoQualityPreset.closest(1280, 720, 60, 1_200),
        )
        assertEquals(
            VideoQualityPreset.SHARP,
            VideoQualityPreset.closest(1920, 1080, 60, 2_500),
        )
    }

    @Test
    fun arbitraryAndInvalidQualityValuesHaveDeterministicFallbacks() {
        assertEquals(
            VideoQualityPreset.DATA_SAVER,
            VideoQualityPreset.closest(640, 360, 24, 900),
        )
        assertEquals(
            VideoQualityPreset.SHARP,
            VideoQualityPreset.closest(2560, 1440, 60, 10_000),
        )
        assertEquals(
            VideoQualityPreset.RECOMMENDED,
            VideoQualityPreset.closest(-1, 720, 30, 2_500),
        )
    }

    @Test
    fun allocationAndUiSettingsAreBounded() {
        assertFalse(UserConfigRules.isValidObsPort(0))
        assertTrue(UserConfigRules.isValidObsPort(65_535))
        assertEquals(4455, UserConfigRules.obsPort(90_000))
        assertEquals(0f, UserConfigRules.micGain(-2f))
        assertEquals(2f, UserConfigRules.micGain(8f))
        assertEquals(1f, UserConfigRules.micGain(Float.NaN))
        assertEquals(13, UserConfigRules.chatTextSizeSp(400))
        assertEquals("compact", UserConfigRules.chatPanelMode("giant"))
        assertEquals("left", UserConfigRules.chatPanelSide("middle"))
        assertEquals(30, UserConfigRules.stealthPulseSeconds(1))
        assertEquals(5, UserConfigRules.maxReconnectMinutes(999))
    }
}
