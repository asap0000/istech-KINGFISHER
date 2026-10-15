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
package com.istech.privacycamera.testutil

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 構成の外で書かれた状態（入力欄がフォーカスを受けたとき、試験側から書いた値）を、
 * 構成へ届け続けるための代役。
 *
 * 通常は Compose 自身が「グローバルな書き込みを見張って、メインのループで反映を通知する」仕組みを
 * 1つ持っているが、その仕組みは**プロセスで最初に動いたテストの窓に結びついたまま**になる。
 * Robolectric のように同じ JVM で何百ものテストを順に回すと、2つ目以降のテストでは通知が
 * 届かず、未反映の書き込みが残り続けて「落ち着くまで待つ」が戻らない。同じ仕事を、そのテストの
 * ループの上でやり直すだけ。終わったら、届け残しを片付けて次のテストに持ち越さない。
 */
class SnapshotApplyRule : TestWatcher() {

    private var handle: ObserverHandle? = null

    override fun starting(description: Description) {
        val main = Handler(Looper.getMainLooper())
        handle = Snapshot.registerGlobalWriteObserver {
            main.post { Snapshot.sendApplyNotifications() }
        }
    }

    override fun finished(description: Description) {
        handle?.dispose()
        handle = null
        Snapshot.sendApplyNotifications()
    }
}
