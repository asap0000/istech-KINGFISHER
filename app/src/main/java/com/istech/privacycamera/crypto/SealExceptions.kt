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

/**
 * Thrown when something encrypted needs the master key and the vault is closed.
 *
 * This is "cannot read right now", which is a different thing from "this record is broken".
 * Callers that quarantine unreadable data must let this one through untouched: the data is
 * fine, the key simply is not in hand.
 */
class VaultClosedException : IllegalStateException("vault is closed")

/**
 * Thrown when the vault is open but a sealed record cannot be opened because the sealing
 * private key could not be loaded (the wrapped copy was missing or unreadable).
 *
 * Like [VaultClosedException] it means "cannot read", not "broken" — the record must be left
 * exactly as it is.
 */
class SealKeyUnavailableException : IllegalStateException("seal private key is unavailable")
