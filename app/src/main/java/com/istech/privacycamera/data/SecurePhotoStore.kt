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
package com.istech.privacycamera.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Environment
import android.provider.MediaStore
import com.istech.privacycamera.crypto.CryptoManager
import com.istech.privacycamera.crypto.SealKeyUnavailableException
import com.istech.privacycamera.crypto.VaultClosedException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * A single stored photo: the masked preview plus its user-added metadata.
 *
 * [id] is the on-device storage key (also the file name) and may differ between
 * devices/installs. [uuid] is a stable, install-independent identity minted once at
 * capture time; it travels inside encrypted exports so that imports can de-duplicate
 * and (later) accumulate batches across multiple backups without resurrecting or
 * double-importing the same photo. Always key cross-app/cross-backup logic on [uuid],
 * never on [id].
 */
data class PhotoItem(
    val id: String,
    val uuid: String,
    val maskedFile: File,
    val createdAt: Long,
    val caption: String = "",
    val category: String = PhotoCategories.UNCLASSIFIED,
    /** When this photo was moved to the trash, or 0 if it is a live (non-trashed) photo. */
    val deletedAt: Long = 0L
)

/**
 * Owns all on-device persistence.
 *
 * Layout (all under the app-private internal storage, which media scanners and cloud
 * photo apps like Google Photos / Amazon Photos cannot see or upload):
 *   filesDir/secure/originals/<id>.enc   -> AES-GCM encrypted full-resolution JPEG
 *   filesDir/secure/masked/<id>.jpg      -> masked (mosaic) preview, safe to display
 *   filesDir/secure/meta/<id>.json       -> { caption, category, createdAt }
 */
class SecurePhotoStore(
    private val context: Context,
    /**
     * How bytes are protected on disk. Defaults to the AndroidKeyStore-backed implementation,
     * which is the only thing production ever uses — the seam exists because AndroidKeyStore
     * is unavailable off-device, and the metadata migration is the part of this class most
     * capable of losing a user's memos if it is wrong. Testing it beats leaving it unproven.
     */
    private val encryptBytes: (ByteArray) -> ByteArray = CryptoManager::encrypt,
    private val decryptBytes: (ByteArray) -> ByteArray = CryptoManager::decrypt,
    /**
     * Writes without the master key: seals bytes so only the opened vault can read them back.
     * Null where sealing does not exist (older call sites, tests that never close the vault);
     * production passes `VaultSession::seal`. Reading needs no counterpart: [decryptBytes]
     * recognises a seal on its own.
     */
    private val sealBytes: ((ByteArray) -> ByteArray)? = null
) {

    private val baseDir = File(context.filesDir, "secure").apply { mkdirs() }
    // All instances for this app-private store share the same read-modify-write lock.
    private val stateLock = stateLocks.computeIfAbsent(baseDir.canonicalPath) { Any() }
    private val originalsDir = File(baseDir, "originals").apply { mkdirs() }
    private val maskedDir = File(baseDir, "masked").apply { mkdirs() }
    private val metaDir = File(baseDir, "meta").apply { mkdirs() }
    // Soft-deleted photos live here for TRASH_TTL_MILLIS so an accidental delete can be
    // undone. Recovery is intentionally the trash's job, separate from backups.
    private val trashDir = File(baseDir, "trash").apply { mkdirs() }
    private val trashOriginalsDir = File(trashDir, "originals").apply { mkdirs() }
    private val trashMaskedDir = File(trashDir, "masked").apply { mkdirs() }
    private val trashMetaDir = File(trashDir, "meta").apply { mkdirs() }
    // User-defined category names are the same kind of text as a memo — "祖母", "離婚調停" —
    // so they get the same protection. The plaintext file is read once, then migrated away.
    private val categoriesFile = File(baseDir, "categories.enc")
    private val legacyCategoriesFile = File(baseDir, "categories.json")
    // Detail access log: encrypted, append-only JSON array of recent AccessEntry rows.
    private val accessLogFile = File(baseDir, "access_log.enc")
    // Pre-encryption format (plaintext JSON). Only read once, to migrate into accessLogFile.
    private val legacyAccessLogFile = File(baseDir, "access_log.json")
    // Entries older than the retention window are rolled up here, one gzip+encrypted JSON
    // array per calendar month, and removed from accessLogFile. See compactAccessLogIfNeeded.
    private val logArchiveDir = File(baseDir, "log_archive").apply { mkdirs() }
    private val importedUuidsFile = File(baseDir, "migration_imported.json")
    // Access-log lines for photos taken while the vault was closed. The log itself is encrypted
    // under the master key, which is not in hand then, so each line waits here as its own sealed
    // file until the next unlock folds it in (see foldPendingLog).
    private val pendingLogDir = File(baseDir, "pending_log")
    // Persistent tombstones: uuids the user deleted ON THIS install. A backup restore skips
    // these so it never resurrects something deleted here — even after the 30-day trash
    // window has purged the photo. A different device starts with an empty set, so a backup
    // restored there DOES bring the photo back (that device never knew about the deletion).
    private val deletedUuidsFile = File(baseDir, "deleted_uuids.json")

    init {
        // Defence in depth: even though internal storage is never media-scanned,
        // drop a .nomedia marker so nothing here is ever indexed.
        File(baseDir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
    }

    /**
     * Persists a freshly captured JPEG: mints a new uuid, encrypts the original, writes a
     * masked preview, and stores initial metadata. [jpegBytes] should already be upright.
     */
    fun save(
        jpegBytes: ByteArray,
        caption: String = "",
        category: String = PhotoCategories.UNCLASSIFIED
    ): PhotoItem = persist(
        jpegBytes,
        uuid = java.util.UUID.randomUUID().toString(),
        createdAt = System.currentTimeMillis(),
        caption = caption,
        category = category
    )

    /**
     * Imports an image (from a Lite migration archive or a general image pick) under a
     * given [uuid] and metadata. Same on-device protection as a capture: the bytes become
     * the encrypted original and a masked preview is generated. Throws if the bytes are
     * not a decodable image so the caller can skip it.
     */
    fun importOriginal(
        jpegBytes: ByteArray,
        uuid: String,
        createdAt: Long,
        caption: String,
        category: String
    ): PhotoItem = persist(jpegBytes, uuid, createdAt, caption, category)

    private fun persist(
        jpegBytes: ByteArray,
        uuid: String,
        createdAt: Long,
        caption: String,
        category: String
    ): PhotoItem {
        // Decode first so a non-image input fails before anything is written to disk.
        val source = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IllegalArgumentException("Not a decodable image")
        val id = newId()

        File(originalsDir, "$id.enc").writeBytes(encryptBytes(jpegBytes))

        val masked = MaskingEngine.mask(source)
        val maskedFile = File(maskedDir, "$id.jpg")
        maskedFile.outputStream().use { out ->
            masked.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        source.recycle()
        masked.recycle()

        writeMeta(id, uuid, caption, category, createdAt)

        return PhotoItem(id, uuid, maskedFile, createdAt, caption, category)
    }

    /**
     * Persists a freshly captured JPEG while the vault may be closed.
     *
     * Same result as [save], but the original and the memo are sealed with [sealBytes] rather
     * than encrypted under the master key, and NOTHING existing is read: with the vault closed
     * there is nothing readable to consult, and consulting it is how records get lost.
     *
     * Order is original, memo, and the masked preview LAST. [list] finds photos by their
     * preview, so a crash part-way leaves files no listing will show; and if anything throws,
     * every file written for this id is removed before the exception goes on. A photo is
     * either all there or not there at all.
     *
     * @param jpegBytes already upright.
     */
    fun saveSealed(
        jpegBytes: ByteArray,
        caption: String = "",
        category: String = PhotoCategories.UNCLASSIFIED
    ): PhotoItem {
        val seal = sealBytes ?: throw IllegalStateException("sealing is not available")
        val source = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IllegalArgumentException("Not a decodable image")
        val uuid = java.util.UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val id = newId()
        val maskedFile = File(maskedDir, "$id.jpg")
        try {
            writeAtomically(File(originalsDir, "$id.enc")) { seal(jpegBytes) }
            writeAtomically(File(metaDir, "$id.enc")) {
                seal(metaJson(uuid, caption, category, createdAt).toString().toByteArray(Charsets.UTF_8))
            }
            val masked = MaskingEngine.mask(source)
            try {
                writeAtomically(maskedFile) {
                    ByteArrayOutputStream().also { masked.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        .toByteArray()
                }
            } finally {
                masked.recycle()
            }
        } catch (e: Throwable) {
            removeEverythingFor(id)
            throw e
        } finally {
            source.recycle()
        }
        return PhotoItem(id, uuid, maskedFile, createdAt, caption, category)
    }

    /**
     * Replaces the memo of a photo that was just taken, whole, sealed, without reading it.
     *
     * A photo captured while closed has nothing in its record but what [saveSealed] put there,
     * so there is nothing to merge with: overwriting is exact, and reading first would need the
     * key this path exists to do without.
     */
    fun rewriteSealedMeta(id: String, uuid: String, createdAt: Long, caption: String, category: String) {
        val seal = sealBytes ?: throw IllegalStateException("sealing is not available")
        val bytes = seal(metaJson(uuid, caption, category, createdAt).toString().toByteArray(Charsets.UTF_8))
        writeAtomically(File(metaDir, "$id.enc")) { bytes }
        File(metaDir, "$id.json").delete()
    }

    /**
     * How many live photos there are. Counts previews rather than decrypting anything, so it
     * is right whether the vault is open or closed, which is what a storage cap needs.
     */
    fun photoCount(): Int = maskedDir.listFiles { f -> f.extension == "jpg" }?.size ?: 0

    /**
     * Queues the access-log line for a photo taken while closed, as the sealed file
     * `<timestamp>_<photoId>.seal`. [foldPendingLog] moves it into the real log after the next
     * unlock, with the time it happened rather than the time it was noticed.
     */
    fun appendSealedCaptureLog(photoId: String, timestamp: Long) {
        val seal = sealBytes ?: throw IllegalStateException("sealing is not available")
        val line = JSONObject()
            .put("t", timestamp)
            .put("a", AccessActions.CAPTURE_LOCKED)
            .put("id", photoId)
        pendingLogDir.mkdirs()
        val bytes = seal(line.toString().toByteArray(Charsets.UTF_8))
        writeAtomically(File(pendingLogDir, "${timestamp}_$photoId.seal")) { bytes }
    }

    /**
     * Folds queued capture lines into the access log, oldest first, each at its own time, and
     * removes each queued file only after its line is in. Call only while the vault is open.
     *
     * If the key turns out not to be there after all (closed again, or the sealing key did not
     * load) it stops and leaves everything queued. A file that cannot be read for any other
     * reason is left where it is too: dropping a line silently would hide that a photo was taken.
     */
    fun foldPendingLog() {
        val files = pendingLogDir.listFiles { f -> f.isFile && f.extension == "seal" } ?: return
        val lines = mutableListOf<Pair<File, JSONObject>>()
        for (file in files) {
            try {
                lines += file to JSONObject(String(decryptBytes(file.readBytes()), Charsets.UTF_8))
            } catch (e: VaultClosedException) {
                return
            } catch (e: SealKeyUnavailableException) {
                return
            } catch (e: Exception) {
                // Unreadable for another reason: keep the file, fold the rest.
            }
        }
        for ((file, line) in lines.sortedBy { it.second.optLong("t") }) {
            logAccessAt(line.optLong("t"), line.optString("id"), line.optString("a"), caption = "")
            file.delete()
        }
    }

    private fun metaJson(uuid: String, caption: String, category: String, createdAt: Long) =
        JSONObject()
            .put("uuid", uuid)
            .put("caption", caption)
            .put("category", category)
            .put("createdAt", createdAt)

    /** Stages [bytes] in a uniquely named neighbour and renames it into place. */
    private fun writeAtomically(target: File, bytes: () -> ByteArray) {
        val tmp = File.createTempFile("${target.name}.tmp.", null, target.parentFile)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes())
                out.fd.sync()
            }
            // Same-directory rename is atomic on the supported filesystem. If it cannot
            // replace the target, fail WITHOUT a copy-over fallback that could truncate it.
            Files.move(tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    /** Removes every file [saveSealed] may have written for [id], finished or staged. */
    private fun removeEverythingFor(id: String) {
        listOf(originalsDir, metaDir, maskedDir).forEach { dir ->
            dir.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
        }
    }

    /** Returns a storage id (file-name key) not already in use. */
    private fun newId(): String {
        var id = "IMG_${System.currentTimeMillis()}"
        var n = 1
        while (File(originalsDir, "$id.enc").exists() || File(maskedDir, "$id.jpg").exists()) {
            id = "IMG_${System.currentTimeMillis()}_$n"
            n++
        }
        return id
    }

    /** A display snapshot is explicitly incomplete when records cannot be read. */
    data class PhotoListing(val photos: List<PhotoItem>, val unreadableCount: Int)

    /** Strict inventory for backup and identity checks: never silently omit a record. */
    fun list(): List<PhotoItem> = scanPhotos(trash = false, allowPartial = false).photos

    /** Readable photos remain usable without rebuilding or changing damaged metadata. */
    fun listForDisplay(): PhotoListing = scanPhotos(trash = false, allowPartial = true)

    private fun scanPhotos(trash: Boolean, allowPartial: Boolean): PhotoListing = synchronized(stateLock) {
        val dir = if (trash) trashMaskedDir else maskedDir
        val files = dir.listFiles { f -> f.extension == "jpg" }
            ?: throw IOException("Cannot list stored photos")
        var unreadable = 0
        val photos = files.mapNotNull { f ->
            try {
                val id = f.nameWithoutExtension
                val meta = if (trash) readTrashMeta(id) else readMeta(id)
                val createdAt = meta?.optLong("createdAt", f.lastModified()) ?: f.lastModified()
                val caption = meta?.optString("caption", "") ?: ""
                val category = meta?.optString("category", PhotoCategories.UNCLASSIFIED)
                    ?: PhotoCategories.UNCLASSIFIED
                PhotoItem(
                    id = id,
                    uuid = if (trash) meta?.optString("uuid", "") ?: ""
                        else ensureUuid(id, meta, caption, category, createdAt),
                    maskedFile = f,
                    createdAt = createdAt,
                    caption = caption,
                    category = category,
                    deletedAt = if (trash) meta?.optLong("deletedAt", f.lastModified())
                        ?: f.lastModified() else 0L
                )
            } catch (e: StorageIntegrityException) {
                if (!allowPartial) throw e
                unreadable++
                null
            }
        }.sortedByDescending { if (trash) it.deletedAt else it.createdAt }
        PhotoListing(photos, unreadable)
    }

    /** Updates the caption/category for a photo, preserving its original timestamp and uuid. */
    fun updateMeta(id: String, caption: String, category: String): Unit = synchronized(stateLock) {
        val meta = readMeta(id)
        val createdAt = meta?.optLong("createdAt", System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val uuid = ensureUuid(id, meta, caption, category, createdAt)
        writeMeta(id, uuid, caption, category, createdAt)
    }

    /**
     * Replaces the original of an existing photo with [jpegBytes] (e.g. after editing):
     * re-encrypts the original and regenerates the masked preview. Because cropping etc.
     * changes the geometry, any saved custom mask spec is dropped and the preview falls
     * back to the safe whole-frame mosaic (over-masks rather than under-masks).
     */
    fun replaceOriginal(id: String, jpegBytes: ByteArray): Unit = synchronized(stateLock) {
        val existingMeta = readMeta(id)
        File(originalsDir, "$id.enc").writeBytes(encryptBytes(jpegBytes))
        val src = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val masked = MaskingEngine.mask(src)
        File(maskedDir, "$id.jpg").outputStream().use { out ->
            masked.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        src.recycle()
        masked.recycle()
        existingMeta?.let { meta ->
            if (meta.has("mask")) {
                meta.remove("mask")
                writeMetaIn(metaDir, id, meta)
            }
        }
    }

    /** Decrypts and decodes the original (unmasked) image. App-only reveal. */
    fun decryptOriginal(id: String): Bitmap? {
        val enc = File(originalsDir, "$id.enc")
        if (!enc.exists()) return null
        val plain = decryptBytes(enc.readBytes())
        return BitmapFactory.decodeByteArray(plain, 0, plain.size)
    }

    /** True if the encrypted original for [id] is present on disk. */
    fun hasOriginal(id: String): Boolean = File(originalsDir, "$id.enc").exists()

    /** The custom mask spec for [id], or the whole-frame default if none was saved. */
    fun loadMaskSpec(id: String): MaskingEngine.MaskSpec {
        val mask = readMeta(id)?.optJSONObject("mask") ?: return MaskingEngine.MaskSpec()
        return MaskingEngine.specFromJson(mask)
    }

    /**
     * Pro mask editing: regenerates the masked preview from the (untouched) encrypted
     * original according to [spec] and persists the spec so it can be re-edited. Returns
     * false if the original is unavailable.
     */
    fun applyMask(id: String, spec: MaskingEngine.MaskSpec): Boolean = synchronized(stateLock) {
        val meta = readMeta(id) ?: JSONObject()
        val original = decryptOriginal(id) ?: return false
        val masked = MaskingEngine.render(original, spec)
        File(maskedDir, "$id.jpg").outputStream().use { out ->
            masked.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        original.recycle()
        masked.recycle()
        meta.put("mask", MaskingEngine.specToJson(spec))
        writeMetaIn(metaDir, id, meta)
        return true
    }

    /** Decrypts the original (unmasked) JPEG bytes — used when building an encrypted backup. */
    fun decryptOriginalBytes(id: String): ByteArray? {
        val enc = File(originalsDir, "$id.enc")
        if (!enc.exists()) return null
        return decryptBytes(enc.readBytes())
    }

    /**
     * Soft-deletes a photo: moves it to the trash and stamps it with the deletion time.
     * It stays recoverable until [TRASH_TTL_MILLIS] elapses (see [purgeExpiredTrash]).
     */
    fun delete(id: String): Unit = synchronized(stateLock) {
        if (!File(originalsDir, "$id.enc").exists() && !File(maskedDir, "$id.jpg").exists()) return
        val meta = readMeta(id) ?: JSONObject()
        loadDeletedUuids() // Refuse a corrupt ledger before moving any photo files.
        meta.put("deletedAt", System.currentTimeMillis())
        // First persist the recoverable record and tombstone. An interruption may leave
        // duplicate metadata, but must not move the photo without its memo and identity.
        writeMetaIn(trashMetaDir, id, meta)
        addDeletedUuid(meta.optString("uuid", ""))
        move(File(originalsDir, "$id.enc"), File(trashOriginalsDir, "$id.enc"))
        move(File(maskedDir, "$id.jpg"), File(trashMaskedDir, "$id.jpg"))
        clearMetaFiles(metaDir, id)
    }

    /** Strict trash inventory for operations that require every stored identity. */
    fun listTrash(): List<PhotoItem> = scanPhotos(trash = true, allowPartial = false).photos

    fun listTrashForDisplay(): PhotoListing = scanPhotos(trash = true, allowPartial = true)

    /** Restores a trashed photo back to the live library, clearing its deletion stamp. */
    fun restore(id: String): Unit = synchronized(stateLock) {
        if (!File(trashOriginalsDir, "$id.enc").exists() && !File(trashMaskedDir, "$id.jpg").exists()) return
        val meta = readTrashMeta(id) ?: readMeta(id) ?: JSONObject()
        loadDeletedUuids() // No photo is moved if deletion history cannot be read.
        meta.remove("deletedAt")
        writeMetaIn(metaDir, id, meta)
        move(File(trashOriginalsDir, "$id.enc"), File(originalsDir, "$id.enc"))
        move(File(trashMaskedDir, "$id.jpg"), File(maskedDir, "$id.jpg"))
        removeDeletedUuid(meta.optString("uuid", ""))
        clearMetaFiles(trashMetaDir, id)
    }

    /** Permanently removes a single trashed photo. */
    fun purge(id: String): Unit = synchronized(stateLock) {
        File(trashOriginalsDir, "$id.enc").delete()
        File(trashMaskedDir, "$id.jpg").delete()
        clearMetaFiles(trashMetaDir, id)
    }

    /**
     * Removes every on-disk form of one metadata record: encrypted, legacy plaintext, the
     * quarantined copy of an unreadable record, and any staging file left by a save that was
     * interrupted. "Deleted for good" has to mean nothing is left behind.
     */
    private fun clearMetaFiles(dir: File, id: String) {
        File(dir, "$id.enc").delete()
        File(dir, "$id.json").delete()
        File(dir, "$id.enc.unreadable").delete()
        dir.listFiles { f -> f.name.startsWith("$id.enc.tmp") }?.forEach { it.delete() }
    }

    /** Permanently removes every trashed photo. */
    fun emptyTrash(): Unit = synchronized(stateLock) {
        trashMaskedDir.listFiles { f -> f.extension == "jpg" }
            ?.forEach { purge(it.nameWithoutExtension) }
    }

    /** Permanently removes trashed photos older than [TRASH_TTL_MILLIS]. */
    fun purgeExpiredTrash(): Unit = synchronized(stateLock) {
        val cutoff = System.currentTimeMillis() - TRASH_TTL_MILLIS
        trashMaskedDir.listFiles { f -> f.extension == "jpg" }?.forEach { f ->
            val id = f.nameWithoutExtension
            val deletedAt = try {
                readTrashMeta(id)?.optLong("deletedAt", 0L) ?: 0L
            } catch (e: StorageIntegrityException) {
                // Preserve the unreadable record; it must not hide or purge other photos.
                return@forEach
            }
            if (deletedAt in 1L until cutoff) purge(id)
        }
    }

    private fun readTrashMeta(id: String): JSONObject? = readMetaIn(trashMetaDir, id)

    /** Moves [from] to [to], falling back to copy+delete across filesystem boundaries. */
    private fun move(from: File, to: File) {
        if (!from.exists()) return
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
        from.delete()
    }

    /**
     * Exports ONLY the masked preview to the shared gallery (Pictures/PrivacyCamera).
     * The original is never exported.
     */
    fun exportMaskedToGallery(item: PhotoItem): Boolean {
        // Scoped-storage MediaStore writes (no permission needed) require API 29+.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${item.id}_masked.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/PrivacyCamera"
            )
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(item.maskedFile.readBytes())
            }
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    /**
     * UUIDs ever imported through the Lite-migration path. This is the basis for the
     * lifetime import cap: the set only grows, so deleting an imported photo never frees a
     * slot (which would otherwise allow a delete -> re-import loop past the cap).
     */
    fun loadImportedUuids(): Set<String> = synchronized(stateLock) {
        readIdentitySet(importedUuidsFile)
    }

    /** Records [uuids] as imported-via-migration (union with the existing set). */
    fun addImportedUuids(uuids: Collection<String>): Unit = synchronized(stateLock) {
        if (uuids.isEmpty()) return
        val merged = loadImportedUuids().toMutableSet().apply { addAll(uuids) }
        val arr = JSONArray()
        merged.forEach { arr.put(it) }
        writeAtomically(importedUuidsFile) { arr.toString().toByteArray(Charsets.UTF_8) }
    }

    /** UUIDs the user has deleted on this install (tombstones); see [deletedUuidsFile]. */
    fun loadDeletedUuids(): Set<String> = synchronized(stateLock) {
        readIdentitySet(deletedUuidsFile)
    }

    private fun readIdentitySet(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        try {
            val arr = JSONArray(file.readText())
            return (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            // An unreadable ledger is not an empty history: resetting it would allow
            // re-imports or resurrect photos the owner deliberately deleted.
            throw StorageIntegrityException(file.name, e)
        }
    }

    private fun writeDeletedUuids(set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        writeAtomically(deletedUuidsFile) { arr.toString().toByteArray(Charsets.UTF_8) }
    }

    /** Marks [uuid] as deleted-on-this-device so a backup restore won't resurrect it. */
    private fun addDeletedUuid(uuid: String): Unit = synchronized(stateLock) {
        if (uuid.isEmpty()) return
        val set = loadDeletedUuids()
        if (uuid in set) return
        writeDeletedUuids(set + uuid)
    }

    /** Clears [uuid]'s tombstone (the photo is back), e.g. when restored from the trash. */
    private fun removeDeletedUuid(uuid: String): Unit = synchronized(stateLock) {
        if (uuid.isEmpty()) return
        val set = loadDeletedUuids()
        if (uuid !in set) return
        writeDeletedUuids(set - uuid)
    }

    /** User-defined categories (persisted across sessions). */
    fun loadCustomCategories(): List<String> = synchronized(stateLock) {
        val file = when {
            categoriesFile.exists() -> categoriesFile
            legacyCategoriesFile.exists() -> legacyCategoriesFile
            else -> return emptyList()
        }
        try {
            val text = if (file == categoriesFile) {
                String(decryptBytes(file.readBytes()), Charsets.UTF_8)
            } else file.readText()
            val arr = JSONArray(text)
            return (0 until arr.length()).map { arr.getString(it) }
        } catch (e: VaultClosedException) {
            throw e
        } catch (e: SealKeyUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw StorageIntegrityException(file.name, e)
        }
    }

    /** Writes the category list encrypted, dropping any plaintext predecessor. */
    private fun saveCustomCategories(names: List<String>) {
        val arr = JSONArray()
        names.forEach { arr.put(it) }
        writeAtomically(categoriesFile) {
            encryptBytes(arr.toString().toByteArray(Charsets.UTF_8))
        }
        legacyCategoriesFile.delete()
    }

    /** Moves a plaintext category list from an older install into the encrypted form. */
    private fun migrateCategoriesToEncrypted(): Unit = synchronized(stateLock) {
        if (!legacyCategoriesFile.exists()) return
        try {
            if (categoriesFile.exists()) {
                // Verify the replacement before discarding the only readable old copy.
                loadCustomCategories()
                legacyCategoriesFile.delete()
            } else saveCustomCategories(loadCustomCategories())
        } catch (e: Exception) {
            // Keep both versions untouched and retry after the read problem is resolved.
        }
    }

    /**
     * Removes a user-defined category from the picker.
     *
     * Only the catalogue entry goes: photos keep whatever category name is written on them.
     * That is why the caller must check the name is unused first — [CategoryCatalog.build]
     * would otherwise put it straight back (a category in use is always offered).
     */
    fun removeCustomCategory(name: String): Unit = synchronized(stateLock) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || PhotoCategories.isBuiltIn(trimmed)) return
        val current = loadCustomCategories().toMutableList()
        if (!current.remove(trimmed)) return
        saveCustomCategories(current)
    }

    /** Adds a new custom category if it is non-blank and not already known. */
    fun addCustomCategory(name: String): Unit = synchronized(stateLock) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || PhotoCategories.isBuiltIn(trimmed)) return
        val current = loadCustomCategories().toMutableList()
        if (trimmed in current) return
        current.add(trimmed)
        saveCustomCategories(current)
    }

    /**
     * Appends an access-log entry. The log is encrypted at rest (AES-256-GCM via
     * [CryptoManager], same as photo originals) and never leaves the device. A photo-less
     * event (file-level import/export, setting change, log management) uses an empty
     * [photoId].
     */
    fun logAccess(photoId: String, action: String, caption: String, auth: String = "") =
        logAccessAt(System.currentTimeMillis(), photoId, action, caption, auth)

    /** [logAccess] with the time given rather than taken: for lines that happened earlier. */
    fun logAccessAt(
        timestamp: Long,
        photoId: String,
        action: String,
        caption: String,
        auth: String = ""
    ) {
        val arr = readAccessLogArray()
        arr.put(
            JSONObject()
                .put("t", timestamp)
                .put("a", action)
                .put("id", photoId)
                .put("c", caption)
                // Absent on entries written before this field existed; read back as "".
                .put("auth", auth)
        )
        writeAccessLogArray(arr)
    }

    /** Loads the (uncompacted) detail access log, newest first. */
    fun loadAccessLog(): List<AccessEntry> = jsonArrayToEntries(readAccessLogArray()).sortedByDescending { it.timestamp }

    /**
     * Permanently erases all log history (detail entries AND monthly archives). Returns the
     * number of entries erased, so the caller can record a [AccessActions.LOG_DELETE]
     * tombstone stating how much was removed — the log system audits its own erasure, so a
     * wipe is never itself invisible.
     */
    fun clearAccessLog(): Int {
        val detailCount = readAccessLogArray().length()
        val archivedCount = listArchivedMonths().sumOf { it.total }
        accessLogFile.delete()
        logArchiveDir.listFiles()?.forEach { it.delete() }
        return detailCount + archivedCount
    }

    /**
     * Rolls detail-log entries older than the retention window into per-month, gzip+encrypted
     * archives, removing them from the detail log. Retention keeps whichever is LARGER of:
     * entries from the last [DETAIL_RETENTION_DAYS] days, or the most recent
     * [DETAIL_MIN_COUNT] entries overall (so light usage across many years still keeps a
     * useful amount of raw detail). Safe to call repeatedly (e.g. on every app start); a
     * no-op run compacts nothing. Returns the list of calendar months ("yyyy-MM") that were
     * rolled up in this call, so the caller can record one [AccessActions.LOG_COMPACT] entry
     * per month — compaction reorganizes data, it never discards it.
     */
    fun compactAccessLogIfNeeded(): List<String> {
        val all = jsonArrayToEntries(readAccessLogArray()).sortedByDescending { it.timestamp }
        if (all.isEmpty()) return emptyList()

        val cutoff = System.currentTimeMillis() - DETAIL_RETENTION_DAYS * 24 * 60 * 60 * 1000
        // Split by index (not a set difference) so entries that are structurally identical
        // (same millisecond/action/photo/caption) can never collide and vanish.
        val retained = mutableListOf<AccessEntry>()
        val aged = mutableListOf<AccessEntry>()
        all.forEachIndexed { index, entry ->
            if (index < DETAIL_MIN_COUNT || entry.timestamp >= cutoff) retained.add(entry) else aged.add(entry)
        }
        if (aged.isEmpty()) return emptyList()

        val byMonth = aged.groupBy { monthKey(it.timestamp) }
        for ((month, entries) in byMonth) {
            val existing = readArchiveMonth(month)
            writeArchiveMonth(month, existing + entries)
        }

        val retainedArr = JSONArray()
        retained.sortedBy { it.timestamp }.forEach { retainedArr.put(entryToJson(it)) }
        writeAccessLogArray(retainedArr)

        return byMonth.keys.sorted()
    }

    /** Lists archived months (newest first) with per-action-code counts, for a history UI. */
    fun listArchivedMonths(): List<ArchivedMonth> {
        val months = logArchiveDir.listFiles { f -> f.name.endsWith(".json.gz.enc") }
            ?.map { it.name.removeSuffix(".json.gz.enc") }
            ?: emptyList()
        return months.sortedDescending().mapNotNull { month ->
            val entries = readArchiveMonth(month)
            if (entries.isEmpty()) return@mapNotNull null
            val counts = entries.groupingBy { it.action }.eachCount()
            ArchivedMonth(month, counts, entries.size)
        }
    }

    /** Fully decompresses/decrypts one archived month's entries, newest first (UI expansion). */
    fun loadArchivedMonthEntries(month: String): List<AccessEntry> =
        readArchiveMonth(month).sortedByDescending { it.timestamp }

    // ---- access-log internals ----

    private fun entryToJson(e: AccessEntry): JSONObject =
        JSONObject().put("t", e.timestamp).put("a", e.action).put("id", e.photoId).put("c", e.caption)

    private fun jsonArrayToEntries(arr: JSONArray): List<AccessEntry> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AccessEntry(
                timestamp = o.optLong("t"),
                action = o.optString("a"),
                photoId = o.optString("id"),
                caption = o.optString("c"),
                auth = o.optString("auth")
            )
        }

    /** Reads the encrypted detail log, transparently migrating a pre-existing plaintext file. */
    private fun readAccessLogArray(): JSONArray {
        if (!accessLogFile.exists() && legacyAccessLogFile.exists()) {
            val migrated = try {
                JSONArray(legacyAccessLogFile.readText())
            } catch (e: Exception) {
                JSONArray()
            }
            writeAccessLogArray(migrated)
            legacyAccessLogFile.delete()
        }
        if (!accessLogFile.exists()) return JSONArray()
        return try {
            JSONArray(String(decryptBytes(accessLogFile.readBytes()), Charsets.UTF_8))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeAccessLogArray(arr: JSONArray) {
        accessLogFile.writeBytes(encryptBytes(arr.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun readArchiveMonth(month: String): List<AccessEntry> {
        val f = File(logArchiveDir, "$month.json.gz.enc")
        if (!f.exists()) return emptyList()
        return try {
            val gzipped = decryptBytes(f.readBytes())
            val json = java.util.zip.GZIPInputStream(gzipped.inputStream()).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            jsonArrayToEntries(JSONArray(json))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeArchiveMonth(month: String, entries: List<AccessEntry>) {
        val arr = JSONArray()
        entries.sortedBy { it.timestamp }.forEach { arr.put(entryToJson(it)) }
        val plain = arr.toString().toByteArray(Charsets.UTF_8)
        val gzipOut = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gzipOut).use { it.write(plain) }
        File(logArchiveDir, "$month.json.gz.enc").writeBytes(encryptBytes(gzipOut.toByteArray()))
    }

    /** "yyyy-MM" key (device-local calendar) for grouping entries into monthly archives. */
    private fun monthKey(timestampMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestampMillis
        return "%04d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
    }

    private fun readMeta(id: String): JSONObject? = readMetaIn(metaDir, id)

    /**
     * Reads one metadata record, preferring the encrypted form and falling back to the
     * plaintext file left by an older install (see [migratePlaintextToEncrypted]).
     */
    private fun readMetaIn(dir: File, id: String): JSONObject? = synchronized(stateLock) {
        val enc = File(dir, "$id.enc")
        if (enc.exists()) {
            try {
                return JSONObject(String(decryptBytes(enc.readBytes()), Charsets.UTF_8))
            } catch (e: VaultClosedException) {
                throw e
            } catch (e: SealKeyUnavailableException) {
                throw e
            } catch (e: Exception) {
                // Never turn a failed read into 'missing'. In particular, listing must not
                // mint a new uuid, lose memos, or overwrite bytes after a failed quarantine.
                throw StorageIntegrityException(enc.name, e)
            }
        }
        // Older versions may already have quarantined this record. Keep that evidence and
        // refuse to create a replacement identity in its absence.
        val quarantined = File(dir, "$id.enc.unreadable")
        if (quarantined.exists()) throw StorageIntegrityException(quarantined.name)
        return readLegacyMeta(dir, id)
    }

    private fun readLegacyMeta(dir: File, id: String): JSONObject? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        try {
            return JSONObject(f.readText())
        } catch (e: Exception) {
            throw StorageIntegrityException(f.name, e)
        }
    }

    /**
     * Returns the photo's stable [PhotoItem.uuid], minting and persisting one for legacy
     * photos that pre-date uuid tracking. Once assigned, a photo's uuid never changes, so
     * exports/imports can rely on it for de-duplication.
     */
    private fun ensureUuid(
        id: String,
        meta: JSONObject?,
        caption: String,
        category: String,
        createdAt: Long
    ): String {
        val existing = meta?.optString("uuid", "")?.takeIf { it.isNotEmpty() }
        if (existing != null) return existing
        val minted = java.util.UUID.randomUUID().toString()
        writeMeta(id, minted, caption, category, createdAt)
        return minted
    }

    private fun writeMeta(
        id: String,
        uuid: String,
        caption: String,
        category: String,
        createdAt: Long
    ) {
        // Start from any existing metadata so extra keys (e.g. a custom mask spec) survive
        // a caption/category edit.
        val json = (readMeta(id) ?: JSONObject()).apply {
            put("uuid", uuid)
            put("caption", caption)
            put("category", category)
            put("createdAt", createdAt)
        }
        writeMetaIn(metaDir, id, json)
    }

    /**
     * Writes one metadata record encrypted, then drops any plaintext predecessor.
     *
     * The write is staged through a temporary file and renamed, so a crash mid-write cannot
     * leave a truncated record where a readable one used to be. The plaintext file is only
     * deleted once the encrypted one is in place.
     */
    private fun writeMetaIn(dir: File, id: String, json: JSONObject): Unit = synchronized(stateLock) {
        // A write must not replace an existing unreadable record, even if its caller
        // supplied a complete new object (migration, restore, or mask updates).
        readMetaIn(dir, id)
        writeAtomically(File(dir, "$id.enc")) {
            encryptBytes(json.toString().toByteArray(Charsets.UTF_8))
        }
        File(dir, "$id.json").delete()
    }

    /**
     * One-time move of metadata from plaintext JSON to the encrypted form.
     *
     * The originals and the access log were already encrypted at rest; the memo and category
     * were not, even though a memo like "母のマイナンバー 表面" describes the very thing the
     * original is encrypted to protect. This closes that gap for installs that predate it.
     *
     * Failures are deliberately survivable: anything that cannot be converted keeps its
     * plaintext file and is retried on the next launch, so a bad record can never cost the
     * user their metadata. (Same shape as the access-log migration above.)
     */
    /**
     * Rewrites every encrypted file under the store with [encryptNew].
     *
     * Used when the master key changes hands — moving the library from the old
     * AndroidKeyStore key to one held by [com.istech.privacycamera.crypto.MasterKeyVault].
     * The key inside the keystore cannot be exported, so re-encrypting is the only way
     * across; there is no wrapping trick that avoids it.
     *
     * **Interrupting this does not break the library.** Each file is written whole and moved
     * into place, so no file is ever half-converted, and [decryptAny] is expected to try both
     * keys — which leaves a half-finished run readable under either one. Running it again
     * simply finishes the job: converted files decrypt with the new key and are rewritten
     * with the same key, unchanged. That property is deliberate. The alternative, a run that
     * must complete or else, is how a library of ID documents gets lost to a flat battery.
     *
     * Masked previews are not touched: they are already the redacted version, stored as
     * ordinary JPEGs, and hold nothing the original does not.
     *
     * @return how many files were rewritten
     */
    fun reencryptAll(
        decryptAny: (ByteArray) -> ByteArray,
        encryptNew: (ByteArray) -> ByteArray,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        val files = encryptedFiles()
        onProgress(0, files.size)
        var done = 0
        for (file in files) {
            try {
                val plain = decryptAny(file.readBytes())
                val tmp = File(file.parentFile, "${file.name}.rewrap.${System.nanoTime()}")
                tmp.writeBytes(encryptNew(plain))
                if (!tmp.renameTo(file)) {
                    file.writeBytes(tmp.readBytes())
                }
                tmp.delete()
                done++
            } catch (e: Exception) {
                // Unreadable under either key: leave it exactly as it is. Overwriting it
                // with something derived from a failed read would destroy what is there.
            }
            onProgress(done, files.size)
        }
        return done
    }

    /**
     * Deletes the whole library — originals, masked previews, metadata, trash, log.
     *
     * Only reached from the passphrase reset, where the user has been told in as many words
     * that the photos go with it. Someone who has forgotten their passphrase otherwise has a
     * library they can never open and can never clear; that dead end is worse than this.
     */
    fun eraseEverything() {
        baseDir.listFiles()?.forEach { entry ->
            // Keep the keys folder: the vault erases its own wrappings, and deleting them
            // from under it would leave a half-reset state neither side owns.
            if (entry.name != "keys") entry.deleteRecursively()
        }
        listOf(originalsDir, maskedDir, metaDir, trashDir, trashOriginalsDir, trashMaskedDir,
            trashMetaDir, logArchiveDir).forEach { it.mkdirs() }
    }

    /** True when the store holds anything encrypted at all — asked before promising a migration. */
    fun hasStoredFiles(): Boolean = encryptedFiles().isNotEmpty()

    /** Every file the store keeps encrypted, in a stable order. */
    private fun encryptedFiles(): List<File> =
        baseDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".enc") }
            // Half-written staging files from an interrupted write are not the library.
            .filterNot { it.name.contains(".tmp.") || it.name.contains(".rewrap.") }
            .sortedBy { it.absolutePath }
            .toList()

    fun migratePlaintextToEncrypted(): Unit = synchronized(stateLock) {
        migrateCategoriesToEncrypted()
        listOf(metaDir, trashMetaDir).forEach { dir ->
            dir.listFiles { f -> f.extension == "json" }?.forEach { legacy ->
                val id = legacy.nameWithoutExtension
                try {
                    if (File(dir, "$id.enc").exists()) {
                        readMetaIn(dir, id)
                        legacy.delete()
                        return@forEach
                    }
                    val json = JSONObject(legacy.readText())
                    writeMetaIn(dir, id, json) // deletes the plaintext once the .enc lands
                } catch (e: Exception) {
                    // Leave the plaintext in place; the next launch tries again.
                }
            }
        }
    }

    companion object {
        private val stateLocks = ConcurrentHashMap<String, Any>()

        // Detail access-log retention: keep whichever is larger of "last N days" or "last N
        // entries" (see compactAccessLogIfNeeded), then roll the rest into monthly archives.
        private const val DETAIL_RETENTION_DAYS = 365L
        private const val DETAIL_MIN_COUNT = 1000

        /** How long a soft-deleted photo is kept in the trash before being purged. */
        const val TRASH_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days

        /** Rotates the given JPEG bytes by [rotationDegrees] and re-encodes as JPEG. */
        fun rotateJpeg(jpegBytes: ByteArray, rotationDegrees: Int): ByteArray {
            if (rotationDegrees == 0) return jpegBytes
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return jpegBytes
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
            if (rotated != bmp) bmp.recycle()
            rotated.recycle()
            return out.toByteArray()
        }
    }
}
