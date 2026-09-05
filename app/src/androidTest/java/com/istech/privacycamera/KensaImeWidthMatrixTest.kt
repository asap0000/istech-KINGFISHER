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
 * K-2 (P1), ledger A-1: verifies PIN width normalization through the real vault UI.
 *
 * This increment does not switch or automate a real Japanese IME. It uses UiAutomator's
 * UiObject2.setText() to inject U+FF10..U+FF19 directly into the password fields, reproducing
 * the value an IME can supply. Real IME switching is intentionally left to a separate increment.
 */
@RunWith(AndroidJUnit4::class)
class KensaImeWidthMatrixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext
    private val packageName = targetContext.packageName

    @Before
    fun resetToFirstLaunch() {
        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("pm grant $packageName android.permission.CAMERA")
        launchApp(clearTask = true)

        val initialScreen = await(
            By.text(Pattern.compile("^(ロックされています|暗証番号を決めてください)$")),
            "vault lock or first-run PIN setup"
        )
        if (initialScreen.text == "ロックされています") {
            await(By.text("暗証番号を忘れた"), "forgot passphrase button").click()
            await(By.text("すべて消して作り直す"), "reset vault confirmation").click()
        }
        await(By.text("暗証番号を決めてください"), "first-run PIN setup")
    }

    @Test
    fun fullWidthSetup_halfWidthUnlock_succeeds() {
        assertHealthy("full-width setup test start")

        setUpPin(FULL_WIDTH_PIN)
        relaunchFromHomeAndAwaitLock()
        unlockWithPin(HALF_WIDTH_PIN)

        assertHealthy("full-width setup test end")
    }

    @Test
    fun halfWidthSetup_fullWidthUnlock_succeeds() {
        assertHealthy("half-width setup test start")

        setUpPin(HALF_WIDTH_PIN)
        relaunchFromHomeAndAwaitLock()
        unlockWithPin(FULL_WIDTH_PIN)

        assertHealthy("half-width setup test end")
    }

    private fun setUpPin(pin: String) {
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        check(fields.size >= 2) { "PIN setup fields were not exposed to UiAutomator" }
        fields[0].setText(pin)
        fields[1].setText(pin)
        await(By.text("この暗証番号にする"), "PIN setup button").click()
        device.waitForIdle()

        check(
            device.wait(
                Until.gone(By.text("暗証番号を決めてください")),
                LONG_WAIT
            )
        ) { "PIN setup did not finish" }

        // Biometric enrollment is optional; dismiss it so this matrix remains PIN-only.
        if (!device.wait(Until.hasObject(By.desc("保護フォルダを開く")), SHORT_WAIT)) {
            device.pressBack()
            device.waitForIdle()
        }
        await(By.desc("保護フォルダを開く"), "camera after PIN setup")
        assertHealthy("PIN setup")
    }

    private fun relaunchFromHomeAndAwaitLock() {
        device.pressHome()
        device.waitForIdle()
        launchApp(clearTask = false)
        await(By.text("ロックされています"), "lock screen after relaunch")
        assertHealthy("relaunch after PIN setup")
    }

    private fun unlockWithPin(pin: String) {
        await(By.clazz("android.widget.EditText"), "PIN unlock field").setText(pin)
        await(By.text("開く"), "PIN unlock button").click()
        device.waitForIdle()
        await(By.desc("保護フォルダを開く"), "camera after width-normalized unlock")
        assertHealthy("width-normalized PIN unlock")
    }

    private fun launchApp(clearTask: Boolean) {
        val launchIntent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("No launcher intent for $packageName", launchIntent)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            (if (clearTask) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)
        targetContext.startActivity(launchIntent!!.addFlags(flags))
        device.waitForIdle()
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
        const val HALF_WIDTH_PIN = "123456"
        const val FULL_WIDTH_PIN = "１２３４５６"
        const val SHORT_WAIT = 3_000L
        const val LONG_WAIT = 15_000L
    }
}
