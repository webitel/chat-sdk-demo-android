package com.webitel.chat.sdk.demo_android.repo

import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The SDK is callback-based (works from both Java and Kotlin):
 * every async call takes a `(Result<T>) -> Unit` callback.
 *
 * This helper turns such a call into a suspend function:
 *
 *     val page = awaitResult { client.getDialogs(request, it) }
 */
suspend fun <T> awaitResult(call: (callback: (Result<T>) -> Unit) -> Unit): T =
    suspendCancellableCoroutine { cont ->
        call { result -> cont.resumeWith(result) }
    }
