/*
 * Copyright 2026 istech
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.istech.privacycamera

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * K-2 (P1), ledger A-4: verifies re-locking across the PIN-only lifecycle matrix.
 *
 * Biometric and two-layer authentication combinations are covered by later increments.
 * UiAutomator is used because the release-like kensa APK is not debuggable.
 */
@RunWith(AndroidJUnit4::class)
class KensaRelockMatrixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext
    private val packageName = targetContext.packageName

    @Before
    fun unlockVaultAndOpenCamera() {
        device.executeShellCommand("pm grant $packageName android.permission.CAMERA")
        launchApp()
        assertHealthy("test setup launch")

        unlockOrSetUpVault()
        await(By.desc("保護フォルダを開く"), "camera after unlock")
        assertHealthy("test setup camera")
    }

    @Test
    fun backgroundThenResume_relocksVault() {
        assertHealthy("background transition start")

        device.pressHome()
        device.waitForIdle()
        Thread.sleep(BACKGROUND_WAIT)
        launchApp()
        awaitLocked("background resume")

        assertHealthy("background transition end")
    }

    @Test
    fun processKillThenResume_startsLocked() {
        assertHealthy("process-kill transition start")

        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("am kill $packageName")
        Thread.sleep(PROCESS_DEATH_WAIT)
        launchApp()
        awaitLocked("cold start after process kill")

        assertHealthy("process-kill transition end")
    }

    @Test
    fun rotationThenBackgroundAndResume_relocksVault() {
        assertHealthy("rotation transition start")

        try {
            device.setOrientationLeft()
            device.waitForIdle()
            Thread.sleep(ROTATION_WAIT)

            device.pressHome()
            device.waitForIdle()
            launchApp()
            awaitLocked("resume after rotation and background")
        } finally {
            device.setOrientationNatural()
            device.waitForIdle()
        }

        assertHealthy("rotation transition end")
    }

    private fun launchApp() {
        val launchIntent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("No launcher intent for $packageName", launchIntent)
        targetContext.startActivity(
            // Preserve the existing task on warm resumes: recreating it would turn the
            // background cases into accidental cold starts and hide an ON_PAUSE regression.
            launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        device.waitForIdle()
    }

    private fun unlockOrSetUpVault() {
        val setup = device.wait(Until.findObject(By.text("暗証番号を決めてください")), SHORT_WAIT)
        if (setup != null) {
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            check(fields.size >= 2) { "PIN setup fields were not exposed to UiAutomator" }
            fields[0].text = TEST_PIN
            fields[1].text = TEST_PIN
            await(By.text("この暗証番号にする"), "PIN setup button").click()
            device.waitForIdle()

            // Dismiss optional biometric enrollment to keep this increment PIN-only.
            if (!device.wait(Until.hasObject(By.desc("保護フォルダを開く")), SHORT_WAIT)) {
                device.pressBack()
                device.waitForIdle()
            }
        } else if (device.hasObject(By.text("ロックされています"))) {
            val field = await(By.clazz("android.widget.EditText"), "PIN input")
            field.text = TEST_PIN
            await(By.text("開く"), "PIN unlock button").click()
            device.waitForIdle()
        }
        assertHealthy("vault setup/unlock")
    }

    private fun awaitLocked(screen: String) {
        await(By.text("ロックされています"), "lock screen after $screen")
        assertHealthy(screen)
    }

    private fun await(selector: androidx.test.uiautomator.BySelector, label: String) =
        device.wait(Until.findObject(selector), LONG_WAIT)
            ?: error("Timed out waiting for $label")

    private fun assertHealthy(screen: String) {
        val crashOrAnr = device.findObject(
            By.pkg("android").text(
                Pattern.compile(
                    ".*(keeps stopping|has stopped|isn't responding|is not responding|" +
                        "停止しました|繰り返し停止|応答していません).*",
                    Pattern.CASE_INSENSITIVE
                )
            )
        )
        assertNull("Crash/ANR dialog during $screen: ${crashOrAnr?.text}", crashOrAnr)
    }

    private companion object {
        const val TEST_PIN = "123456"
        const val SHORT_WAIT = 3_000L
        const val LONG_WAIT = 15_000L
        const val BACKGROUND_WAIT = 2_500L
        const val PROCESS_DEATH_WAIT = 2_000L
        const val ROTATION_WAIT = 2_000L
    }
}
