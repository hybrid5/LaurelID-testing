package com.laurelid.util

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    // Complete (success / failure)
    addOnCompleteListener { task ->
        if (cont.isCancelled) return@addOnCompleteListener

        if (task.isCanceled) {
            cont.cancel(CancellationException("Task was cancelled"))
            return@addOnCompleteListener
        }

        val exception = task.exception
        if (exception != null) {
            cont.resumeWithException(exception)
        } else {
            @Suppress("UNCHECKED_CAST")
            cont.resume(task.result as T)
        }
    }

    // If the Google Task gets canceled, cancel the coroutine as well.
    addOnCanceledListener {
        if (cont.isActive) {
            cont.cancel(CancellationException("Task was cancelled"))
        }
    }

    // Note: There's no general way to cancel a Google Task from coroutine cancellation here.
    // For APIs that accept a CancellationToken, wire that up at call site.
}
