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
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * K-2 (P1), ledger A-3: cold-start stability with legacy plaintext metadata and many photos.
 *
 * This increment covers the data-state side of the matrix (legacy and high-volume data).
 * Every cold start begins locked, but explicitly constructing separate key-present/key-absent
 * rows is out of scope because valid encrypted records cannot be prepared without the vault key.
 * UiAutomator is used because the release-like kensa APK is not debuggable.
 *
 * **Disabled: the approach cannot work anywhere** (kensa ledger, A-3 row).
 *
 * The [runAs] helper below reaches the app's private files through `run-as`, and that command
 * requires the *target package* to carry the DEBUGGABLE flag. The kensa build derives from
 * release and deliberately does not. A permissive device does not lift that: what matters is
 * the package, not the phone.
 *
 * Measured 2026-09-06, both ends of the range:
 *
 * ```
 * OPPO CPH2013   ro.debuggable=0  build.type=user
 *   run-as com.istech.privacycamera.kensa  -> package not debuggable
 * AVD (API 35)   ro.debuggable=1  build.type=userdebug
 *   run-as com.istech.privacycamera.kensa  -> package not debuggable
 *   run-as com.istech.privacycamera        -> cache code_cache files   (debug build, works)
 * ```
 *
 * The last line is the control: on that same emulator a debuggable package answers, so the
 * refusal tracks the package rather than the device. An earlier revision of this comment said
 * emulators permit the call and the limit only shows on real hardware — that was written from
 * inference rather than measurement, and it was wrong.
 *
 * Reviving A-3 therefore means dropping `run-as` and checking state through the UI, the way
 * the other three kensa tests do (thumbnail counts in the gallery, and so on). The body is
 * kept rather than deleted because the flows it drives — capture, memo dialog, bulk data —
 * are the ones a rewrite still needs.
 */
@Ignore(
    "run-as cannot reach a non-debuggable package on any device; A-3 needs rebuilding on UI checks"
)
@RunWith(AndroidJUnit4::class)
class KensaKeyDataMatrixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext
    private val packageName = targetContext.packageName
    private val secureFilesDir = "${targetContext.filesDir.absolutePath}/secure"

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
    fun legacyPlaintextMeta_migratesWithoutCrashOnColdStart() {
        launchApp(clearTask = true)
        unlockOrSetUpVault()
        val id = captureOnePhoto()

        runAs("rm $secureFilesDir/meta/$id.enc")
        val legacyUuid = UUID.randomUUID().toString()
        val legacyJson =
            "{\"uuid\":\"$legacyUuid\",\"caption\":\"legacy\",\"category\":\"未分類\"," +
                "\"createdAt\":1700000000000}"
        val encodedJson = Base64.encodeToString(
            legacyJson.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        runAs("sh -c 'echo $encodedJson | base64 -d > $secureFilesDir/meta/$id.json'")
        assertTrue(
            "Legacy metadata was not created",
            runAs("sh -c 'test -f $secureFilesDir/meta/$id.json; echo $?'").trim() == "0"
        )
        assertHealthy("legacy data preparation")

        coldStartAndUnlock()
        openGalleryAndAwait()
        assertHealthy("gallery with migrated legacy metadata")

        val migrated = waitForShellCondition(MIGRATION_WAIT) {
            val result = runAs(
                "sh -c 'test -f $secureFilesDir/meta/$id.enc && " +
                    "test ! -f $secureFilesDir/meta/$id.json; echo $?'")
            result.trim() == "0"
        }
        assertTrue("Legacy metadata did not migrate to $id.enc", migrated)
    }

    @Test
    fun manyPhotos_coldStartsWithoutCrashOrHang() {
        launchApp(clearTask = true)
        unlockOrSetUpVault()
        val sourceId = captureOnePhoto()

        repeat(COPY_COUNT) {
            val copyId = "IMG_KENSA_${UUID.randomUUID()}"
            runAs(
                "cp $secureFilesDir/originals/$sourceId.enc " +
                    "$secureFilesDir/originals/$copyId.enc"
            )
            runAs("cp $secureFilesDir/masked/$sourceId.jpg $secureFilesDir/masked/$copyId.jpg")
            runAs("cp $secureFilesDir/meta/$sourceId.enc $secureFilesDir/meta/$copyId.enc")
        }
        var maskedCount = 0
        val copiesReady = waitForShellCondition(MIGRATION_WAIT) {
            maskedCount = runAs("sh -c 'ls $secureFilesDir/masked/*.jpg | wc -l'")
                .trim()
                .toInt()
            maskedCount >= COPY_COUNT + 1
        }
        assertTrue(
            "Expected at least ${COPY_COUNT + 1} photos, found $maskedCount",
            copiesReady
        )
        assertHealthy("many-photo data preparation")

        coldStartAndUnlock()
        openGalleryAndAwait()
        assertHealthy("gallery with many photos")
    }

    private fun captureOnePhoto(): String {
        await(By.desc("保護フォルダを開く"), "camera before capture")
        assertHealthy("before capture")

        val density = targetContext.resources.displayMetrics.density
        val shutterX = device.displayWidth / 2
        val shutterY = device.displayHeight - ((48 + 38) * density).toInt()
        assertTrue(
            "Could not tap shutter at ($shutterX, $shutterY)",
            device.click(shutterX, shutterY)
        )

        await(By.text("メモを追加"), "post-capture memo dialog")
        assertHealthy("after capture")
        await(By.text("スキップ"), "memo skip button").click()
        device.waitForIdle()
        await(By.desc("保護フォルダを開く"), "camera after capture")
        assertHealthy("camera after capture")

        val diagnosticStart = SystemClock.elapsedRealtime()
        logSecureStorageDiagnostics("immediately after capture")
        val diagnosticFailure = AtomicReference<Throwable?>()
        val delayedDiagnostics = Thread {
            try {
                sleepUntil(diagnosticStart + DIAGNOSTIC_THREE_SECOND_WAIT)
                logSecureStorageDiagnostics("3 seconds after capture")
                sleepUntil(diagnosticStart + DIAGNOSTIC_TEN_SECOND_WAIT)
                logSecureStorageDiagnostics("10 seconds after capture")
            } catch (failure: Throwable) {
                diagnosticFailure.set(failure)
            }
        }.apply {
            name = "kensa-storage-diagnostics"
            start()
        }

        var names = emptyList<String>()
        val originalSaved = waitForShellCondition(MIGRATION_WAIT) {
            names = runAs("sh -c 'ls $secureFilesDir/originals/*.enc'")
                .lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(".enc") }
                .map { it.substringAfterLast('/').removeSuffix(".enc") }
                .toList()
            names.isNotEmpty()
        }
        delayedDiagnostics.join()
        diagnosticFailure.get()?.let { throw it }
        check(originalSaved) {
            "Timed out waiting for captured original in $secureFilesDir/originals: $names"
        }
        check(names.size == 1) { "Expected one captured original, found ${names.size}: $names" }
        return names.single()
    }

    private fun logSecureStorageDiagnostics(checkpoint: String) {
        val probe = device.executeShellCommand("run-as $packageName echo RUNAS_PROBE_OK")
        val secureFiles = runAs("find $secureFilesDir -type f -print")
        val originalsContents = runAs("ls $secureFilesDir/originals")
        val originalsDirectory = runAs("ls -ld $secureFilesDir/originals")
        Log.d(
            DIAGNOSTIC_LOG_TAG,
            "Storage diagnostics ($checkpoint)\n" +
                "run-as probe (raw, unfiltered): [[[$probe]]]\n" +
                "find $secureFilesDir -type f -print: [[[$secureFiles]]]\n" +
                "ls $secureFilesDir/originals: [[[$originalsContents]]]\n" +
                "ls -ld $secureFilesDir/originals: [[[$originalsDirectory]]]"
        )
    }

    private fun sleepUntil(deadline: Long) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0) Thread.sleep(remaining)
    }

    private fun coldStartAndUnlock() {
        // force-stop would also terminate the instrumentation hosted by the target package.
        // Home + am kill provides real process death while allowing this test to continue.
        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("am kill $packageName")
        Thread.sleep(PROCESS_DEATH_WAIT)
        launchApp(clearTask = true)
        await(By.text("ロックされています"), "lock screen after process death")
        assertHealthy("locked cold start")
        unlockOrSetUpVault()
    }

    private fun openGalleryAndAwait() {
        await(By.desc("保護フォルダを開く"), "camera after cold-start unlock").click()
        device.waitForIdle()
        await(By.text("保護フォルダ"), "gallery after cold start")
        assertHealthy("gallery after cold start")
    }

    private fun launchApp(clearTask: Boolean) {
        val launchIntent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("No launcher intent for $packageName", launchIntent)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            (if (clearTask) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)
        targetContext.startActivity(launchIntent!!.addFlags(flags))
        device.waitForIdle()
        assertHealthy("app launch")
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

            check(
                device.wait(
                    Until.gone(By.text("暗証番号を決めてください")),
                    LONG_WAIT
                )
            ) { "PIN setup did not finish" }

            if (!device.wait(Until.hasObject(By.desc("保護フォルダを開く")), SHORT_WAIT)) {
                device.pressBack()
                device.waitForIdle()
            }
        } else {
            await(By.text("ロックされています"), "vault lock screen")
            await(By.clazz("android.widget.EditText"), "PIN input").text = TEST_PIN
            await(By.text("開く"), "PIN unlock button").click()
            device.waitForIdle()
        }
        await(By.desc("保護フォルダを開く"), "camera after vault setup/unlock")
        assertHealthy("vault setup/unlock")
    }

    private fun runAs(command: String): String {
        // executeShellCommand() only returns stdout; merge stderr so a run-as-level failure
        // (e.g. "package not debuggable") surfaces here instead of silently reading as "".
        val output = device.executeShellCommand("run-as $packageName $command 2>&1")
        assertFalse("run-as failed for [$command]: $output", output.contains("run-as:"))
        return output
    }

    private fun waitForShellCondition(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(SHELL_POLL_WAIT)
            assertHealthy("waiting for metadata migration")
        }
        return condition()
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
        const val COPY_COUNT = 30
        const val SHORT_WAIT = 3_000L
        const val LONG_WAIT = 30_000L
        const val PROCESS_DEATH_WAIT = 2_000L
        const val MIGRATION_WAIT = 15_000L
        const val SHELL_POLL_WAIT = 500L
        const val DIAGNOSTIC_THREE_SECOND_WAIT = 3_000L
        const val DIAGNOSTIC_TEN_SECOND_WAIT = 10_000L
        const val DIAGNOSTIC_LOG_TAG = "KensaKeyDataMatrix"
    }
}
