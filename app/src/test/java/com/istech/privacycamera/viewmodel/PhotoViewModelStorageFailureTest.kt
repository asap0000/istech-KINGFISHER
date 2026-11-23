/*
 * Copyright 2026 istech
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.istech.privacycamera.viewmodel

import androidx.lifecycle.ViewModelProvider
import android.net.Uri
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.PrivacyCameraApplication
import com.istech.privacycamera.data.BackupManager
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoViewModelStorageFailureTest {
    private lateinit var app: PrivacyCameraApplication
    private val dispatcher = StandardTestDispatcher()
    private val uncaught = mutableListOf<Throwable>()
    private var activeModel: PhotoViewModel? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "secure").deleteRecursively()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> synchronized(uncaught) { uncaught += error } }
    }
    @After fun cleanup() {
        val job = activeModel?.viewModelScope?.coroutineContext?.get(Job)
        owner.viewModelStore.clear()
        // Cancellation does not interrupt a blocking filesystem write. Finish those test
        // jobs before another Robolectric case reuses/clears its synthetic store directory.
        for (attempt in 0 until 200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (job == null || job.isCompleted) break
            Thread.sleep(10)
        }
        assertThat(job == null || job.isCompleted).isTrue()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        Dispatchers.resetMain()
    }
    private fun model(): PhotoViewModel = ViewModelProvider(
        // getInstance caches the first Application across Robolectric sandboxes. Use the
        // actual Android factory with THIS test's Application, not a previous test's store.
        owner, ViewModelProvider.AndroidViewModelFactory(app)
    )[PhotoViewModel::class.java].also {
        activeModel = it
        assertThat(it.getApplication<PrivacyCameraApplication>()).isSameInstanceAs(app)
    }
    private fun waitForWarning(model: PhotoViewModel) {
        repeat(200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (model.storageWarning.value != null) return
            Thread.sleep(25)
        }
        error("Storage failure was not reported")
    }
    @Test fun `factory created model reports corrupt metadata without changing its bytes`() {
        val root = File(app.filesDir, "secure")
        File(root, "masked").mkdirs()
        File(root, "masked/A.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val record = File(root, "meta/A.enc").apply { parentFile?.mkdirs() }
        val bytes = byteArrayOf(9, 8, 7)
        record.writeBytes(bytes)
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
        val model = model()
        waitForWarning(model)
        assertThat(synchronized(uncaught) { uncaught.toList() }).isEmpty()
        assertThat(record.readBytes()).isEqualTo(bytes)
        assertThat(File(root, "meta/A.enc.unreadable").exists()).isFalse()
        assertThat(model.storageWarning.value).contains("読み出せない写真")
    }
    @Test fun `factory created model reports corrupt categories rather than crashing`() {
        val record = File(app.filesDir, "secure/categories.enc").apply { parentFile?.mkdirs() }
        val bytes = byteArrayOf(9, 8, 7)
        record.writeBytes(bytes)
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
        val model = model()
        waitForWarning(model)
        assertThat(synchronized(uncaught) { uncaught.toList() }).isEmpty()
        assertThat(record.readBytes()).isEqualTo(bytes)
    }
    private fun healthyPhoto(id: String) {
        val root = File(app.filesDir, "secure")
        File(root, "masked/$id.jpg").apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        File(root, "meta/$id.json").apply { parentFile?.mkdirs() }.writeText(
            """{"uuid":"uuid-$id","caption":"memo-$id","category":"category-$id","createdAt":1234}"""
        )
    }
    private fun waitForPhotos(model: PhotoViewModel, count: Int) {
        repeat(200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (model.photos.value.size == count) return
            Thread.sleep(25)
        }
        error("Readable photos did not appear: expected=$count, actual=${model.photos.value.map { it.id }}, warning=${model.storageWarning.value}")
    }
    @Test fun `healthy photos remain visible and category cleanup is blocked for a partial library`() {
        healthyPhoto("A"); healthyPhoto("B"); healthyPhoto("C")
        File(app.filesDir, "secure/meta/C.enc").writeBytes(byteArrayOf(9, 8, 7))
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
        val model = model()
        waitForPhotos(model, 2); waitForWarning(model)
        assertThat(model.photos.value.map { it.uuid }).containsExactly("uuid-A", "uuid-B")
        assertThat(model.storageWarning.value).contains("保管中 1 枚")
        assertThat(model.removeCategory("unused")).isFalse()
        assertThat(File(app.filesDir, "secure/meta/C.enc").readBytes()).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(synchronized(uncaught) { uncaught.toList() }).isEmpty()
    }
    @Test fun `broken migration ledger does not hide readable photos`() {
        healthyPhoto("A")
        File(app.filesDir, "secure/migration_imported.json").writeText("broken")
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
        val model = model()
        waitForPhotos(model, 1); waitForWarning(model)
        assertThat(model.photos.value.single().uuid).isEqualTo("uuid-A")
        assertThat(synchronized(uncaught) { uncaught.toList() }).isEmpty()
    }
    @Test fun `successful rescan clears the partial read warning`() {
        healthyPhoto("A")
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
        // Start from an encrypted record, as in a current install. Restoring the original
        // bytes atomically avoids deleting a file underneath the legacy-migration writer.
        app.photoStore.migratePlaintextToEncrypted()
        val bad = File(app.filesDir, "secure/meta/A.enc")
        val intact = bad.readBytes()
        bad.writeBytes(byteArrayOf(9, 8, 7))
        val model = model()
        waitForWarning(model)
        val restored = File(bad.parentFile, "A.fixture-restored")
        restored.writeBytes(intact)
        java.nio.file.Files.move(restored.toPath(), bad.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        model.refresh()
        waitForPhotos(model, 1)
        repeat(200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (model.storageWarning.value == null) return@repeat
            Thread.sleep(10)
        }
        assertThat(model.storageWarning.value).isNull()
        assertThat(model.photos.value.single().uuid).isEqualTo("uuid-A")
    }
    private fun unreadableLibrary(): PhotoViewModel {
        healthyPhoto("A"); healthyPhoto("C")
        File(app.filesDir, "secure/meta/C.enc").writeBytes(byteArrayOf(9, 8, 7))
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
        return model().also { waitForPhotos(it, 1); waitForWarning(it) }
    }
    private class DestinationProbe : ContentProvider() {
        var writes = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("synthetic.pcbak")) }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (mode.contains('w')) writes++
            throw java.io.FileNotFoundException("Synthetic destination probe")
        }
    }
    @Test fun `migration export refuses to write a seemingly complete zip from a partial display`() {
        val model = unreadableLibrary()
        val provider = DestinationProbe()
        ShadowContentResolver.registerProviderInternal("synthetic", provider)
        var outcome: Int? = null
        model.exportMigrationZip(Uri.parse("content://synthetic/migration.zip")) { outcome = it }
        repeat(200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (outcome != null) return@repeat
            Thread.sleep(10)
        }
        assertThat(outcome).isEqualTo(-1)
        assertThat(provider.writes).isEqualTo(0)
        assertThat(File(app.filesDir, "secure/meta/C.enc").readBytes()).isEqualTo(byteArrayOf(9, 8, 7))
    }
    @Test fun `encrypted backup refuses a partial display before opening its destination`() {
        val model = unreadableLibrary()
        val provider = DestinationProbe()
        ShadowContentResolver.registerProviderInternal("synthetic", provider)
        var outcome: Boolean? = null
        val passphrase = "synthetic-only".toCharArray()
        model.exportBackup(Uri.parse("content://synthetic/backup.pcbak"), passphrase) { outcome = it }
        repeat(200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (outcome != null) return@repeat
            Thread.sleep(10)
        }
        assertThat(outcome).isFalse()
        assertThat(provider.writes).isEqualTo(0)
        assertThat(passphrase.all { it == ' ' }).isTrue()
        assertThat(model.exporting.value).isFalse()
        assertThat(model.exportResult.value?.success).isFalse()
        model.acknowledgeExport()
    }
    @Test fun `backup restore reports a local record problem instead of a bad password`() {
        val root = File(app.filesDir, "secure")
        File(root, "masked").mkdirs()
        File(root, "masked/A.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val record = File(root, "meta/A.enc").apply { parentFile?.mkdirs() }
        val bytes = byteArrayOf(9, 8, 7)
        record.writeBytes(bytes)
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
        val model = model()
        var outcome: BackupManager.RestoreOutcome? = null
        val pass = "synthetic-only".toCharArray()
        model.importBackup(Uri.parse("content://synthetic/not-opened"), pass) { outcome = it }
        repeat(200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (outcome != null) return@repeat
            Thread.sleep(25)
        }
        assertThat(outcome).isEqualTo(BackupManager.RestoreOutcome.LocalStoreUnreadable)
        assertThat(pass.all { it == ' ' }).isTrue()
        assertThat(record.readBytes()).isEqualTo(bytes)
        assertThat(synchronized(uncaught) { uncaught.toList() }).isEmpty()
    }
}
