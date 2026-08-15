package dev.whitespc.roam.diagnostics

import android.util.Log

/** Writes sanitized messages to logcat and the user-exportable diagnostic log. */
@Suppress("unused")
object RoamLog {
    fun v(tag: String, msg: String): Int = write(Log.VERBOSE, "V", tag, msg)

    fun d(tag: String, msg: String): Int = write(Log.DEBUG, "D", tag, msg)

    fun d(tag: String, msg: String, throwable: Throwable?): Int =
        write(Log.DEBUG, "D", tag, msg, throwable)

    fun i(tag: String, msg: String): Int = write(Log.INFO, "I", tag, msg)

    fun w(tag: String, msg: String): Int = write(Log.WARN, "W", tag, msg)

    fun w(tag: String, msg: String, throwable: Throwable?): Int =
        write(Log.WARN, "W", tag, msg, throwable)

    fun w(tag: String, throwable: Throwable?): Int =
        write(Log.WARN, "W", tag, "", throwable)

    fun e(tag: String, msg: String): Int = write(Log.ERROR, "E", tag, msg)

    fun e(tag: String, msg: String, throwable: Throwable?): Int =
        write(Log.ERROR, "E", tag, msg, throwable)

    private fun write(
        priority: Int,
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ): Int {
        val safeTag = DiagnosticSanitizer.sanitizeTag(tag)
        val safeMessage = LogStore.append(level, safeTag, message, throwable)
        return Log.println(priority, safeTag, safeMessage)
    }
}
