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

import java.security.SecureRandom

/**
 * The written-down code that opens the library when the passphrase has been forgotten.
 *
 * Not a back door. A code that let anyone in without the passphrase would be a back door for
 * whoever picks up the phone as well — the hole that rescues and the hole that attacks are the
 * same hole. What this is instead is a second front door handed over in advance: the code
 * wraps the same master key the passphrase wraps (see [MasterKeyVault]), so it reaches the
 * photos rather than merely permitting a reset. Permission alone would be worthless, because
 * a lost key does not come back by being allowed to.
 *
 * Shaped for paper, since that is where it will live:
 *
 *  - **16 characters, in four groups of four** — `K7M2-P9XR-4TQW-3BND`. The alphabet holds 32
 *    symbols, so each character carries 5 bits and the whole code carries 80.
 *  - **No `0`/`O` and no `1`/`I`** — the pairs that get mixed up between handwriting and a
 *    keyboard. They are removed from the alphabet rather than corrected on input: there is
 *    nothing to guess about a character that can never occur. (`L` stays: only the lowercase
 *    `l` resembles a one, and the code is upper case throughout.)
 *  - **Digits and letters together** — digits alone would need far more of them for the same
 *    strength, and a word list would mean shipping the list.
 */
object RecoveryCode {

    /** Digits 2-9 and A-Z without I and O: 32 symbols, five bits each. */
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** Characters in a code, separators excluded. */
    const val LENGTH = 16

    /** Characters per group as written down. */
    const val GROUP = 4

    /** Groups in a code. */
    const val GROUPS = LENGTH / GROUP

    /**
     * A fresh code in bare form (no separators).
     *
     * The alphabet is exactly 32 symbols so five random bits pick one with no remainder —
     * masking is uniform here, where taking a remainder of an arbitrary size would quietly
     * favour the front of the alphabet.
     */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(LENGTH).also { random.nextBytes(it) }
        return buildString(LENGTH) {
            bytes.forEach { append(ALPHABET[it.toInt() and 0x1F]) }
        }
    }

    /** [bare] with a hyphen every [GROUP] characters, as it is shown and written down. */
    fun format(bare: String): String = bare.chunked(GROUP).joinToString("-")

    /** The [GROUPS] groups of [bare], for asking the user to copy one back. */
    fun groups(bare: String): List<String> = bare.chunked(GROUP)

    /**
     * [input] reduced to the bare form to compare or derive a key from.
     *
     * Three things happen, and each has cost someone an afternoon somewhere:
     * full-width characters are folded (a Japanese IME hands the field `Ｋ７Ｍ２`, and the app
     * has already paid for that lesson once — see [BackupCrypto.normalizeWidth]), letters are
     * upper-cased, and anything outside the alphabet is dropped so hyphens and spaces do not
     * have to be typed exactly as printed.
     */
    fun normalize(input: CharArray): CharArray =
        BackupCrypto.normalizeWidth(input)
            .concatToString()
            .uppercase()
            .filter { it in ALPHABET }
            .toCharArray()

    /** True when [input] holds a full-length code, whatever the separators looked like. */
    fun isComplete(input: CharArray): Boolean = normalize(input).size == LENGTH

    /** True when [typed] is the group at [index] of [bare]; used to confirm the user copied it. */
    fun matchesGroup(bare: String, index: Int, typed: CharArray): Boolean =
        normalize(typed).concatToString() == groups(bare).getOrNull(index)
}
