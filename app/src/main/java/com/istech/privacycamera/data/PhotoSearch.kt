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

import java.text.Normalizer

/**
 * Free-text search over the photos already held in memory.
 *
 * Deliberately index-free: the gallery loads every photo's memo and category anyway, so
 * matching against that list costs nothing extra and — unlike a search index — leaves no
 * second copy of the text on disk. For an app whose subject matter is ID documents, not
 * writing an extra searchable copy of "母のマイナンバー 表面" is the point.
 *
 * Searching covers the memo **and the category name**. Matching the category is what lets
 * a name that has been taken out of the picker still be reachable: the name stays written
 * on the photo, so typing it finds those photos again.
 *
 * Not covered, by design: the image itself. This app does not read the contents of a
 * picture to decide anything, and adding OCR for search would break that promise.
 */
object PhotoSearch {

    /**
     * Case- and width-insensitive key. NFKC folds full-width to half-width (ＡＢＣ -> ABC,
     * ７ -> 7) and composes kana, so what looks the same to a reader matches.
     *
     * Note there is no reading dictionary: "そぼ" does not find "祖母". Supplying one would
     * mean shipping a morphological analyser or dictionary — a dependency this app's
     * offline guarantee would then have to account for.
     */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()

    /** True if [item]'s memo or category contains [query] (substring, normalized). */
    fun matches(item: PhotoItem, query: String): Boolean {
        val q = normalize(query.trim())
        if (q.isEmpty()) return true
        return normalize(item.caption).contains(q) || normalize(item.category).contains(q)
    }

    /**
     * Applies the search box and the category filter together.
     *
     * Both narrow the same list, so picking a category and then typing searches within it.
     * A blank query means "no search", not "match nothing".
     */
    fun filter(
        items: List<PhotoItem>,
        query: String = "",
        category: String? = null
    ): List<PhotoItem> {
        return items.filter { item ->
            (category == null || item.category == category) && matches(item, query)
        }
    }
}
