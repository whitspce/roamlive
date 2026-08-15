package dev.whitespc.roam

import android.app.Application
import dev.whitespc.roam.diagnostics.LogStore
import dev.whitespc.roam.storage.BackupStore
import dev.whitespc.roam.storage.Prefs

class RoamApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // First: any subsequent Log.* in this method (or anywhere) routes
        // through RoamLog to LogStore. Initialising before the other steps
        // guarantees we capture their setup logs too.
        LogStore.init(this)
        Prefs.sanitizeUserConfiguration(this)
        BackupStore.recoverInterruptedImport(this)
        NetworkMonitor.init(this)
    }
}
