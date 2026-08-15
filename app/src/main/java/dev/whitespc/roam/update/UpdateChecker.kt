package dev.whitespc.roam.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import dev.whitespc.roam.diagnostics.RoamLog as Log
import dev.whitespc.roam.storage.Prefs
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "RoamUpdate"
private const val VERSION_URL = "https://roamlive.app/version.json"
private const val UPDATE_PAGE_URL = "https://roamlive.app/"
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
private const val MAX_MANIFEST_BYTES = 16 * 1024
private const val MAX_VERSION_NAME_LENGTH = 32
private val VERSION_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")

/**
 * Sideload-channel update discovery. The signed-in-app update destination is
 * fixed locally; the remote manifest can announce a version but cannot choose
 * a link for the app to open.
 *
 * Privacy: one anonymous GET of a static file over HTTPS, identical for every
 * install, no identifiers, no cookies. Off-switchable in Settings; this is
 * the only network request Roam makes that isn't streaming, chat, or OBS.
 */
object UpdateChecker {

    data class Update(val versionCode: Int, val versionName: String, val url: String)

    sealed class CheckResult {
        data class Available(val update: Update) : CheckResult()
        data object UpToDate : CheckResult()
        data object Failed : CheckResult()
        data object ManagedByPlay : CheckResult()
    }

    /** True when this install came from Google Play. Play installs are signed
     *  with a different key than the site APK (Play App Signing), so our APK
     *  can't install over them: offering it would end in a confusing "app not
     *  installed" error. Play handles their updates itself. */
    fun installedFromPlay(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (android.os.Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        installer == "com.android.vending"
    }.getOrDefault(false)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Daily auto-check. Fetches only when the last successful check is older
     *  than a day, then returns what the user should currently be offered.
     *  Null when up to date, disabled, dismissed, or the site is unreachable
     *  (a broken fetch is a silent no, never an error surface). */
    suspend fun maybeCheck(context: Context): Update? {
        // Play installs never see the sideload banner (and skip the daily
        // fetch entirely: Play delivers their updates).
        if (installedFromPlay(context)) return null
        if (!Prefs.updateCheckEnabled(context)) return null
        val now = System.currentTimeMillis()
        val lastCheck = Prefs.lastUpdateCheckMs(context)
        if (lastCheck <= 0L || now < lastCheck || now - lastCheck >= CHECK_INTERVAL_MS) {
            fetch(context)
        }
        return currentOffer(context)
    }

    /** Manual "check now" from Settings. Always fetches (except on Play
     *  installs, where updates are Play's job and the site APK can't be
     *  installed over the Play signature anyway). */
    suspend fun checkNow(context: Context): CheckResult {
        if (installedFromPlay(context)) return CheckResult.ManagedByPlay
        val fetched = fetch(context) ?: return CheckResult.Failed
        val offerable = fetched.versionCode > installedVersionCode(context)
        return if (offerable) CheckResult.Available(fetched) else CheckResult.UpToDate
    }

    /** The update the user should see right now, from the last stored fetch:
     *  newer than installed and not already dismissed. */
    fun currentOffer(context: Context): Update? {
        val (code, name, _) = Prefs.availableUpdate(context) ?: return null
        if (code <= installedVersionCode(context)) {
            // They updated; the stored offer is stale.
            Prefs.clearAvailableUpdate(context)
            return null
        }
        if (code <= Prefs.dismissedUpdateCode(context)) return null
        return validatedUpdate(code, name)
    }

    private suspend fun fetch(context: Context): Update? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(VERSION_URL).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "version fetch failed ${response.code}")
                    return@withContext null
                }
                val responseBody = response.body
                val declaredLength = responseBody.contentLength()
                if (declaredLength > MAX_MANIFEST_BYTES) {
                    Log.w(TAG, "version manifest is too large")
                    return@withContext null
                }
                val body = readUtf8WithLimit(responseBody.byteStream(), MAX_MANIFEST_BYTES)
                    ?: run {
                        Log.w(TAG, "version manifest exceeded the size limit")
                        return@withContext null
                    }
                val json = JSONObject(body)
                val update = validatedUpdate(
                    versionCode = json.optInt("versionCode", -1),
                    versionName = json.optString("versionName"),
                ) ?: run {
                    Log.w(TAG, "version manifest has invalid fields")
                    return@withContext null
                }
                // Only successful fetches move the daily clock, so an offline
                // launch retries next launch instead of going quiet for a day.
                Prefs.setLastUpdateCheckMs(context, System.currentTimeMillis())
                if (update.versionCode > installedVersionCode(context)) {
                    Prefs.setAvailableUpdate(
                        context, update.versionCode, update.versionName, update.url,
                    )
                    Log.d(TAG, "update available: ${update.versionName} (${update.versionCode})")
                } else {
                    Prefs.clearAvailableUpdate(context)
                }
                update
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "version fetch error", e)
            null
        }
    }

    fun installedVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info).toInt()
    }.getOrDefault(Int.MAX_VALUE)
}

internal fun validatedUpdate(versionCode: Int, versionName: String): UpdateChecker.Update? {
    val cleanName = versionName.trim()
    if (versionCode <= 0 || cleanName.length !in 1..MAX_VERSION_NAME_LENGTH) return null
    if (!VERSION_NAME_PATTERN.matches(cleanName)) return null
    return UpdateChecker.Update(versionCode, cleanName, UPDATE_PAGE_URL)
}

internal fun readUtf8WithLimit(input: InputStream, maxBytes: Int): String? {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 4096))
    val buffer = ByteArray(4096)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toString(StandardCharsets.UTF_8.name())
}
