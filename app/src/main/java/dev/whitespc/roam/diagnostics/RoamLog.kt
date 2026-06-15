package dev.whitespc.roam.diagnostics

import android.util.Log

/**
 * Drop-in replacement for [android.util.Log] that ALSO writes through to
 * [LogStore] so the line ends up in the user-shareable diagnostic file.
 *
 * Files opt in with an import alias at the top:
 * ```
 * import dev.whitespc.roam.diagnostics.RoamLog as Log
 * ```
 * and every existing `Log.d(TAG, msg)` / `Log.w(...)` / `Log.e(...)` call
 * automatically goes to both logcat and the rolling file. No call-site changes.
 */
@Suppress("unused")
object RoamLog {
    fun v(tag: String, msg: String): Int {
        LogStore.append("V", tag, msg)
        return Log.v(tag, msg)
    }

    fun d(tag: String, msg: String): Int {
        LogStore.append("D", tag, msg)
        return Log.d(tag, msg)
    }

    fun d(tag: String, msg: String, throwable: Throwable?): Int {
        LogStore.append("D", tag, msg, throwable)
        return Log.d(tag, msg, throwable)
    }

    fun i(tag: String, msg: String): Int {
        LogStore.append("I", tag, msg)
        return Log.i(tag, msg)
    }

    fun w(tag: String, msg: String): Int {
        LogStore.append("W", tag, msg)
        return Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, throwable: Throwable?): Int {
        LogStore.append("W", tag, msg, throwable)
        return Log.w(tag, msg, throwable)
    }

    fun w(tag: String, throwable: Throwable?): Int {
        LogStore.append("W", tag, throwable?.message ?: "", throwable)
        return Log.w(tag, throwable)
    }

    fun e(tag: String, msg: String): Int {
        LogStore.append("E", tag, msg)
        return Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable?): Int {
        LogStore.append("E", tag, msg, throwable)
        return Log.e(tag, msg, throwable)
    }
}
