package com.aeriotv.android.core.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistentLogPolicyTest {
    @Test
    fun `drops verbose debug and info View rendering noise`() {
        listOf(
            "07-23 16:51:32.254 V/View(1): render",
            "07-23 16:51:32.254 D/View    (1): render",
            "07-23 16:51:32.254 I/View    (1): render",
        ).forEach { line ->
            assertNull(PersistentLogPolicy.safeLine(line))
        }
    }

    @Test
    fun `drops verbose debug and info SurfaceView rendering noise`() {
        listOf(
            "07-23 16:51:32.254 V/SurfaceView(1): render",
            "07-23 16:51:32.254 D/SurfaceView(1): render",
            "07-23 16:51:32.254 I/SurfaceView (1): render",
        ).forEach { line ->
            assertNull(PersistentLogPolicy.safeLine(line))
        }
    }

    @Test
    fun `preserves warnings and errors from noisy tags`() {
        assertEquals(
            "07-23 16:51:32.254 W/View(1): warning",
            PersistentLogPolicy.safeLine("07-23 16:51:32.254 W/View(1): warning"),
        )
        assertEquals(
            "07-23 16:51:32.254 E/SurfaceView(1): failure",
            PersistentLogPolicy.safeLine("07-23 16:51:32.254 E/SurfaceView(1): failure"),
        )
    }

    @Test
    fun `does not suppress similarly named application tags`() {
        val line = "07-23 16:51:32.254 I/ViewModel(1): state changed"
        assertEquals(line, PersistentLogPolicy.safeLine(line))
    }

    @Test
    fun `preserves fatal entries and similarly named or case variant tags`() {
        val lines = listOf(
            "07-23 16:51:32.254 F/View(1): fatal",
            "07-23 16:51:32.254 I/ViewModel(1): state changed",
            "07-23 16:51:32.254 I/ViewRootImpl(1): state changed",
            "07-23 16:51:32.254 I/SurfaceViewRenderer(1): state changed",
            "07-23 16:51:32.254 I/MyView(1): state changed",
            "07-23 16:51:32.254 I/view(1): state changed",
        )
        lines.forEach { line -> assertEquals(line, PersistentLogPolicy.safeLine(line)) }
    }

    @Test
    fun `does not suppress application messages that merely mention a noisy pattern`() {
        val line = "07-23 16:51:32.254 I/Adaptarr(1): observed I/View(1) text"
        assertEquals(line, PersistentLogPolicy.safeLine(line))
    }

    @Test
    fun `crash snapshot filters every line through the same persistent policy`() {
        val dump = listOf(
            "07-23 16:51:32.254 I/View(1): render noise",
            "07-23 16:51:32.255 D/SurfaceView(1): render noise",
            "07-23 16:51:32.256 W/View(1): warning",
            "07-23 16:51:32.257 I/Adaptarr(1): connected",
            "07-23 16:51:32.258 E/SurfaceView(1): failure",
        ).joinToString("\n")

        assertEquals(
            listOf(
                "07-23 16:51:32.256 W/View(1): warning",
                "07-23 16:51:32.257 I/Adaptarr(1): connected",
                "07-23 16:51:32.258 E/SurfaceView(1): failure",
            ).joinToString("\n"),
            PersistentLogPolicy.safeSnapshot(dump),
        )
    }

    @Test
    fun `sanitizes every retained line`() {
        assertEquals(
            "07-23 16:51:32.254 I/Adaptarr(1): Authorization: ***",
            PersistentLogPolicy.safeLine(
                "07-23 16:51:32.254 I/Adaptarr(1): Authorization: Bearer super-secret-value",
            ),
        )
    }
}
