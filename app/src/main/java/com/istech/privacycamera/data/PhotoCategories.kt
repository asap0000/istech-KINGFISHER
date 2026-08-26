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

/** Categories for classifying captured documents. */
object PhotoCategories {
    const val UNCLASSIFIED = "未分類"

    /** Built-in categories. Users can add their own on top of these. */
    val PREDEFINED = listOf(
        "運転免許証",
        "健康保険証",
        "マイナンバーカード",
        "預金通帳",
        "クレジットカード",
        "パスポート",
        "その他"
    )

    /** True if [name] is one of the built-in names (predefined or unclassified). */
    fun isBuiltIn(name: String): Boolean =
        name == UNCLASSIFIED || name in PREDEFINED
}
