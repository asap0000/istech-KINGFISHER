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
import org.junit.Test

/**
 * Pins the approved behaviour of the category picker (解釈ゲート 2026-08-27, ②/④).
 */
class CategoryCatalogTest {

    @Test
    fun `未分類は常に最後に来る`() {
        val all = CategoryCatalog.build(custom = listOf("母"), usedOnPhotos = emptyList())
        assertThat(all.last()).isEqualTo(PhotoCategories.UNCLASSIFIED)
    }

    @Test
    fun `写真が使っている名前は候補に無くても必ず出る`() {
        // A restored backup carries per-photo categories but not the catalogue file.
        val all = CategoryCatalog.build(custom = emptyList(), usedOnPhotos = listOf("祖母"))
        assertThat(all).contains("祖母")
    }

    @Test
    fun `候補から外した名前も写真が残っていれば戻ってくる`() {
        // The removal took "確定申告" out of the stored list, but a photo still carries it.
        val all = CategoryCatalog.build(
            custom = listOf("母"),
            usedOnPhotos = listOf("確定申告")
        )
        assertThat(all).containsAtLeast("母", "確定申告")
    }

    @Test
    fun `同じ名前は重複しない`() {
        val all = CategoryCatalog.build(
            custom = listOf("母", "母"),
            usedOnPhotos = listOf("母", "運転免許証")
        )
        assertThat(all.count { it == "母" }).isEqualTo(1)
        assertThat(all.count { it == "運転免許証" }).isEqualTo(1)
    }

    @Test
    fun `表示は8個までで残りは折りたたまれる`() {
        val all = (1..12).map { "cat$it" }
        val (visible, folded) = CategoryCatalog.split(all)
        assertThat(visible).hasSize(8)
        assertThat(folded).hasSize(12 - 8)
    }

    @Test
    fun `最初から入っているカテゴリだけなら折りたたみは起きない`() {
        // A brand-new install must not show "ほかのカテゴリ" before the user has added
        // anything: the cap exists for crowding the user caused, not for the built-ins.
        val fresh = CategoryCatalog.build(custom = emptyList(), usedOnPhotos = emptyList())
        val (visible, folded) = CategoryCatalog.split(fresh, selected = PhotoCategories.UNCLASSIFIED)
        assertThat(folded).isEmpty()
        assertThat(visible).containsExactlyElementsIn(fresh)
    }

    @Test
    fun `折りたたんでもカテゴリは1つも失われない`() {
        // The selected-category swap moves entries between the two lists; dropping one on the
        // way would make it unreachable from both the chips and the "ほかのカテゴリ" sheet.
        val all = (1..12).map { "cat$it" }
        listOf(null, "cat1", "cat9", "cat12").forEach { selected ->
            val (visible, folded) = CategoryCatalog.split(all, selected = selected)
            assertThat(visible + folded).containsExactlyElementsIn(all)
        }
    }

    @Test
    fun `選択中のカテゴリが上限より後ろでも隠れない`() {
        // Otherwise the user cannot see what the photo is currently set to.
        val all = (1..12).map { "cat$it" }
        val (visible, folded) = CategoryCatalog.split(all, selected = "cat11")
        assertThat(visible).contains("cat11")
        assertThat(folded).doesNotContain("cat11")
        assertThat(visible).hasSize(CategoryCatalog.MAX_VISIBLE)
        assertThat(visible + folded).containsExactlyElementsIn(all)
    }

    @Test
    fun `候補が上限以下なら折りたたみは起きない`() {
        val all = listOf("a", "b", "c")
        val (visible, folded) = CategoryCatalog.split(all)
        assertThat(visible).isEqualTo(all)
        assertThat(folded).isEmpty()
    }

    @Test
    fun `使っている写真があるカテゴリは外せない`() {
        assertThat(CategoryCatalog.isRemovable("母", usageCount = 3)).isFalse()
        assertThat(CategoryCatalog.isRemovable("母", usageCount = 0)).isTrue()
    }

    @Test
    fun `最初から入っているカテゴリは外せない`() {
        assertThat(CategoryCatalog.isRemovable("運転免許証", usageCount = 0)).isFalse()
        assertThat(CategoryCatalog.isRemovable(PhotoCategories.UNCLASSIFIED, usageCount = 0))
            .isFalse()
    }
}
