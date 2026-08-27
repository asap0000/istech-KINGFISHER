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

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.istech.privacycamera.PrivacyCameraApplication
import com.istech.privacycamera.Tier
import com.istech.privacycamera.data.AccessActions
import com.istech.privacycamera.data.AccessEntry
import com.istech.privacycamera.data.AppSettings
import com.istech.privacycamera.data.ArchivedMonth
import com.istech.privacycamera.data.BackupManager
import com.istech.privacycamera.data.CategoryCatalog
import com.istech.privacycamera.data.MaskingEngine
import com.istech.privacycamera.data.PhotoCategories
import com.istech.privacycamera.data.PhotoItem
import com.istech.privacycamera.data.SecurePhotoStore
import com.istech.privacycamera.ui.BackupGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as PrivacyCameraApplication
    private val store: SecurePhotoStore = application.photoStore
    private val settings: AppSettings = application.appSettings

    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos.asStateFlow()

    /** Currently selected category filter (null = show all). */
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    /** True while a backup is being written; the UI blocks interaction until it clears. */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** The free-text search box; blank means "not searching". */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Soft-deleted photos awaiting restore or expiry, most-recently-deleted first. */
    private val _trash = MutableStateFlow<List<PhotoItem>>(emptyList())
    val trash: StateFlow<List<PhotoItem>> = _trash.asStateFlow()

    /** Categories the user has defined, as stored on disk. */
    private val _customCategories = MutableStateFlow<List<String>>(emptyList())

    /**
     * All selectable categories.
     *
     * Derived rather than stored, so it cannot drift from the photos: any category written
     * on a photo (including one in the trash) is always offered, even if it is missing from
     * the stored list. That covers both a restored backup — which carries per-photo
     * categories but not the catalogue file — and a category the user has taken out of the
     * picker while photos still use it.
     */
    val categories: StateFlow<List<String>> =
        combine(_customCategories, _photos, _trash) { custom, photos, trash ->
            CategoryCatalog.build(
                custom = custom,
                usedOnPhotos = photos.map { it.category } + trash.map { it.category }
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PhotoCategories.PREDEFINED + PhotoCategories.UNCLASSIFIED
        )

    /** How many photos have ever been imported via the Lite-migration path (cap basis). */
    private val _importedMigrationCount = MutableStateFlow(0)
    val importedMigrationCount: StateFlow<Int> = _importedMigrationCount.asStateFlow()


    init {
        // The plaintext -> encrypted migration touches every stored record and each record costs
        // a Keystore round trip, so it must not run on the main thread: a Pro library with
        // hundreds of photos would block the first frame long enough to ANR. Reads fall back
        // to the plaintext form while it is in flight, so starting it alongside the first
        // load is safe.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.migratePlaintextToEncrypted() }
            refresh()
            reloadCategories()
        }
        refresh()
        reloadCategories()
        refreshTrash() // also purges anything past its 30-day window
        compactAccessLog() // rolls aged-out log entries into monthly archives, if any
    }

    private fun reloadCategories() {
        viewModelScope.launch {
            _customCategories.value = withContext(Dispatchers.IO) { store.loadCustomCategories() }
        }
    }

    /** Sets the search text. Blank clears the search. */
    fun setQuery(text: String) {
        _query.value = text
    }

    /**
     * How many photos carry each category, trash included.
     *
     * The trash counts because a restore would otherwise resurrect a photo whose category
     * had been removed in the meantime, leaving it unfilterable.
     */
    fun categoryUsage(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        (_photos.value + _trash.value).forEach { item ->
            counts[item.category] = (counts[item.category] ?: 0) + 1
        }
        return counts
    }

    /**
     * Takes a category out of the picker. Refuses while photos still use it — those photos
     * would keep the name but no longer be filterable, so the user is asked to move them
     * first. Returns true when the category was actually removed.
     */
    fun removeCategory(name: String): Boolean {
        if (!CategoryCatalog.isRemovable(name, categoryUsage()[name] ?: 0)) return false
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.removeCustomCategory(name) }
            reloadCategories()
            if (_selectedCategory.value == name) _selectedCategory.value = null
        }
        return true
    }

    fun addCategory(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.addCustomCategory(name) }
            reloadCategories()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val (photos, importedCount) = withContext(Dispatchers.IO) {
                store.list() to store.loadImportedUuids().size
            }
            _photos.value = photos
            _importedMigrationCount.value = importedCount
        }
    }

    fun setCategoryFilter(category: String?) {
        _selectedCategory.value = category
    }

    /**
     * Per-tier storage cap (null = unlimited). Surfaced so the UI can show a counter
     * and warn before capture. See [com.istech.privacycamera.Tier.saveLimit].
     */
    val saveLimit: Int? = Tier.saveLimit

    /** True when the local store is full for this tier (always false when unlimited). */
    fun isAtSaveLimit(): Boolean =
        saveLimit?.let { _photos.value.size >= it } ?: false

    /**
     * Saves a captured JPEG on a background thread (applying [rotationDegrees] so the
     * stored image is upright), refreshes the gallery, then reports the new photo id so
     * the UI can prompt for a memo.
     *
     * When the tier's storage cap is already reached the capture is discarded and
     * [onLimitReached] is invoked instead — the user must delete to make room.
     */
    fun onCaptured(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        onSaved: (String) -> Unit = {},
        onLimitReached: () -> Unit = {}
    ) {
        if (isAtSaveLimit()) {
            onLimitReached()
            return
        }
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                val upright = SecurePhotoStore.rotateJpeg(jpegBytes, rotationDegrees)
                store.save(upright)
            }
            refresh()
            onSaved(item.id)
        }
    }

    fun updateMeta(id: String, caption: String, category: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.updateMeta(id, caption, category) }
            refresh()
        }
    }

    suspend fun revealOriginal(id: String): Bitmap? =
        withContext(Dispatchers.IO) { store.decryptOriginal(id) }

    /** Loads the saved (or default) mask spec for [id]. */
    suspend fun loadMaskSpec(id: String): MaskingEngine.MaskSpec =
        withContext(Dispatchers.IO) { store.loadMaskSpec(id) }

    /** Pro mask editing: regenerates the masked preview from the original using [spec]. */
    fun applyMask(id: String, spec: MaskingEngine.MaskSpec, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.applyMask(id, spec) }
            refresh()
            onDone()
        }
    }

    /** Overwrites the original with edited [jpegBytes], then refreshes the gallery. */
    fun replaceOriginal(id: String, jpegBytes: ByteArray, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.replaceOriginal(id, jpegBytes) }
            refresh()
            onDone()
        }
    }

    /** Soft-deletes: the photo moves to the trash and is recoverable for 30 days. */
    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refresh()
            refreshTrash()
        }
    }

    fun refreshTrash() {
        viewModelScope.launch {
            _trash.value = withContext(Dispatchers.IO) {
                store.purgeExpiredTrash()
                store.listTrash()
            }
        }
    }

    /**
     * Restores a trashed photo to the live library. Blocked (returns false) when the tier
     * is already at its storage cap, so a restore can't push Lite past the limit.
     */
    fun restore(id: String, onResult: (Boolean) -> Unit = {}) {
        if (isAtSaveLimit()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.restore(id) }
            refresh()
            refreshTrash()
            onResult(true)
        }
    }

    /** Permanently removes a single trashed photo (cannot be undone). */
    fun purge(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.purge(id) }
            refreshTrash()
        }
    }

    /** Permanently removes every trashed photo (cannot be undone). */
    fun emptyTrash() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.emptyTrash() }
            refreshTrash()
        }
    }

    suspend fun exportMasked(item: PhotoItem): Boolean =
        withContext(Dispatchers.IO) { store.exportMaskedToGallery(item) }

    /**
     * Writes a passphrase-encrypted backup of all current photos to [uri] (a destination
     * the user picked via the system file picker). The [passphrase] is wiped once used.
     * Reports success/failure on the main thread.
     */
    /**
     * Writes a PLAINTEXT migration ZIP of all current photos to [uri]. Lite's "limited"
     * export: the originals leave the device unencrypted (the user is warned). Pro can
     * later import up to its lifetime cap. Reports success/failure on the main thread.
     */
    fun exportMigrationZip(uri: Uri, onResult: (Int) -> Unit) {
        val items = _photos.value
        viewModelScope.launch {
            // >= 0 : number of images written; -1 : the file could not be opened/written.
            val written = withContext(Dispatchers.IO) {
                val n = try {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        BackupManager.exportPlainZip(out, items, store)
                    } ?: -1
                } catch (e: Exception) {
                    -1
                }
                store.logAccess(
                    "", AccessActions.MIGRATE_EXPORT,
                    if (n < 0) "書き出し失敗" else "平文ZIPに $n 枚を書き出し（原本が平文で端末外へ）"
                )
                n
            }
            onResult(written)
        }
    }

    fun exportBackup(uri: Uri, passphrase: CharArray, onResult: (Boolean) -> Unit) {
        val items = _photos.value
        _exporting.value = true
        BackupGate.isRunning = true
        // Runs on the application scope, not viewModelScope: leaving the screen must not kill
        // a half-written backup. NonCancellable additionally guarantees the logging and the
        // cleanup below happen even if the scope itself is being torn down — losing the log is
        // how the beta failures became untraceable.
        application.backgroundScope.launch {
            val ok = withContext(NonCancellable) {
                val resolver = getApplication<Application>().contentResolver
                var written = -1
                var verified = -1
                var failure: String? = null
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        written = BackupManager.export(out, items, store, passphrase)
                    } ?: run { failure = "書き出し先を開けませんでした" }
                    // Read the file straight back and confirm it decrypts, so a truncated or
                    // corrupt write is caught NOW rather than when the backup is finally
                    // needed (a silently-broken backup already bit us once).
                    if (written >= 0) {
                        resolver.openInputStream(uri)?.use { input ->
                            verified = BackupManager.verifyEncrypted(input, passphrase)
                        } ?: run { failure = "書き出したファイルを読み戻せませんでした" }
                    }
                } catch (e: Throwable) {
                    // Throwable, not Exception: running out of memory partway through is a
                    // plausible way for a large export to die, and it must not escape silently.
                    failure = "${e.javaClass.simpleName}: ${e.message ?: "詳細なし"}"
                } finally {
                    passphrase.fill(' ')
                }
                val success = written >= 0 && verified == written
                if (!success) {
                    // Never leave a file that looks like a backup but is not one. The beta
                    // failures left a 32-byte header behind, indistinguishable from a real
                    // backup in the file manager until the day it was needed.
                    val removed = deleteDocumentQuietly(uri)
                    store.logAccess(
                        "", AccessActions.BACKUP_EXPORT,
                        "書き出しに失敗（書込 $written / 検証 $verified" +
                            (failure?.let { " / $it" } ?: "") +
                            "）。壊れたファイルは" + (if (removed) "削除しました" else "削除できませんでした") + "。"
                    )
                } else {
                    store.logAccess(
                        "", AccessActions.BACKUP_EXPORT,
                        "暗号化バックアップ $written 枚を書き出し・復元検証OK"
                    )
                }
                success
            }
            BackupGate.isRunning = false
            _exporting.value = false
            onResult(ok)
        }
    }

    /** Removes a failed export so a broken file is never mistaken for a usable backup. */
    private fun deleteDocumentQuietly(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri)
    } catch (e: Exception) {
        false
    }

    /**
     * Pro-only: restores an encrypted backup from [uri] using [passphrase]. De-duplication
     * skips uuids that are already LIVE (true duplicates) and uuids this device has itself
     * deleted (tombstones) so a restore never resurrects a photo the user deleted here. On a
     * DIFFERENT device the tombstone set is empty, so the same backup DOES bring those photos
     * back — the new device has no record of the deletion. The passphrase is wiped after use.
     */
    fun importBackup(
        uri: Uri,
        passphrase: CharArray,
        onResult: (BackupManager.RestoreOutcome) -> Unit
    ) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val o = try {
                    val existing = _photos.value.map { it.uuid }.toSet() + store.loadDeletedUuids()
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        BackupManager.importEncrypted(input, store, passphrase, existing)
                    } ?: BackupManager.RestoreOutcome.WrongPassphraseOrCorrupt
                } catch (e: Exception) {
                    BackupManager.RestoreOutcome.WrongPassphraseOrCorrupt
                } finally {
                    passphrase.fill(' ')
                }
                val detail = when (o) {
                    is BackupManager.RestoreOutcome.Success ->
                        "復元 ${o.imported} 枚 / スキップ ${o.skipped} 枚"
                    BackupManager.RestoreOutcome.WrongPassphraseOrCorrupt -> "失敗（パスフレーズ違い/破損）"
                    BackupManager.RestoreOutcome.NotABackup -> "失敗（バックアップ形式でない）"
                }
                store.logAccess("", AccessActions.BACKUP_RESTORE, detail)
                o
            }
            refresh()
            onResult(outcome)
        }
    }

    /**
     * Pro-only: imports a Lite-migration ZIP from [uri], de-duplicating by uuid and
     * enforcing the lifetime migration cap (Tier.LITE_SAVE_LIMIT). Reports the per-import
     * outcome, or null on failure.
     */
    fun importMigration(uri: Uri, onResult: (BackupManager.MigrationImportResult?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        BackupManager.importMigrationZip(input, store, Tier.LITE_SAVE_LIMIT)
                    }
                } catch (e: Exception) {
                    null
                }
                store.logAccess(
                    "", AccessActions.MIGRATE_IMPORT,
                    if (r == null) "取り込み失敗"
                    else "取り込み ${r.imported} 枚（重複 ${r.skippedDuplicate} / 上限超過 ${r.skippedOverCap}）"
                )
                r
            }
            refresh()
            onResult(result)
        }
    }

    /**
     * Pro-only general image import: brings arbitrary picked images into the protected
     * store (each gets a fresh uuid and the usual masked preview). Not subject to the
     * migration cap. Reports how many were imported.
     */
    fun importImages(uris: List<Uri>, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                var n = 0
                for (uri in uris) {
                    try {
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                        store.importOriginal(
                            bytes,
                            java.util.UUID.randomUUID().toString(),
                            System.currentTimeMillis(),
                            "",
                            PhotoCategories.UNCLASSIFIED
                        )
                        n++
                    } catch (e: Exception) {
                        // Skip files that aren't decodable images.
                    }
                }
                store.logAccess(
                    "", AccessActions.IMAGE_IMPORT, "画像取り込み $n / ${uris.size} 枚"
                )
                n
            }
            refresh()
            onResult(count)
        }
    }

    // ---- Access log ----

    private val _accessLog = MutableStateFlow<List<AccessEntry>>(emptyList())
    val accessLog: StateFlow<List<AccessEntry>> = _accessLog.asStateFlow()

    /** Calendar months rolled up into compressed archives (newest first), for the history UI. */
    private val _archivedMonths = MutableStateFlow<List<ArchivedMonth>>(emptyList())
    val archivedMonths: StateFlow<List<ArchivedMonth>> = _archivedMonths.asStateFlow()

    fun logAccess(photoId: String, action: String, caption: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.logAccess(photoId, action, caption) }
        }
    }

    fun refreshAccessLog() {
        viewModelScope.launch {
            _accessLog.value = withContext(Dispatchers.IO) { store.loadAccessLog() }
            _archivedMonths.value = withContext(Dispatchers.IO) { store.listArchivedMonths() }
        }
    }

    /**
     * Rolls aged-out detail entries into monthly archives (see
     * [SecurePhotoStore.compactAccessLogIfNeeded]). Compaction reorganizes data — it never
     * discards it — so each affected month gets its own [AccessActions.LOG_COMPACT] record.
     * Safe to call on every app start; a no-op run compacts nothing.
     */
    fun compactAccessLog() {
        viewModelScope.launch {
            val months = withContext(Dispatchers.IO) {
                val compacted = store.compactAccessLogIfNeeded()
                compacted.forEach { month ->
                    store.logAccess("", AccessActions.LOG_COMPACT, "$month 分を圧縮アーカイブへ")
                }
                compacted
            }
            if (months.isNotEmpty()) refreshAccessLog()
        }
    }

    /** Decrypts/decompresses one archived month's full entry list on demand (UI expansion). */
    suspend fun loadArchivedMonthEntries(month: String): List<AccessEntry> =
        withContext(Dispatchers.IO) { store.loadArchivedMonthEntries(month) }

    /**
     * Permanently erases all log history (detail + archives). The erasure itself is recorded
     * as a fresh [AccessActions.LOG_DELETE] entry stating how many rows were removed and
     * when — so "the log was cleared" is the one fact that always survives a clear. Complete
     * silence (no trace at all) is intentionally not offered.
     */
    fun clearAccessLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val erased = store.clearAccessLog()
                store.logAccess("", AccessActions.LOG_DELETE, "履歴 $erased 件を削除")
            }
            refreshAccessLog()
        }
    }

    // ---- Hidden submission-print settings (Pro-only; docs/2026-07-04_仕様_提出用出力機能.md §4) ----

    private val _settingsRevealed = MutableStateFlow(settings.revealed)
    val settingsRevealed: StateFlow<Boolean> = _settingsRevealed.asStateFlow()

    private val _printEnabled = MutableStateFlow(settings.printEnabled)
    val printEnabled: StateFlow<Boolean> = _printEnabled.asStateFlow()

    /** Reveals the hidden settings entry (dev-options-style unlock gesture). */
    fun revealSettings() {
        if (settings.revealed) return
        settings.revealed = true
        _settingsRevealed.value = true
    }

    /** Re-hides the settings entry without an uninstall (user-initiated "hide again"). */
    fun hideSettingsAgain() {
        settings.revealed = false
        _settingsRevealed.value = false
    }

    /**
     * Toggles the submission-print opt-out. Turning it ON requires the caller to have
     * already passed device authentication (enforced by the settings screen); turning it
     * OFF needs no re-auth (moving to the safer state). Every change is audited.
     */
    fun setPrintEnabled(enabled: Boolean) {
        settings.printEnabled = enabled
        _printEnabled.value = enabled
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.logAccess(
                    "", AccessActions.SETTING_CHANGE,
                    "提出用の印刷: ${if (enabled) "ON" else "OFF"}"
                )
            }
        }
    }

    // ---- Submission-print output flow logging (P5: no output without a log record) ----

    /**
     * Records [AccessActions.OUTPUT_PRINT] BEFORE a print job is queued, suspending until the
     * write completes. Returns false only if the write itself failed, in which case the
     * caller must NOT proceed to print — an output that isn't logged must not happen.
     */
    suspend fun logOutputPrintBeforeJob(photoId: String, detail: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                store.logAccess(photoId, AccessActions.OUTPUT_PRINT, detail)
                true
            } catch (e: Exception) {
                false
            }
        }

    /** Records the terminal outcome of a print job, as a separate entry from OUTPUT_PRINT. */
    suspend fun logOutputResult(photoId: String, detail: String) {
        withContext(Dispatchers.IO) { store.logAccess(photoId, AccessActions.OUTPUT_RESULT, detail) }
    }
}
