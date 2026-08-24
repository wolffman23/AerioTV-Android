package com.aeriotv.android.core.network.adaptarr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticSessionQualityControllerTest {

    @Test
    fun `automatic source never authorizes a manual owner`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellular)
        assertTrue(controller.commit(requireNotNull(controller.prepareManualSelect(cellular)), true))

        assertNull(controller.prepareAutomatic(cellular, AutomaticSessionQualityDecision.Source))
        assertEquals(AutomaticSessionQualityOwnership.Manual, controller.snapshot.ownership)
    }

    @Test
    fun `manual selection invalidates late automatic work`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellular)
        val automatic = requireNotNull(controller.prepareAutomatic(cellular, AutomaticSessionQualityDecision.OutputProfile(8)))
        val beforeManual = controller.snapshot.epoch
        val manual = requireNotNull(controller.prepareManualSelect(cellular))

        assertTrue(controller.snapshot.epoch > beforeManual)
        assertFalse(controller.commit(automatic, true))
        assertTrue(controller.commit(manual, true))
        assertEquals(AutomaticSessionQualityOwnership.Manual, controller.snapshot.ownership)
    }

    @Test
    fun `manual restore blocks automatic work even when its reprime fails`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellular)
        val automatic = requireNotNull(controller.prepareAutomatic(cellular, AutomaticSessionQualityDecision.OutputProfile(8)))
        assertTrue(controller.commit(automatic, true))
        val lateAutomatic = requireNotNull(controller.prepareAutomatic(cellular, AutomaticSessionQualityDecision.OutputProfile(8)))

        val restore = requireNotNull(controller.prepareManualRestore(cellular))
        assertTrue(controller.snapshot.automaticBlocked)
        assertFalse(controller.commit(lateAutomatic, true))
        assertNull(controller.prepareAutomatic(cellular, AutomaticSessionQualityDecision.OutputProfile(8)))
        assertFalse(controller.commit(restore, false))
        assertTrue(controller.snapshot.automaticBlocked)
        assertEquals(AutomaticSessionQualityOwnership.Automatic, controller.snapshot.ownership)
    }

    @Test
    fun `every fingerprint change rejects old automatic work`() {
        listOf(
            cellular.copy(channelId = "channel-2"),
            cellular.copy(baseSourceFingerprint = "source-b"),
            cellular.copy(mode = AutomaticSessionQualityMode.Off),
            cellular.copy(eligibility = cellular.eligibility.copy(isRemote = true)),
            cellular.copy(transport = AutomaticSessionTransport.Wifi),
        ).forEach { changed ->
            val controller = AutomaticSessionQualityController()
            controller.beginSession(cellular)
            val automatic = requireNotNull(controller.prepareAutomatic(cellular, AutomaticSessionQualityDecision.OutputProfile(8)))
            assertTrue(controller.isCurrentForReprime(automatic))
            controller.beginSession(changed)
            assertFalse(controller.isCurrentForReprime(automatic))
            assertFalse(controller.commit(automatic, true))
            assertEquals(AutomaticSessionQualityOwnership.None, controller.snapshot.ownership)
        }
    }

    @Test
    fun `automatic source restores only an automatic owner after successful reprime`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellular)
        assertTrue(controller.commit(requireNotNull(controller.prepareAutomatic(cellular, AutomaticSessionQualityDecision.OutputProfile(8))), true))

        val restore = requireNotNull(controller.prepareAutomatic(cellular, AutomaticSessionQualityDecision.Source))
        assertFalse(controller.commit(restore, false))
        assertEquals(AutomaticSessionQualityOwnership.Automatic, controller.snapshot.ownership)
        assertTrue(controller.commit(restore, true))
        assertEquals(AutomaticSessionQualityOwnership.None, controller.snapshot.ownership)
    }

    @Test
    fun `automatic owner alone carries across a transport-only transition`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellular)
        assertTrue(controller.commit(requireNotNull(controller.prepareAutomatic(cellular, AutomaticSessionQualityDecision.OutputProfile(8))), true))
        val wifi = cellular.copy(transport = AutomaticSessionTransport.Wifi)

        assertTrue(controller.beginAutomaticTransportTransition(wifi))
        assertNotNull(controller.prepareAutomatic(wifi, AutomaticSessionQualityDecision.Source))

        val manualController = AutomaticSessionQualityController()
        manualController.beginSession(cellular)
        assertTrue(manualController.commit(requireNotNull(manualController.prepareManualSelect(cellular)), true))
        assertFalse(manualController.beginAutomaticTransportTransition(wifi))
        assertEquals(AutomaticSessionQualityOwnership.None, manualController.snapshot.ownership)
    }

    @Test
    fun `new session clears old owner and manual restore latch`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellular)
        assertTrue(controller.commit(requireNotNull(controller.prepareManualRestore(cellular)), true))
        assertTrue(controller.snapshot.automaticBlocked)

        controller.beginSession(cellular.copy(channelId = "channel-2"))
        assertEquals(AutomaticSessionQualityOwnership.None, controller.snapshot.ownership)
        assertFalse(controller.snapshot.automaticBlocked)
    }

    private companion object {
        val cellular = AutomaticSessionQualityFingerprint(
            channelId = "channel-1",
            baseSourceFingerprint = "source-a",
            mode = AutomaticSessionQualityMode.Auto,
            eligibility = AutomaticSessionEligibility(true, false, false, false),
            transport = AutomaticSessionTransport.Cellular,
        )
    }
}
