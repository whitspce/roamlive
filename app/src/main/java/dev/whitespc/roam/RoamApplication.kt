package dev.whitespc.roam

import android.app.Application
import android.util.Log
import java.io.IOException

private const val TAG = "RoamApplication"

class RoamApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installNetworkCrashGuard()
        NetworkMonitor.init(this)
    }

    /**
     * Catches uncaught IOExceptions that come from third-party-library coroutines
     * doing socket writes when the network dies. These crashes are benign (the
     * connection died, the retry logic handles it) but the library code doesn't
     * catch them, so they propagate to the platform as FATAL EXCEPTION and kill us.
     *
     * Specifically scoped to:
     *  - Background coroutine dispatcher threads (DefaultDispatcher-worker-*, etc.)
     *  - Exception is or wraps IOException
     *  - Stack contains a known network-write frame (ktor TLS, ktor io, java NIO socket)
     *
     * Anything else still crashes normally so we don't mask real bugs.
     */
    private fun installNetworkCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            if (isBenignNetworkException(thread, exception)) {
                Log.w(TAG, "Swallowed library network exception on ${thread.name}", exception)
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, exception)
        }
    }

    private fun isBenignNetworkException(thread: Thread, e: Throwable): Boolean {
        if (!hasIoException(e)) return false
        val threadName = thread.name ?: return false
        val onDispatcherThread =
            threadName.contains("DefaultDispatcher-worker") ||
                threadName.contains("DispatchedTask") ||
                threadName.startsWith("OkHttp")
        if (!onDispatcherThread) return false
        return stackContainsNetworkWrite(e)
    }

    private fun hasIoException(e: Throwable?): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is IOException) return true
            cur = cur.cause
        }
        return false
    }

    private fun stackContainsNetworkWrite(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            for (frame in cur.stackTrace) {
                val cls = frame.className
                if (cls.startsWith("io.ktor.network.") ||
                    cls.startsWith("io.ktor.utils.io.") ||
                    cls.startsWith("sun.nio.ch.")
                ) {
                    return true
                }
            }
            cur = cur.cause
        }
        return false
    }
}
