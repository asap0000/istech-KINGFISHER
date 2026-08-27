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

/**
 * Builds the list of selectable categories and decides how many of them are shown at once.
 *
 * Two rules drive everything here:
 *
 *  1. **A name that is in use is always offered.** The catalogue is not just the file of
 *     user-defined names — it also includes every category actually written on a photo.
 *     Without this, a name can exist on photos while being absent from the picker, which
 *     makes those photos impossible to filter for (it happened in two ways: restoring a
 *     backup, which carries per-photo categories but not the catalogue file, and removing
 *     a category that photos still use).
 *  2. **The cap is on display, not on how many may exist.** Capping the number of
 *     categories would create a "you must delete something before you can continue"
 *     moment; this app deliberately avoids those (deletes go to a 30-day trash, restore
 *     adds without removing). Crowding is a display problem, so it is solved by showing
 *     the first [MAX_VISIBLE] and folding the rest away.
 *
 * Pure functions only — no storage, no Android — so all of this is unit-testable.
 */
object CategoryCatalog {

    /**
     * How many category chips are shown before the rest fold into "ほかのカテゴリ".
     *
     * Sized so that everything shipped with the app — the seven built-ins plus 未分類 — fits,
     * which means folding only ever starts once the user has added a category of their own.
     * At seven, a brand-new install would already show "ほかのカテゴリ (1)" hiding 「その他」,
     * which is the opposite of what a cap for crowding is for.
     */
    val MAX_VISIBLE = PhotoCategories.PREDEFINED.size + 1

    /**
     * Longest category name accepted. Names are echoed inside the confirm button
     * ("「祖母」を作って保存"), so an unbounded one would push the dialog's buttons apart.
     */
    const val MAX_NAME_LENGTH = 20

    /**
     * The full ordered catalogue: built-ins, then user-defined, then any category found on
     * a photo that neither of those already covers, and 未分類 last.
     *
     * [usedOnPhotos] should include photos in the trash — a restore would otherwise bring
     * back a photo whose category is no longer offered.
     */
    fun build(custom: List<String>, usedOnPhotos: Collection<String>): List<String> {
        val ordered = LinkedHashSet<String>()
        ordered.addAll(PhotoCategories.PREDEFINED)
        custom.forEach { name ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty() && trimmed != PhotoCategories.UNCLASSIFIED) ordered.add(trimmed)
        }
        // Orphan rescue: a name that is written on a photo but is in neither list above.
        usedOnPhotos.forEach { name ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty() && trimmed != PhotoCategories.UNCLASSIFIED) ordered.add(trimmed)
        }
        ordered.add(PhotoCategories.UNCLASSIFIED)
        return ordered.toList()
    }

    /**
     * Splits the catalogue into the chips shown up front and the ones folded away.
     *
     * [selected] is always kept visible even if it sits past the cap, so the current choice
     * never disappears behind "ほかのカテゴリ" (the user would have no way to see what is set).
     */
    fun split(
        all: List<String>,
        selected: String? = null,
        limit: Int = MAX_VISIBLE
    ): Pair<List<String>, List<String>> {
        if (limit <= 0) return emptyList<String>() to all
        if (all.size <= limit) return all to emptyList()
        val visible = all.take(limit).toMutableList()
        val folded = all.drop(limit).toMutableList()
        if (selected != null && selected in folded) {
            // Swap the selected one into view, dropping the last visible entry instead.
            // The displaced entry goes back where it came from in the original order, so the
            // folded list stays in the same sequence the user saw before.
            folded.remove(selected)
            val displaced = visible.removeAt(visible.size - 1)
            visible.add(selected)
            val insertAt = folded.indexOfFirst { all.indexOf(it) > all.indexOf(displaced) }
            if (insertAt < 0) folded.add(displaced) else folded.add(insertAt, displaced)
        }
        return visible to folded
    }

    /**
     * Whether [name] may be removed from the catalogue.
     *
     * Built-ins are permanent, and a category that photos still carry stays — removing it
     * would leave those photos unfilterable. The user is told to move the photos first.
     */
    fun isRemovable(name: String, usageCount: Int): Boolean =
        !PhotoCategories.isBuiltIn(name) && usageCount == 0
}
