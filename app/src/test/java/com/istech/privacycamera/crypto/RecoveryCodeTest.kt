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
package com.istech.privacycamera.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 回復コードの形の回帰テスト。
 *
 * このコードは紙に書き写され、忘れた日に打ち込まれる。**書き写しと読み取りの間で失われないこと**
 * が要件なので、見ているのは強さより「人が写して打てるか」である。
 */
class RecoveryCodeTest {

    @Test
    fun `紛らわしい文字は最初から出てこない`() {
        // 手書きと画面の間で混ざる 0/O と 1/I を、入力の補正ではなく字母から外して消す。
        // 補正は「どちらの意味だったか」を推測することになり、推測は外れる。
        val produced = (1..200).flatMap { RecoveryCode.generate().toList() }.toSet()
        assertThat(produced).containsNoneOf('0', 'O', '1', 'I')
    }

    @Test
    fun `16文字を4桁ずつ区切って出す`() {
        val bare = RecoveryCode.generate()
        assertThat(bare).hasLength(RecoveryCode.LENGTH)
        assertThat(RecoveryCode.format(bare)).matches("[^-]{4}-[^-]{4}-[^-]{4}-[^-]{4}")
        assertThat(RecoveryCode.groups(bare)).hasSize(RecoveryCode.GROUPS)
    }

    @Test
    fun `毎回ちがうコードが出る`() {
        assertThat((1..50).map { RecoveryCode.generate() }.toSet()).hasSize(50)
    }

    @Test
    fun `全角で打ち込んでも通る`() {
        // v0.5.4 のバックアップと同じ事故。日本語IMEは英数字の欄に全角を入れてくる。
        // 回復コードは「忘れた日」に打つものなので、ここで弾かれると打つ手が無くなる。
        val normalized = RecoveryCode.normalize("ＫＭ２７".toCharArray())
        assertThat(normalized.concatToString()).isEqualTo("KM27")
    }

    @Test
    fun `小文字でも区切りが違っても同じコードとして読む`() {
        // 画面には K7M2-P9XR… と出るが、紙から打ち直す人が区切りまで再現するとは限らない。
        val bare = "K7M2P9XR4TQW3BND"
        assertThat(RecoveryCode.normalize("k7m2 p9xr-4tqw3bnd".toCharArray()).concatToString())
            .isEqualTo(bare)
        assertThat(RecoveryCode.isComplete("k7m2-p9xr-4tqw-3bnd".toCharArray())).isTrue()
    }

    @Test
    fun `足りない入力は完成と見なさない`() {
        assertThat(RecoveryCode.isComplete("K7M2-P9XR".toCharArray())).isFalse()
        assertThat(RecoveryCode.isComplete("".toCharArray())).isFalse()
    }

    @Test
    fun `控えの確認は指定したかたまりだけを見る`() {
        val bare = "K7M2P9XR4TQW3BND"
        assertThat(RecoveryCode.matchesGroup(bare, 0, "k7m2".toCharArray())).isTrue()
        assertThat(RecoveryCode.matchesGroup(bare, 2, "4TQW".toCharArray())).isTrue()
        // 別のかたまりを打っても通らない（4つのうち1つを聞く意味が消える）。
        assertThat(RecoveryCode.matchesGroup(bare, 2, "K7M2".toCharArray())).isFalse()
    }
}
