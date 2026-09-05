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
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release-like, process-external smoke test for every screen reachable with an empty library.
 *
 * This deliberately uses only UiAutomator: the kensa APK is not debuggable and the bug
 * this guards against happens while AndroidViewModelFactory reflectively creates ViewModels.
 */
@RunWith(AndroidJUnit4::class)
class KensaColdStartSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext
    private val packageName = targetContext.packageName

    @Test
    fun coldStart_opensEveryReachableScreen_withoutCrashOrAnr() {
        // Avoid a system permission dialog whose wording differs by Android release/locale.
        // The permission belongs to this dedicated kensa application id only.
        device.executeShellCommand("pm grant $packageName android.permission.CAMERA")
        // force-stop kills this process and its instrumentation, so it is run from kensa.sh before the test starts.

        val launchIntent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("No launcher intent for $packageName", launchIntent)
        targetContext.startActivity(
            launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        device.waitForIdle()
        assertHealthy("cold start")

        unlockOrSetUpVault()
        awaitDescription("保護フォルダを開く", "camera")

        clickDescription("保護フォルダを開く", "gallery")
        awaitText("保護フォルダ", "gallery")

        clickDescription("アクセスログ", "access log")
        awaitText("アクセスログ", "access log")
        clickDescription("戻る", "return from access log")
        awaitText("保護フォルダ", "gallery after access log")

        openDrawer()
        clickTextMatching("ゴミ箱 \\(0\\)", "trash")
        awaitText("ゴミ箱", "trash")
        clickDescription("戻る", "return from trash")
        awaitText("保護フォルダ", "gallery after trash")

        // Settings is intentionally hidden until the version label is tapped seven times.
        openDrawer()
        repeat(7) {
            awaitInScrollableDrawer(By.textStartsWith("バージョン "), "version label").click()
        }
        val settings = awaitInScrollableDrawer(By.text("設定"), "revealed settings entry")
        settings.click()
        awaitText("提出用の印刷", "settings")

        clickDescription("戻る", "return from settings")
        awaitText("保護フォルダ", "gallery after settings")
        clickDescription("カメラに戻る", "return to camera")
        awaitDescription("保護フォルダを開く", "camera again")
    }

    private fun unlockOrSetUpVault() {
        val setup = device.wait(Until.findObject(By.text("暗証番号を決めてください")), SHORT_WAIT)
        if (setup != null) {
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            check(fields.size >= 2) { "PIN setup fields were not exposed to UiAutomator" }
            fields[0].text = TEST_PIN
            fields[1].text = TEST_PIN
            clickText("この暗証番号にする", "PIN setup")

            check(
                device.wait(
                    Until.gone(By.text("暗証番号を決めてください")),
                    LONG_WAIT
                )
            ) { "PIN setup did not finish" }

            // A configured biometric device may offer enrollment. Dismissing it leaves the
            // passphrase path enabled and keeps this smoke test device-independent.
            if (!device.wait(Until.hasObject(By.desc("保護フォルダを開く")), SHORT_WAIT)) {
                device.pressBack()
            }
        } else if (device.hasObject(By.text("ロックされています"))) {
            val field = await(By.clazz("android.widget.EditText"), "PIN input")
            field.text = TEST_PIN
            clickText("開く", "PIN unlock")
        }
        assertHealthy("vault setup/unlock")
    }

    private fun openDrawer() {
        clickDescription("カテゴリ", "category drawer")
        awaitText("カテゴリで分類", "category drawer")
    }

    private fun clickDescription(description: String, screen: String) {
        await(By.desc(description), description).click()
        device.waitForIdle()
        assertHealthy(screen)
    }

    private fun clickText(text: String, screen: String) {
        await(By.text(text), text).click()
        device.waitForIdle()
        assertHealthy(screen)
    }

    private fun clickTextMatching(regex: String, screen: String) {
        await(By.text(Pattern.compile(regex)), regex).click()
        device.waitForIdle()
        assertHealthy(screen)
    }

    private fun awaitText(text: String, screen: String) {
        await(By.text(text), text)
        assertHealthy(screen)
    }

    private fun awaitDescription(description: String, screen: String) {
        await(By.desc(description), description)
        assertHealthy(screen)
    }

    private fun awaitInScrollableDrawer(
        selector: androidx.test.uiautomator.BySelector,
        label: String
    ): androidx.test.uiautomator.UiObject2 {
        device.findObject(selector)?.let { return it }
        val drawer = await(By.scrollable(true), "scrollable category drawer")
        repeat(MAX_DRAWER_SCROLLS) {
            drawer.scroll(Direction.DOWN, DRAWER_SCROLL_PERCENT)
            device.waitForIdle()
            assertHealthy("category drawer while looking for $label")
            device.findObject(selector)?.let { return it }
        }
        error("Timed out waiting for $label after scrolling the category drawer")
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
        assertNull("Crash/ANR dialog while opening $screen: ${crashOrAnr?.text}", crashOrAnr)
    }

    private companion object {
        const val TEST_PIN = "123456"
        const val SHORT_WAIT = 3_000L
        const val LONG_WAIT = 15_000L
        const val MAX_DRAWER_SCROLLS = 8
        const val DRAWER_SCROLL_PERCENT = 0.8f
    }
}
