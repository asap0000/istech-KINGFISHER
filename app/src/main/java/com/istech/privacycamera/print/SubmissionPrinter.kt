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
package com.istech.privacycamera.print

import android.content.Context
import android.graphics.Bitmap
import android.print.PrintAttributes
import android.print.PrintManager
import kotlinx.coroutines.delay

/** Terminal outcome of a submission-print job. */
enum class PrintOutcome { COMPLETED, FAILED, CANCELED, TIMEOUT, UNAVAILABLE }

/**
 * Starts a system print job for a single, already-prepared (masked + watermarked) bitmap
 * and waits for its outcome. No INTERNET permission is needed: [PrintManager.print] is an
 * IPC to the system print framework, which hands the job to whichever print service the
 * user has installed — network transport, if any, is that service's concern, not this
 * app's (see docs/2026-07-04_仕様_提出用出力機能.md §9).
 *
 * The framework's [android.print.PrintJob] exposes no completion callback, so this polls
 * its state at [POLL_INTERVAL_MS] until a terminal state or [POLL_TIMEOUT_MS] elapses (the
 * timeout is generous because printing involves the user picking a printer/destination).
 */
object SubmissionPrinter {
    private const val POLL_INTERVAL_MS = 500L
    private const val POLL_TIMEOUT_MS = 5 * 60 * 1000L

    suspend fun print(context: Context, bitmap: Bitmap, jobName: String): PrintOutcome {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: return PrintOutcome.UNAVAILABLE
        val adapter = SubmissionPrintAdapter(context, bitmap, jobName)
        val job = printManager.print(jobName, adapter, PrintAttributes.Builder().build())
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when {
                job.isCompleted -> return PrintOutcome.COMPLETED
                job.isCancelled -> return PrintOutcome.CANCELED
                job.isFailed -> return PrintOutcome.FAILED
            }
            delay(POLL_INTERVAL_MS)
        }
        return PrintOutcome.TIMEOUT
    }
}
