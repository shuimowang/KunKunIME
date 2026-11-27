package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePreferencesTest {
    @Test
    fun firstUseDoesNotTurnAStaleFallbackFlagIntoConsent() {
        assertEquals(VoiceEngine.LOCAL_MODEL, restoredVoiceEngine(null, false, true))
        assertEquals(VoiceEngine.LOCAL_MODEL, restoredVoiceEngine("system_allowed", false, true))
    }

    @Test
    fun upgradePreservesBothExplicitSystemChoices() {
        assertEquals(VoiceEngine.SYSTEM_ON_DEVICE, restoredVoiceEngine(null, true, false))
        assertEquals(VoiceEngine.SYSTEM_ALLOWED, restoredVoiceEngine(null, true, true))
    }

    @Test
    fun explicitLocalChoiceOverridesLegacySystemConsent() {
        assertEquals(VoiceEngine.LOCAL_MODEL, restoredVoiceEngine("local_model", true, true))
        assertEquals(VoiceEngine.SYSTEM_ON_DEVICE, restoredVoiceEngine("system_on_device", true, true))
    }

    @Test
    fun unknownChoiceNeverFallsBackToANetworkService() {
        assertEquals(VoiceEngine.LOCAL_MODEL, restoredVoiceEngine("future_engine", true, true))
        assertEquals(VoiceEngine.LOCAL_MODEL, restoredVoiceEngine("", true, true))
    }
}
