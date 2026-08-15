package dev.whitespc.roam.ui.screens.settings

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whitespc.roam.diagnostics.LogStore
import dev.whitespc.roam.storage.BackupStore
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.ui.theme.RoamLive
import dev.whitespc.roam.update.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Diagnostics, the support link, and the app version. */
@Composable
internal fun HelpAboutPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    var updateCheckEnabled by remember { mutableStateOf(Prefs.updateCheckEnabled(context)) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf<String?>(null) }

    // Use the Activity scope and Toast because the document picker can recreate
    // this composable before the backup result returns.
    val activity = remember(context) { context.findComponentActivity() }
    val backupScope = activity?.lifecycleScope

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null || backupScope == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    BackupStore.export(context, it)
                } ?: false
            }
            Toast.makeText(
                context,
                if (ok) "Backup saved." else "Couldn't write the backup file.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || backupScope == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use {
                    BackupStore.import(context, it)
                } ?: false
            }
            Toast.makeText(
                context,
                if (ok) {
                    "Backup imported. Reopen Settings to see the changes; " +
                        "private connection details and web addresses need re-entering."
                } else {
                    "Couldn't read that file as a Roam backup."
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    PaneScaffold(title = "Help & about") {
        SubHeading("Diagnostics")
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = null,
                tint = RoamLive,
                modifier = Modifier.size(22.dp).padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            HelpText(
                "A rolling text log of what the engine did. Useful for bug " +
                    "reports and troubleshooting. The stream key is " +
                    "never included.",
            )
        }
        // Save to Downloads: direct file save, easy to find in Files app.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val filename = LogStore.saveToDownloads(context)
                    val msg = if (filename != null) {
                        "Saved to Downloads / $filename"
                    } else {
                        "Couldn't save the log file."
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                .padding(vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "Save logs to Downloads",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        SubHeading("Backup")
        ActionRow(
            icon = Icons.Filled.Upload,
            label = "Export settings & overlays",
            description = "Roam credentials and web addresses stay on this phone. " +
                "Imported HTML is included as-is, so check it before sharing " +
                "the backup.",
            onClick = {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(Date())
                exportLauncher.launch("roam-backup-$date.zip")
            },
        )
        ActionRow(
            icon = Icons.Filled.Download,
            label = "Import from backup",
            description = "Replaces current settings and overlays. Takes " +
                "effect when you leave Settings.",
            // Any file: some providers report our .zip as octet-stream or
            // x-zip-compressed, so a strict "application/zip" filter greys the
            // backup out and it can't be picked. We validate on read instead.
            onClick = { importLauncher.launch(arrayOf("*/*")) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubHeading("Updates")
        ToggleRow(
            label = "Check for updates automatically",
            description = "Once a day, Roam fetches a version file from " +
                "roamlive.app and compares it on the phone. Nothing about " +
                "you or your phone is sent.",
            checked = updateCheckEnabled,
            onCheckedChange = {
                updateCheckEnabled = it
                Prefs.setUpdateCheckEnabled(context, it)
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !updateChecking) {
                    updateChecking = true
                    updateStatus = "Checking..."
                    updateUrl = null
                    scope.launch {
                        when (val result = UpdateChecker.checkNow(context)) {
                            is UpdateChecker.CheckResult.Available -> {
                                updateStatus =
                                    "Roam Live ${result.update.versionName} is available."
                                updateUrl = result.update.url
                            }
                            UpdateChecker.CheckResult.UpToDate ->
                                updateStatus = "You're up to date."
                            UpdateChecker.CheckResult.Failed ->
                                updateStatus =
                                    "Couldn't reach roamlive.app. Try again later."
                            UpdateChecker.CheckResult.ManagedByPlay ->
                                updateStatus =
                                    "This install updates through Google Play."
                        }
                        updateChecking = false
                    }
                }
                .padding(vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "Check now",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
        }
        updateStatus?.let { HelpText(it) }
        updateUrl?.let { url ->
            LinkRow(
                icon = Icons.Filled.Download,
                label = "Get the update",
                description = "Opens the download page in your browser.",
                url = url,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        SubHeading("Help")
        LinkRow(
            icon = Icons.Filled.BugReport,
            label = "Help and feedback",
            description = "Guides, known issues, and ways to get in touch.",
            url = "https://roamlive.app/support",
        )
        if (versionName != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Roam Live $versionName",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

/** Unwrap the Compose [Context] to the hosting [ComponentActivity]. Its
 *  lifecycleScope outlives this composable, so backup work survives the
 *  file-picker round-trip that can rebuild the composition. */
private fun Context.findComponentActivity(): ComponentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
