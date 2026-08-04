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
        controller.beginSession(cellularSession)
        val manual = requireNotNull(controller.prepareManualSelect(cellularSession))
        assertTrue(controller.commit(manual, rePrimeSucceeded = true))

        assertNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.Source,
            ),
        )
        assertEquals(AutomaticSessionQualityOwnership.Manual, controller.snapshot.ownership)
    }

    @Test
    fun `manual restore blocks automatic work for the current fingerprint`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellularSession)
        val automatic = requireNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )
        assertTrue(controller.commit(automatic, rePrimeSucceeded = true))

        val lateAutomatic = requireNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )
        val epochBeforeManualRestore = controller.snapshot.epoch
        val manualRestore = requireNotNull(controller.prepareManualRestore(cellularSession))

        assertTrue(controller.snapshot.epoch > epochBeforeManualRestore)
        assertFalse(controller.commit(lateAutomatic, rePrimeSucceeded = true))
        assertTrue(controller.snapshot.automaticBlocked)
        assertNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )
        assertTrue(controller.commit(manualRestore, rePrimeSucceeded = true))
        assertEquals(AutomaticSessionQualityOwnership.Manual, controller.snapshot.ownership)
    }

    @Test
    fun `manual selection invalidates late automatic work`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellularSession)
        val lateAutomatic = requireNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )

        val epochBeforeManualSelection = controller.snapshot.epoch
        val manual = requireNotNull(controller.prepareManualSelect(cellularSession))

        assertTrue(controller.snapshot.epoch > epochBeforeManualSelection)
        assertFalse(controller.commit(lateAutomatic, rePrimeSucceeded = true))
        assertTrue(controller.commit(manual, rePrimeSucceeded = true))
        assertEquals(AutomaticSessionQualityOwnership.Manual, controller.snapshot.ownership)
    }

    @Test
    fun `fingerprint changes reject old automatic work`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellularSession)
        val oldAutomatic = requireNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )

        controller.beginSession(wifiSession)

        assertFalse(controller.commit(oldAutomatic, rePrimeSucceeded = true))
        assertEquals(AutomaticSessionQualityOwnership.None, controller.snapshot.ownership)
        assertEquals(wifiSession, controller.snapshot.fingerprint)
    }

    @Test
    fun `channel change rejects an authorization before it can re-prime`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellularSession)
        val oldAutomatic = requireNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )

        assertTrue(controller.isCurrentForReprime(oldAutomatic))
        controller.beginSession(cellularSession.copy(channelId = "channel-2"))

        assertFalse(controller.isCurrentForReprime(oldAutomatic))
    }

    @Test
    fun `mode remote timeshift and transport changes invalidate automatic work`() {
        val variants = listOf(
            cellularSession.copy(mode = AutomaticSessionQualityMode.Off),
            cellularSession.copy(eligibility = cellularSession.eligibility.copy(isRemote = true)),
            cellularSession.copy(eligibility = cellularSession.eligibility.copy(isTimeshift = true)),
            cellularSession.copy(transport = AutomaticSessionTransport.Wifi),
        )

        variants.forEach { changedSession ->
            val controller = AutomaticSessionQualityController()
            controller.beginSession(cellularSession)
            val oldAutomatic = requireNotNull(
                controller.prepareAutomatic(
                    fingerprint = cellularSession,
                    decision = AutomaticSessionQualityDecision.OutputProfile(8),
                ),
            )

            controller.beginSession(changedSession)

            assertFalse(controller.commit(oldAutomatic, rePrimeSucceeded = true))
        }
    }

    @Test
    fun `failed reprime does not move ownership state`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellularSession)
        val automatic = requireNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )

        assertFalse(controller.commit(automatic, rePrimeSucceeded = false))
        assertEquals(AutomaticSessionQualityOwnership.None, controller.snapshot.ownership)

        val manual = requireNotNull(controller.prepareManualSelect(cellularSession))
        assertFalse(controller.commit(manual, rePrimeSucceeded = false))
        assertEquals(AutomaticSessionQualityOwnership.None, controller.snapshot.ownership)
    }

    @Test
    fun `automatic source restores only an automatic owner after successful reprime`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellularSession)
        val automaticSelection = requireNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )
        assertTrue(controller.commit(automaticSelection, rePrimeSucceeded = true))

        val sourceRestore = requireNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.Source,
            ),
        )

        assertFalse(controller.commit(sourceRestore, rePrimeSucceeded = false))
        assertEquals(AutomaticSessionQualityOwnership.Automatic, controller.snapshot.ownership)
        assertTrue(controller.commit(sourceRestore, rePrimeSucceeded = true))
        assertEquals(AutomaticSessionQualityOwnership.None, controller.snapshot.ownership)
    }

    @Test
    fun `automatic ownership can restore source after a transport transition`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellularSession)
        val automaticSelection = requireNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession,
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )
        assertTrue(controller.commit(automaticSelection, rePrimeSucceeded = true))

        val wifi = cellularSession.copy(transport = AutomaticSessionTransport.Wifi)
        controller.beginAutomaticTransportTransition(wifi)

        val sourceRestore = controller.prepareAutomatic(wifi, AutomaticSessionQualityDecision.Source)
        assertNotNull(sourceRestore)
        assertTrue(controller.commit(requireNotNull(sourceRestore), rePrimeSucceeded = true))
        assertEquals(AutomaticSessionQualityOwnership.None, controller.snapshot.ownership)
    }

    @Test
    fun `channel or base source change starts a session with cleared owner and latch`() {
        val controller = AutomaticSessionQualityController()
        controller.beginSession(cellularSession)
        val manualRestore = requireNotNull(controller.prepareManualRestore(cellularSession))
        assertTrue(controller.commit(manualRestore, rePrimeSucceeded = true))
        assertTrue(controller.snapshot.automaticBlocked)

        controller.beginSession(cellularSession.copy(channelId = "other-channel"))
        assertEquals(AutomaticSessionQualityOwnership.None, controller.snapshot.ownership)
        assertFalse(controller.snapshot.automaticBlocked)

        val automatic = controller.prepareAutomatic(
            fingerprint = cellularSession.copy(baseSourceFingerprint = "other-source"),
            decision = AutomaticSessionQualityDecision.OutputProfile(8),
        )
        assertNull(automatic)
        controller.beginSession(cellularSession.copy(baseSourceFingerprint = "other-source"))
        assertNotNull(
            controller.prepareAutomatic(
                fingerprint = cellularSession.copy(baseSourceFingerprint = "other-source"),
                decision = AutomaticSessionQualityDecision.OutputProfile(8),
            ),
        )
    }

    private companion object {
        val cellularSession = AutomaticSessionQualityFingerprint(
            channelId = "channel-1",
            baseSourceFingerprint = "source-a",
            mode = AutomaticSessionQualityMode.Auto,
            eligibility = AutomaticSessionEligibility(
                isTrustedLocalLive = true,
                isCatchup = false,
                isTimeshift = false,
                isRemote = false,
            ),
            transport = AutomaticSessionTransport.Cellular,
        )
        val wifiSession = cellularSession.copy(transport = AutomaticSessionTransport.Wifi)
    }
}
