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
package com.istech.privacycamera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.istech.privacycamera.data.CategoryCatalog

/**
 * Dialog for adding/editing a photo's memo (caption) and category. Used both right
 * after capture and from the viewer. Supports adding a brand-new category inline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoDialog(
    initialCaption: String,
    initialCategory: String,
    categories: List<String>,
    onAddCategory: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (caption: String, category: String) -> Unit,
    title: String = "メモを追加"
) {
    var caption by remember { mutableStateOf(initialCaption) }
    var category by remember { mutableStateOf(initialCategory) }
    var newCategory by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }

    val pendingCategory = newCategory.trim()
    val hasPending = pendingCategory.isNotEmpty()

    // Only the first few chips are shown; the rest fold away behind a counter. The cap is on
    // what is displayed, not on how many categories may exist — running out of room should
    // never turn into "delete something before you can continue".
    val (visibleCategories, foldedCategories) = CategoryCatalog.split(categories, category)

    /**
     * Commits the dialog. A name typed into the "new category" field counts as chosen even
     * if the user never pressed "+": the text is right there on screen, so silently dropping
     * it is the one outcome nobody expects.
     */
    fun commit() {
        if (hasPending) {
            onAddCategory(pendingCategory)
            onSave(caption.trim(), pendingCategory)
        } else {
            onSave(caption.trim(), category)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("メモ（例: 母のマイナンバー 表面）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Text(
                    "カテゴリ",
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shown = if (showAll) categories else visibleCategories
                    shown.forEach { c ->
                        FilterChip(
                            selected = category == c && !hasPending,
                            onClick = {
                                category = c
                                // Choosing an existing chip means the half-typed name is not
                                // what the user wants after all.
                                newCategory = ""
                            },
                            label = { Text(c) }
                        )
                    }
                    if (foldedCategories.isNotEmpty()) {
                        AssistChip(
                            onClick = { showAll = !showAll },
                            label = {
                                Text(
                                    if (showAll) "畳む"
                                    else "ほかのカテゴリ (${foldedCategories.size})"
                                )
                            }
                        )
                    }
                }
                // Inline "add new category" row.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    OutlinedTextField(
                        value = newCategory,
                        // Capped because the name is echoed in the confirm button; without a
                        // limit a pasted paragraph pushes "スキップ" off the dialog.
                        onValueChange = {
                            newCategory = it.take(CategoryCatalog.MAX_NAME_LENGTH)
                        },
                        label = { Text("新しいカテゴリを追加") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = hasPending,
                        onClick = {
                            onAddCategory(pendingCategory)
                            category = pendingCategory
                            newCategory = ""
                            // A brand-new category can sit past the display cap, so open the
                            // full list to show it actually landed and is selected.
                            showAll = true
                        }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "カテゴリを追加")
                    }
                }
            }
        },
        confirmButton = {
            // The label states what pressing it will do, so adopting the typed name is
            // visible before the press rather than a surprise after it.
            TextButton(onClick = { commit() }) {
                Text(if (hasPending) "「$pendingCategory」を作って保存" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("スキップ") }
        }
    )
}
