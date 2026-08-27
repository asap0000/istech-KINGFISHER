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

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Pins the approved behaviour of search (解釈ゲート 2026-08-27, ③).
 */
class PhotoSearchTest {

    private fun photo(id: String, caption: String, category: String) = PhotoItem(
        id = id,
        uuid = id,
        maskedFile = File("$id.jpg"),
        createdAt = 0L,
        caption = caption,
        category = category
    )

    private val items = listOf(
        photo("a", "祖母 診察券", PhotoCategories.UNCLASSIFIED),
        photo("b", "保険証 表", "祖母"),
        photo("c", "母 マイナンバー", "母"),
        photo("d", "ＡＢＣ保険", "その他")
    )

    @Test
    fun `メモが一致した写真が出る`() {
        val hit = PhotoSearch.filter(items, query = "診察")
        assertThat(hit.map { it.id }).containsExactly("a")
    }

    @Test
    fun `カテゴリ名が一致した写真も出る`() {
        // This is what makes a category removed from the picker still reachable.
        val hit = PhotoSearch.filter(items, query = "祖母")
        assertThat(hit.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `全角と半角は同じものとして扱う`() {
        assertThat(PhotoSearch.filter(items, query = "ABC").map { it.id }).containsExactly("d")
        assertThat(PhotoSearch.filter(items, query = "ＡＢＣ").map { it.id }).containsExactly("d")
    }

    @Test
    fun `大文字と小文字は同じものとして扱う`() {
        assertThat(PhotoSearch.filter(items, query = "abc").map { it.id }).containsExactly("d")
    }

    @Test
    fun `読み仮名では引けない(承認済みの割り切り)`() {
        // Kept as a test so that if a reading dictionary is ever added, this fails loudly and
        // the offline/dependency question gets asked again rather than slipping in.
        assertThat(PhotoSearch.filter(items, query = "そぼ")).isEmpty()
    }

    @Test
    fun `検索が空なら全部出る`() {
        assertThat(PhotoSearch.filter(items, query = "")).hasSize(items.size)
        assertThat(PhotoSearch.filter(items, query = "   ")).hasSize(items.size)
    }

    @Test
    fun `カテゴリ絞り込みと検索は両方効く`() {
        val hit = PhotoSearch.filter(items, query = "保険", category = "祖母")
        assertThat(hit.map { it.id }).containsExactly("b")
    }

    @Test
    fun `当てはまらない語では空になる`() {
        assertThat(PhotoSearch.filter(items, query = "存在しない語")).isEmpty()
    }
}
