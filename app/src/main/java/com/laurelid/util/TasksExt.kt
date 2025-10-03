package com.laurelid.util

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (continuation.isCancelled) return@addOnCompleteListener
        val exception = task.exception
        if (exception != null) {
            continuation.resumeWithException(exception)
        } else {
            @Suppress("UNCHECKED_CAST")
            continuation.resume(task.result as T)
        }
    }
    continuation.invokeOnCancellation { cancel() }
}
