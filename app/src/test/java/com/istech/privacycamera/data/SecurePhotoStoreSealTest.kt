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

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.crypto.SealKeyUnavailableException
import com.istech.privacycamera.crypto.VaultClosedException
import com.istech.privacycamera.crypto.VaultSession
import com.istech.privacycamera.testutil.TestImages
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 鍵が閉じたまま写真に封をして書く層（段2a）の試験。
 *
 * 一番守りたいのは [`閉じたまま読んでも、何も退けず何も変えない`]。記録を読めないとき、
 * 以前は「壊れている」として脇へ退け、空のメモで書き直していた。鍵が閉じているだけで
 * 読めないのを壊れていると取り違えると、ライブラリ全体のメモ・カテゴリ・uuid を失う。
 */
@RunWith(RobolectricTestRunner::class)
class SecurePhotoStoreSealTest {

    private lateinit var context: Context
    private lateinit var secureDir: File
    private lateinit var keysDir: File

    private val master: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    private val jpeg: ByteArray by lazy {
        val bmp = TestImages.gradient()
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** 鍵の組は作らず、閉じた状態の [VaultSession]（公開鍵はあるが、マスター鍵は無い）。 */
    private fun closedSession() = VaultSession(keysDir) { error("no legacy key") }

    private fun storeFor(session: VaultSession, seal: ((ByteArray) -> ByteArray)? = session::seal) =
        SecurePhotoStore(context, session::encrypt, session::decrypt, seal)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        secureDir = File(context.filesDir, "secure")
        secureDir.deleteRecursively()
        keysDir = File(secureDir, "keys").apply { mkdirs() }
        sharedPair.forEach { (name, bytes) -> File(keysDir, name).writeBytes(bytes) }
    }

    /**
     * secure/ 以下の全ファイル（鍵の置き場と、ストアが作る .nomedia は除く）の相対パス → 中身。
     * パスは区切りを '/' にそろえる（Windows でも同じ文字列になるように）。
     */
    private fun snapshot(): Map<String, List<Byte>> =
        secureDir.walkTopDown()
            .filter { it.isFile && !it.path.startsWith(keysDir.path) && it.name != ".nomedia" }
            .associate { it.relativeTo(secureDir).invariantSeparatorsPath to it.readBytes().toList() }

    // ---- a / b : 閉じたまま書いて、開けると読める ----------------------------------------------

    @Test
    fun `閉じたまま写真を保存すると、開けた一覧にメモ・カテゴリ・uuid・撮影時刻がそのまま出る`() {
        val closed = closedSession()
        val saved = storeFor(closed).saveSealed(jpeg, "母のマイナンバー 表面", "母")
        assertThat(closed.isOpen).isFalse()

        val opened = VaultSession(keysDir) { error("no legacy key") }.also { it.open(master) }
        val listed = storeFor(opened).list().single()

        assertThat(listed.id).isEqualTo(saved.id)
        assertThat(listed.caption).isEqualTo("母のマイナンバー 表面")
        assertThat(listed.category).isEqualTo("母")
        assertThat(listed.uuid).isEqualTo(saved.uuid)
        assertThat(listed.createdAt).isEqualTo(saved.createdAt)
        // 原本は封をされたまま、開けると元のバイト列に戻る。
        assertThat(storeFor(opened).decryptOriginalBytes(saved.id)).isEqualTo(jpeg)
        // 平文では置かれていない。
        assertThat(File(secureDir, "originals/${saved.id}.enc").readBytes().copyOfRange(0, 4))
            .isEqualTo(byteArrayOf(0x50, 0x43, 0x53, 0x31))
        assertThat(String(File(secureDir, "meta/${saved.id}.enc").readBytes(), Charsets.ISO_8859_1))
            .doesNotContain("マイナンバー")
    }

    @Test
    fun `閉じたままメモを書き直すと、開けた一覧に新しいメモが出て、uuidと撮影時刻は変わらない`() {
        val saved = storeFor(closedSession()).saveSealed(jpeg)

        storeFor(closedSession()).rewriteSealedMeta(
            saved.id, saved.uuid, saved.createdAt, "あとから書いたメモ", "祖母"
        )

        val opened = VaultSession(keysDir) { error("no legacy key") }.also { it.open(master) }
        val listed = storeFor(opened).list().single()
        assertThat(listed.caption).isEqualTo("あとから書いたメモ")
        assertThat(listed.category).isEqualTo("祖母")
        assertThat(listed.uuid).isEqualTo(saved.uuid)
        assertThat(listed.createdAt).isEqualTo(saved.createdAt)
        // 書き直しで一時ファイルが残らない。
        assertThat(File(secureDir, "meta").listFiles { f -> f.name.contains(".tmp") }).isEmpty()
    }

    @Test
    fun `開いたあとの更新は、封をした記録をマスター鍵の記録に置き換えられる`() {
        // 開いた後は ふつうの経路（読む入口は decrypt の一か所）で読み書きできること。
        val saved = storeFor(closedSession()).saveSealed(jpeg, "最初", "母")
        val opened = VaultSession(keysDir) { error("no legacy key") }.also { it.open(master) }
        val store = storeFor(opened)

        store.updateMeta(saved.id, "更新後", "祖母")

        val listed = store.list().single()
        assertThat(listed.caption).isEqualTo("更新後")
        assertThat(listed.uuid).isEqualTo(saved.uuid)
        assertThat(listed.createdAt).isEqualTo(saved.createdAt)
    }

    // ---- c : ★罠の固定 -------------------------------------------------------------------------

    private fun libraryMixingSealedAndMasterRecords(): List<String> {
        // 封をした記録 2 件（閉じたまま）+ マスター鍵の記録 1 件（開けて）+ カテゴリ + 履歴。
        val sealedA = storeFor(closedSession()).saveSealed(jpeg, "封A", "母")
        val sealedB = storeFor(closedSession()).saveSealed(jpeg, "封B", "祖母")
        val opened = VaultSession(keysDir) { error("no legacy key") }.also { it.open(master) }
        val store = storeFor(opened)
        val ordinary = store.save(jpeg, "マスター鍵のメモ", "父")
        store.addCustomCategory("離婚調停")
        store.logAccess(ordinary.id, AccessActions.OPEN, "x")
        return listOf(sealedA.id, sealedB.id, ordinary.id)
    }

    @Test
    fun `閉じたまま読んでも、何も退けず何も変えない`() {
        val ids = libraryMixingSealedAndMasterRecords()
        val before = snapshot()
        val store = storeFor(closedSession())

        // 記録を読む入口を、閉じたまま順に踏む。どれも「鍵が閉じている」と伝えて止まる。
        assertThrows(VaultClosedException::class.java) { store.list() }
        ids.forEach { id ->
            assertThrows(VaultClosedException::class.java) { store.updateMeta(id, "上書き", "母") }
            assertThrows(VaultClosedException::class.java) { store.loadMaskSpec(id) }
            assertThrows(VaultClosedException::class.java) { store.delete(id) }
            assertThrows(VaultClosedException::class.java) { store.decryptOriginalBytes(id) }
        }

        // .unreadable が 1 つもできず、どのファイルの中身も 1 バイトも変わらない。
        val after = snapshot()
        assertThat(after.keys.filter { it.contains("unreadable") }).isEmpty()
        assertThat(after).isEqualTo(before)

        // そして開ければ、全部そのまま読める（何も失っていない）。
        val opened = VaultSession(keysDir) { error("no legacy key") }.also { it.open(master) }
        val listed = storeFor(opened).list().associateBy { it.id }
        assertThat(listed[ids[0]]!!.caption).isEqualTo("封A")
        assertThat(listed[ids[1]]!!.caption).isEqualTo("封B")
        assertThat(listed[ids[2]]!!.caption).isEqualTo("マスター鍵のメモ")
        assertThat(storeFor(opened).loadCustomCategories()).contains("離婚調停")
    }

    @Test
    fun `開いていても封の秘密鍵が読み込めないときは、退けずに止まる`() {
        val ids = libraryMixingSealedAndMasterRecords()
        File(keysDir, "seal.priv").delete() // 秘密鍵が失われた（あるいは読めない）状態で開く
        val before = snapshot()
        val opened = VaultSession(keysDir) { error("no legacy key") }.also { it.open(master) }
        val store = storeFor(opened)

        assertThrows(SealKeyUnavailableException::class.java) { store.list() }
        assertThrows(SealKeyUnavailableException::class.java) { store.updateMeta(ids[0], "上書き", "母") }
        assertThrows(SealKeyUnavailableException::class.java) { store.delete(ids[0]) }

        val after = snapshot()
        assertThat(after.keys.filter { it.contains("unreadable") }).isEmpty()
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `本当に壊れている記録は、今までどおり脇へ退ける`() {
        // 退ける仕組み自体は残っている（鍵が閉じているのとは別の話）。
        val opened = VaultSession(keysDir) { error("no legacy key") }.also { it.open(master) }
        val store = storeFor(opened)
        val photo = store.save(jpeg, "壊す", "母")
        File(secureDir, "meta/${photo.id}.enc").writeBytes("これは壊れた記録".toByteArray())

        store.list()

        assertThat(File(secureDir, "meta/${photo.id}.enc.unreadable").exists()).isTrue()
    }

    @Test
    fun `閉じたまま保存しても、既にある記録を読まず何も変えない`() {
        libraryMixingSealedAndMasterRecords()
        val before = snapshot()

        val saved = storeFor(closedSession()).saveSealed(jpeg, "新しい", "母")

        val after = snapshot()
        // 既存のファイルはそのまま。増えたのは今回の 3 つ（原本・メモ・見本）だけ。
        assertThat(after.filterKeys { it in before }).isEqualTo(before)
        assertThat(after.keys - before.keys).containsExactly(
            "originals/${saved.id}.enc", "meta/${saved.id}.enc", "masked/${saved.id}.jpg"
        )
    }

    // ---- d : 数え方 ---------------------------------------------------------------------------

    @Test
    fun `閉じたままでも枚数が正しい`() {
        val closed = storeFor(closedSession())
        assertThat(closed.photoCount()).isEqualTo(0)

        closed.saveSealed(jpeg)
        closed.saveSealed(jpeg)

        assertThat(closed.photoCount()).isEqualTo(2)
        // 開いても同じ（復号しないので、どちらでも同じ数）。
        val opened = VaultSession(keysDir) { error("no legacy key") }.also { it.open(master) }
        storeFor(opened).save(jpeg)
        assertThat(storeFor(opened).photoCount()).isEqualTo(3)
        assertThat(storeFor(closedSession()).photoCount()).isEqualTo(3)
    }

    // ---- e : 途中で落ちたら何も残さない -------------------------------------------------------

    @Test
    fun `保存の途中で失敗したら、その写真のファイルはどこにも残らず、既存には触れない`() {
        libraryMixingSealedAndMasterRecords()
        val before = snapshot()
        val session = closedSession()

        // 2 回目の封（= メモ）で落とす。原本は既に書かれている状態からの後始末を見る。
        var calls = 0
        val failingSecond: (ByteArray) -> ByteArray = { bytes ->
            if (++calls == 2) throw java.io.IOException("ディスクがいっぱい")
            session.seal(bytes)
        }
        assertThrows(java.io.IOException::class.java) {
            storeFor(session, failingSecond).saveSealed(jpeg, "落ちる", "母")
        }

        assertThat(calls).isEqualTo(2)
        assertThat(snapshot()).isEqualTo(before) // 原本・メモ・見本・一時ファイルのどこにも残らない
        assertThat(storeFor(session).photoCount()).isEqualTo(3)
    }

    @Test
    fun `最初の封で失敗しても何も残らない`() {
        val before = snapshot()
        val failing: (ByteArray) -> ByteArray = { throw IllegalStateException("no sealing key") }

        assertThrows(IllegalStateException::class.java) {
            storeFor(closedSession(), failing).saveSealed(jpeg)
        }

        assertThat(snapshot()).isEqualTo(before)
    }

    // ---- f / g : 未解錠で撮影した履歴の仮置き -------------------------------------------------

    @Test
    fun `閉じたまま積んだ履歴は、開けて取り込むと元の時刻で入り、仮置きは空になる`() {
        val closed = storeFor(closedSession())
        // 古い方を後から積む: 取り込みは積んだ順ではなく時刻の順。
        closed.appendSealedCaptureLog("IMG_B", 2_000_000_000_000L)
        closed.appendSealedCaptureLog("IMG_A", 1_700_000_000_000L)
        assertThat(File(secureDir, "pending_log").listFiles()!!.map { it.extension }.toSet())
            .containsExactly("seal")

        val opened = VaultSession(keysDir) { error("no legacy key") }.also { it.open(master) }
        val store = storeFor(opened)
        store.foldPendingLog()

        val entries = store.loadAccessLog()
        assertThat(entries.map { it.action }).containsExactly(
            AccessActions.CAPTURE_LOCKED, AccessActions.CAPTURE_LOCKED
        )
        // 新しい順。どちらも元の時刻のまま（取り込んだ時刻ではない）。
        assertThat(entries.map { it.timestamp }).containsExactly(2_000_000_000_000L, 1_700_000_000_000L).inOrder()
        assertThat(entries.map { it.photoId }).containsExactly("IMG_B", "IMG_A").inOrder()
        assertThat(File(secureDir, "pending_log").listFiles().orEmpty()).isEmpty()

        // 二度呼んでも増えない。
        store.foldPendingLog()
        assertThat(store.loadAccessLog()).hasSize(2)
    }

    @Test
    fun `鍵が閉じたまま取り込みを呼んでも、仮置きは消えない`() {
        storeFor(closedSession()).appendSealedCaptureLog("IMG_A", 1_700_000_000_000L)
        val before = snapshot()

        storeFor(closedSession()).foldPendingLog()

        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `全消去で仮置きの履歴も消える`() {
        val store = storeFor(closedSession())
        store.appendSealedCaptureLog("IMG_A", 1_700_000_000_000L)
        assertThat(File(secureDir, "pending_log").exists()).isTrue()

        store.eraseEverything()

        assertThat(File(secureDir, "pending_log").exists()).isFalse()
        // 鍵の置き場は全消去の対象外（金庫自身が消す）。
        assertThat(File(keysDir, "seal.pub").exists()).isTrue()
    }

    @Test
    fun `未解錠で撮影の表示名がある`() {
        assertThat(AccessActions.label(AccessActions.CAPTURE_LOCKED)).isEqualTo("未解錠で撮影")
    }

    private companion object {
        /** seal.pub / seal.priv（マスター鍵で包んだもの）。RSA 3072 の生成が遅いので一度だけ作る。 */
        val sharedPair: Map<String, ByteArray> by lazy {
            val dir = java.nio.file.Files.createTempDirectory("seal-template").toFile()
            try {
                val key: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
                val session = VaultSession(dir) { it }
                session.open(key)
                session.ensureSealKeys()
                mapOf(
                    "seal.pub" to File(dir, "seal.pub").readBytes(),
                    "seal.priv" to File(dir, "seal.priv").readBytes()
                )
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
