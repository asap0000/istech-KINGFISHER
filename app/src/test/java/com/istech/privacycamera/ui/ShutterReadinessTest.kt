package com.istech.privacycamera.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shutter takes a press only when a capture can actually happen (2026-11-25). */
class ShutterReadinessTest {
    @Test fun readyOnlyWithABoundCameraAndNothingInFlight() {
        assertTrue(canPressShutter(saving = false, memoPending = false, cameraBound = true))
    }

    @Test fun notBeforeTheCameraIsBound() {
        // Right after setup the camera may still be starting; a press then used to do nothing.
        assertFalse(canPressShutter(saving = false, memoPending = false, cameraBound = false))
    }

    @Test fun notWhileSavingOrWhileTheMemoScreenIsUp() {
        assertFalse(canPressShutter(saving = true, memoPending = false, cameraBound = true))
        assertFalse(canPressShutter(saving = false, memoPending = true, cameraBound = true))
    }
}
