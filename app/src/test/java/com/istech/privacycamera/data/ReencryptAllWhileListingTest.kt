/* Copyright 2026 istech. Licensed under the Apache License, Version 2.0. */
package com.istech.privacycamera.data

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.istech.privacycamera.PrivacyCameraApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unlocking a vault that has not been migrated rewraps every record (VaultViewModel's
 * migrateThenOpen) while the photo list's first read (PhotoViewModel's startUp) runs. That read
 * must never find a record half rewritten: it counts it as unreadable and the gallery shows
 * none of the photos, with nothing to read again after the rewrap (2026-11-27). Here the two
 * run against each other for a while; every listing must hold the one stored photo.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReencryptAllWhileListingTest {
    private val app get() = ApplicationProvider.getApplicationContext<PrivacyCameraApplication>()

    @Test fun aListingDuringTheRewrapAlwaysHoldsThePhoto() {
        app.vaultSession.open(app.vault.initialize("123456".toCharArray()))
        app.vaultSession.ensureSealKeys()
        val jpeg = java.io.ByteArrayOutputStream().use {
            Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
                .compress(Bitmap.CompressFormat.JPEG, 90, it)
            it.toByteArray()
        }
        app.photoStore.save(jpeg, "書き換え中の一覧", "合成")
        val session = app.vaultSession
        val store = app.photoStore

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val rewrapped = java.util.concurrent.atomic.AtomicInteger()
        var writerError: Throwable? = null
        val writer = Thread {
            try {
                while (!stop.get()) {
                    rewrapped.addAndGet(store.reencryptAll(decryptAny = session::decrypt, encryptNew = session::encrypt))
                }
            } catch (t: Throwable) { writerError = t }
        }.apply { start() }
        var listings = 0
        var short = 0
        val until = System.nanoTime() + 3_000_000_000L
        try {
            while (System.nanoTime() < until) {
                val listing = store.listForDisplay()
                listings++
                if (listing.photos.size != 1 || listing.unreadableCount != 0) short++
            }
        } finally {
            stop.set(true)
            writer.join()
        }
        writerError?.let { throw it }
        assertEquals("listings missing the photo, out of $listings", 0, short)
        // The rewrap did its work (records were rewritten, not skipped as unreadable).
        org.junit.Assert.assertTrue("nothing was rewrapped", rewrapped.get() > 0)
        val after = store.listForDisplay()
        assertEquals(1, after.photos.size)
        assertEquals(0, after.unreadableCount)
    }
}
