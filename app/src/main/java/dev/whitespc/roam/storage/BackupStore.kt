package dev.whitespc.roam.storage

import android.content.Context
import android.content.SharedPreferences
import dev.whitespc.roam.diagnostics.RoamLog as Log
import dev.whitespc.roam.streaming.overlay.OverlayJson
import dev.whitespc.roam.streaming.overlay.OverlayImageStore
import dev.whitespc.roam.streaming.overlay.OverlaySource
import dev.whitespc.roam.streaming.overlay.OverlayWebStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "RoamBackup"

/** One-file backup of non-secret settings and app-managed overlay files. */
object BackupStore {

    private const val BACKUP_VERSION = 2
    private const val LEGACY_BACKUP_VERSION = 1
    private const val BACKUP_FILE_PREFIX = "roam-backup:"
    private const val PREFS_FILE = "roam_prefs"
    private const val PREFS_ENTRY = "prefs.json"
    private const val IMPORT_TRANSACTION_KEY = "backup_import_transaction"
    private const val IMPORT_STAGING_PREFIX = ".backup-import-"
    private const val ROLLBACK_PREFIX = ".backup-rollback-"
    private const val HAD_DIRECTORY_PREFIX = ".had-"
    private const val MAX_ENTRIES = 1_024
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_WEB_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_PREFS_BYTES = 1024L * 1024
    private const val MAX_PREFS_COUNT = 256

    private val EXCLUDED_KEY_PREFIXES = listOf(
        "stream_url", "stream_key", "server_url", "obs_password",
    )

    private val TRANSIENT_KEYS = setOf(
        "last_update_check_ms", "dismissed_update_code",
        "avail_update_code", "avail_update_name", "avail_update_url",
    )

    private val MEDIA_DIRS = listOf("overlay_images", "overlay_web")

    private val IMPORTABLE_TYPES = mapOf(
        "video_width" to "int",
        "video_height" to "int",
        "video_fps" to "int",
        "video_bitrate_kbps" to "int",
        "chat_enabled" to "bool",
        "kick_channel" to "string",
        "twitch_channel" to "string",
        "youtube_channel" to "string",
        "brb_text" to "string",
        "stealth_dot" to "bool",
        "stealth_haptic" to "bool",
        "stealth_pulse_sec" to "int",
        "max_reconnect_min" to "int",
        "overlay_scene_v1" to "string",
        "stabilization_enabled" to "bool",
        "brb_image_path" to "string",
        "auto_bitrate" to "bool",
        "record_while_streaming" to "bool",
        "dual_cam_enabled" to "bool",
        "audio_meter_enabled" to "bool",
        "obs_host" to "string",
        "obs_port" to "int",
        "obs_brb_scene" to "string",
        "obs_sync_streaming" to "bool",
        "obs_brb_mute" to "bool",
        "mic_gain" to "float",
        "chat_text_size_sp" to "int",
        "chat_panel_mode" to "string",
        "chat_panel_side" to "string",
        "mirror_front_pip" to "bool",
        "update_check_enabled" to "bool",
    )

    private fun isCredentialKey(key: String): Boolean =
        EXCLUDED_KEY_PREFIXES.any { key.startsWith(it) }

    fun export(context: Context, out: OutputStream): Boolean = runCatching {
        Prefs.sanitizeUserConfiguration(context)
        val includedFiles = LinkedHashSet<String>()
        var exportedEntries = 1
        var exportedMediaBytes = 0L
        ZipOutputStream(out.buffered()).use { zip ->
            val entries = JSONArray()
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .all.toSortedMap().forEach { (key, value) ->
                    val expectedType = IMPORTABLE_TYPES[key]
                    if (expectedType == null || isCredentialKey(key) ||
                        key in TRANSIENT_KEYS || value == null
                    ) return@forEach
                    val actualType = preferenceType(value) ?: return@forEach
                    if (actualType != expectedType) return@forEach
                    val portableValue = portablePreferenceValue(
                        context,
                        key,
                        value,
                        includedFiles,
                    ) ?: return@forEach
                    entries.put(
                        JSONObject()
                            .put("k", key)
                            .put("t", actualType)
                            .put("v", portableValue),
                    )
                }
            val root = JSONObject()
                .put("backupVersion", BACKUP_VERSION)
                .put("prefs", entries)
            val prefsPayload = root.toString(2).toByteArray(Charsets.UTF_8)
            require(prefsPayload.size <= MAX_PREFS_BYTES) { "too many settings to export" }
            exportedMediaBytes = prefsPayload.size.toLong()
            putEntry(zip, PREFS_ENTRY, prefsPayload)

            MEDIA_DIRS.forEach { dirName ->
                val dir = File(context.filesDir, dirName).canonicalFile
                if (!dir.isDirectory) return@forEach
                dir.walkTopDown()
                    .filter { it.isFile }
                    .mapNotNull { file ->
                        val canonical = runCatching { file.canonicalFile }.getOrNull()
                            ?: return@mapNotNull null
                        if (!canonical.path.startsWith(dir.path + File.separator)) {
                            return@mapNotNull null
                        }
                        canonical to canonical.relativeTo(dir).invariantSeparatorsPath
                    }
                    .sortedBy { it.second }
                    .forEach { (file, relative) ->
                        val entryName = safeArchiveName("$dirName/$relative")
                        val included = entryName in includedFiles ||
                            includedFiles.any { it.endsWith('/') && entryName.startsWith(it) }
                        if (!included) return@forEach
                        val size = file.length()
                        val entryLimit = if (dirName == "overlay_web") {
                            MAX_WEB_FILE_BYTES
                        } else {
                            MAX_ENTRY_BYTES
                        }
                        require(size in 0..entryLimit) { "managed file is too large" }
                        require(size <= MAX_TOTAL_BYTES - exportedMediaBytes) {
                            "managed files are too large"
                        }
                        exportedEntries++
                        require(exportedEntries <= MAX_ENTRIES) { "too many managed files" }
                        exportedMediaBytes += size
                        zip.putNextEntry(ZipEntry(entryName).apply { time = 0L })
                        file.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
        }
        true
    }.getOrElse {
        Log.w(TAG, "export failed", it)
        false
    }

    private fun portablePreferenceValue(
        context: Context,
        key: String,
        value: Any,
        includedFiles: MutableSet<String>,
    ): Any? = when (key) {
        "brb_image_path" -> portableImagePath(context, value as String, includedFiles)
        "overlay_scene_v1" -> portableScene(context, value as String, includedFiles)
        else -> value
    }

    private fun portableImagePath(
        context: Context,
        path: String,
        includedFiles: MutableSet<String>,
    ): String? = runCatching {
        val root = File(context.filesDir, "overlay_images").canonicalFile
        val file = File(path).canonicalFile
        require(
            file.isFile && file.path.startsWith(root.path + File.separator) &&
                OverlayImageStore.isManagedImageName(file.name),
        )
        val archivePath = safeArchiveName(
            "overlay_images/${file.relativeTo(root).invariantSeparatorsPath}",
        )
        includedFiles += archivePath
        BACKUP_FILE_PREFIX + archivePath
    }.getOrNull()

    private fun portableScene(
        context: Context,
        json: String,
        includedFiles: MutableSet<String>,
    ): String? {
        val scene = OverlayJson.fromJson(json) ?: return null
        val portableItems = scene.items.mapNotNull { item ->
            when (val source = item.source) {
                is OverlaySource.Image -> {
                    val path = portableImagePath(context, source.path, includedFiles)
                        ?: return@mapNotNull null
                    item.copy(source = source.copy(path = path))
                }
                is OverlaySource.WebPage -> {
                    val local = OverlayWebStore.resolveLocal(context, source.url)
                    if (local != null) {
                        val archivePath = safeArchiveName("overlay_web/${local.relativePath}")
                        includedFiles += "overlay_web/${local.relativePath.substringBefore('/')}/"
                        item.copy(source = source.copy(url = BACKUP_FILE_PREFIX + archivePath))
                    } else {
                        // Browser-source URLs often contain access tokens. Keep
                        // the overlay slot, but never put its address in a backup.
                        item.copy(source = source.copy(url = ""))
                    }
                }
                else -> item
            }
        }
        return OverlayJson.toJson(scene.copy(items = portableItems))
    }

    fun import(context: Context, input: InputStream): Boolean {
        val stagingRoot = File(
            context.filesDir,
            IMPORT_STAGING_PREFIX + UUID.randomUUID(),
        )
        return runCatching {
            require(stagingRoot.mkdirs()) { "could not create import staging directory" }
            MEDIA_DIRS.forEach { require(File(stagingRoot, it).mkdirs()) }
            val prefsBytes = extract(input, stagingRoot)
            val importedPrefs = parsePrefs(context, stagingRoot, prefsBytes)
            install(context, stagingRoot, importedPrefs)
            Log.d(TAG, "import complete: prefs=${importedPrefs.size}")
            true
        }.getOrElse {
            Log.w(TAG, "import failed", it)
            false
        }.also {
            runCatching { stagingRoot.deleteRecursively() }
        }
    }

    /** Repair or finish a media swap interrupted by process death. */
    fun recoverInterruptedImport(context: Context) {
        val preferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val committedId = preferences.getString(IMPORT_TRANSACTION_KEY, null)
        context.filesDir.listFiles().orEmpty()
            .filter { it.isDirectory && hasUuidSuffix(it.name, IMPORT_STAGING_PREFIX) }
            .forEach { staging ->
                runCatching { staging.deleteRecursively() }
                    .onFailure { Log.w(TAG, "could not remove stale backup staging", it) }
            }
        val rollbackRoots = context.filesDir.listFiles().orEmpty().filter { candidate ->
            candidate.isDirectory && hasUuidSuffix(candidate.name, ROLLBACK_PREFIX)
        }
        if (committedId == null && rollbackRoots.isEmpty()) return
        rollbackRoots.forEach { rollbackRoot ->
            val id = rollbackRoot.name.removePrefix(ROLLBACK_PREFIX)
            runCatching {
                if (id == committedId) {
                    require(rollbackRoot.deleteRecursively() || !rollbackRoot.exists())
                } else {
                    restoreMediaRollback(context, rollbackRoot)
                }
            }.onFailure { Log.w(TAG, "could not recover an interrupted backup import", it) }
        }
        val matchingRollbackStillExists = context.filesDir.listFiles().orEmpty().any {
            it.isDirectory && it.name == ROLLBACK_PREFIX + committedId
        }
        if (committedId != null && !matchingRollbackStillExists) {
            if (!preferences.edit().remove(IMPORT_TRANSACTION_KEY).commit()) {
                Log.w(TAG, "could not clear a completed import marker")
            }
        }
    }

    private fun hasUuidSuffix(name: String, prefix: String): Boolean =
        name.startsWith(prefix) &&
            runCatching { UUID.fromString(name.removePrefix(prefix)) }.isSuccess

    private fun extract(input: InputStream, stagingRoot: File): ByteArray {
        var prefsBytes: ByteArray? = null
        val budget = SafeArchiveBudget(MAX_ENTRIES, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES)
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = budget.begin(entry)
                when {
                    name == PREFS_ENTRY -> {
                        require(!entry.isDirectory) { "preferences entry is a directory" }
                        val bytes = ByteArrayOutputStream()
                        budget.copy(zip, bytes, MAX_PREFS_BYTES)
                        prefsBytes = bytes.toByteArray()
                    }
                    MEDIA_DIRS.any { name == it || name.startsWith("$it/") } -> {
                        val output = resolveArchiveFile(stagingRoot, name)
                        if (entry.isDirectory) {
                            require(output.mkdirs() || output.isDirectory)
                            budget.discard(zip)
                        } else {
                            require(output.parentFile?.let { it.mkdirs() || it.isDirectory } == true)
                            val entryLimit = if (name.startsWith("overlay_web/")) {
                                MAX_WEB_FILE_BYTES
                            } else {
                                MAX_ENTRY_BYTES
                            }
                            output.outputStream().use { budget.copy(zip, it, entryLimit) }
                        }
                    }
                    else -> throw IllegalArgumentException("backup has an unknown entry")
                }
                zip.closeEntry()
                budget.finish()
                entry = zip.nextEntry
            }
        }
        return requireNotNull(prefsBytes) { "backup has no preferences" }
    }

    private fun parsePrefs(
        context: Context,
        stagingRoot: File,
        bytes: ByteArray,
    ): List<ImportedPref> {
        require(bytes.size <= MAX_PREFS_BYTES) { "preferences entry is too large" }
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val backupVersion = root.get("backupVersion")
        require(backupVersion is Int) { "backup version has the wrong type" }
        require(backupVersion == BACKUP_VERSION || backupVersion == LEGACY_BACKUP_VERSION) {
            "unsupported backup version"
        }
        val entries = root.getJSONArray("prefs")
        require(entries.length() <= MAX_PREFS_COUNT) { "backup has too many preferences" }
        val seen = HashSet<String>()
        val imported = buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val key = entry.getString("k")
                require(seen.add(key)) { "backup has a duplicate preference" }
                if (isCredentialKey(key) || key in TRANSIENT_KEYS) continue
                val expectedType = IMPORTABLE_TYPES[key] ?: continue
                val declaredType = entry.getString("t")
                require(declaredType == expectedType) { "backup preference has the wrong type" }
                val rawValue = entry.get("v")
                val value: Any = when (declaredType) {
                    "bool" -> rawValue.also { require(it is Boolean) }
                    "int" -> rawValue.also { require(it is Int) }
                    "long" -> rawValue.also { require(it is Long) }
                    "float" -> (rawValue as? Number)?.toDouble()?.also {
                        require(
                            it.isFinite() &&
                                it in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble(),
                        )
                    }?.toFloat() ?: throw IllegalArgumentException("invalid float preference")
                    "string" -> (rawValue as? String)?.also {
                        val max = if (key == "overlay_scene_v1") 512 * 1024 else 4 * 1024
                        require(it.length <= max) { "backup preference is too large" }
                    } ?: throw IllegalArgumentException("invalid string preference")
                    else -> throw IllegalArgumentException("unsupported preference type")
                }
                val restoredValue = restoreAndValidateValue(
                    context,
                    stagingRoot,
                    backupVersion,
                    key,
                    value,
                )
                add(ImportedPref(key, declaredType, restoredValue))
            }
        }
        return normalizeImportedQuality(imported)
    }

    private fun restoreAndValidateValue(
        context: Context,
        stagingRoot: File,
        backupVersion: Int,
        key: String,
        value: Any,
    ): Any {
        when (key) {
            "obs_port" -> return UserConfigRules.obsPort(value as Int)
            "stealth_pulse_sec" -> return UserConfigRules.stealthPulseSeconds(value as Int)
            "max_reconnect_min" -> return UserConfigRules.maxReconnectMinutes(value as Int)
            "chat_text_size_sp" -> return UserConfigRules.chatTextSizeSp(value as Int)
            "chat_panel_mode" -> return UserConfigRules.chatPanelMode(value as String)
            "chat_panel_side" -> return UserConfigRules.chatPanelSide(value as String)
            "mic_gain" -> return UserConfigRules.micGain(value as Float)
            "brb_image_path" -> return restoreManagedFile(
                context,
                stagingRoot,
                backupVersion,
                value as String,
                "overlay_images",
            ).absolutePath
            "overlay_scene_v1" -> return restoreScene(
                context,
                stagingRoot,
                backupVersion,
                value as String,
            )
        }
        return value
    }

    /** Collapse the four legacy quality fields into one complete safe preset. */
    private fun normalizeImportedQuality(values: List<ImportedPref>): List<ImportedPref> {
        val qualityKeys = setOf(
            "video_width",
            "video_height",
            "video_fps",
            "video_bitrate_kbps",
        )
        val qualityValues = values.filter { it.key in qualityKeys }.associateBy(ImportedPref::key)
        if (qualityValues.isEmpty()) return values
        val preset = if (qualityValues.keys.containsAll(qualityKeys)) {
            VideoQualityPreset.closest(
                qualityValues.getValue("video_width").value as Int,
                qualityValues.getValue("video_height").value as Int,
                qualityValues.getValue("video_fps").value as Int,
                qualityValues.getValue("video_bitrate_kbps").value as Int,
            )
        } else {
            VideoQualityPreset.default
        }
        return values.filterNot { it.key in qualityKeys } + listOf(
            ImportedPref("video_width", "int", preset.width),
            ImportedPref("video_height", "int", preset.height),
            ImportedPref("video_fps", "int", preset.fps),
            ImportedPref("video_bitrate_kbps", "int", preset.bitrateKbps),
        )
    }

    private fun restoreScene(
        context: Context,
        stagingRoot: File,
        backupVersion: Int,
        json: String,
    ): String {
        val scene = requireNotNull(OverlayJson.fromJson(json)) { "invalid overlay scene" }
        require(scene.id.isNotBlank() && scene.id.length <= 128 && scene.name.length <= 128)
        require(scene.items.size <= 32) { "overlay scene has too many items" }
        val ids = HashSet<String>()
        val restoredItems = scene.items.map { item ->
            require(item.id.isNotBlank() && item.id.length <= 128 && ids.add(item.id))
            require(item.name.length <= 128)
            require(item.xPercent.isFinite() && item.xPercent in 0f..100f)
            require(item.yPercent.isFinite() && item.yPercent in 0f..100f)
            require(item.widthPercent.isFinite() && item.widthPercent in 0f..200f)
            require(item.heightPercent.isFinite() && item.heightPercent in 0f..400f)
            require(item.zOrder in -10_000..10_000)
            val restoredSource = when (val source = item.source) {
                is OverlaySource.Image -> {
                    require(source.aspectRatio.isFinite() && source.aspectRatio in 0.01f..1_000f)
                    source.copy(
                        path = restoreManagedFile(
                            context,
                            stagingRoot,
                            backupVersion,
                            source.path,
                            "overlay_images",
                        ).absolutePath,
                    )
                }
                is OverlaySource.WebPage -> when {
                    source.url.isBlank() -> source.copy(url = "")
                    source.url.startsWith(BACKUP_FILE_PREFIX) ||
                        (backupVersion == LEGACY_BACKUP_VERSION &&
                            OverlayWebStore.isLocalUrl(source.url)) -> {
                        val file = restoreManagedFile(
                            context,
                            stagingRoot,
                            backupVersion,
                            source.url,
                            "overlay_web",
                        )
                        val webRoot = File(context.filesDir, "overlay_web").canonicalFile
                        source.copy(
                            url = OverlayWebStore.localUrlForRelative(
                                file.relativeTo(webRoot).invariantSeparatorsPath,
                            ),
                        )
                    }
                    backupVersion == LEGACY_BACKUP_VERSION &&
                        OverlayWebStore.isSafeHttpsUrl(source.url) -> source
                    else -> source.copy(url = "")
                }
                is OverlaySource.Text -> {
                    require(source.text.length <= 4_096)
                    require(source.fontSizeSp.isFinite() && source.fontSizeSp in 8f..200f)
                    source
                }
                OverlaySource.Watermark -> source
            }
            item.copy(source = restoredSource)
        }
        return OverlayJson.toJson(scene.copy(items = restoredItems))
    }

    private fun restoreManagedFile(
        context: Context,
        stagingRoot: File,
        backupVersion: Int,
        storedValue: String,
        dirName: String,
    ): File {
        val archivePath = when {
            storedValue.startsWith(BACKUP_FILE_PREFIX) ->
                safeArchiveName(storedValue.removePrefix(BACKUP_FILE_PREFIX))
            backupVersion == LEGACY_BACKUP_VERSION && dirName == "overlay_web" -> {
                val relative = OverlayWebStore.managedRelativePath(context, storedValue)
                    ?: legacyRelativePath(storedValue, dirName)
                safeArchiveName("$dirName/$relative")
            }
            backupVersion == LEGACY_BACKUP_VERSION ->
                safeArchiveName("$dirName/${legacyRelativePath(storedValue, dirName)}")
            else -> throw IllegalArgumentException("managed file has an invalid reference")
        }
        require(archivePath.startsWith("$dirName/")) { "managed file is in the wrong directory" }
        if (dirName == "overlay_images") {
            require(OverlayImageStore.isManagedImageName(archivePath.substringAfterLast('/'))) {
                "managed image has an invalid name"
            }
        }
        val stagedFile = resolveArchiveFile(stagingRoot, archivePath)
        require(stagedFile.isFile) { "managed file is missing from the backup" }
        if (dirName == "overlay_images") {
            require(OverlayImageStore.hasSafeImageBounds(stagedFile)) {
                "managed image has unsafe dimensions"
            }
        }
        return resolveArchiveFile(context.filesDir, archivePath)
    }

    private fun legacyRelativePath(storedValue: String, dirName: String): String {
        val normalized = storedValue.removePrefix("file://").replace(File.separatorChar, '/')
        val marker = "/$dirName/"
        val markerIndex = normalized.lastIndexOf(marker)
        require(markerIndex >= 0) { "legacy managed file has an invalid path" }
        return safeArchiveName(normalized.substring(markerIndex + marker.length))
    }

    private fun install(
        context: Context,
        stagingRoot: File,
        importedPrefs: List<ImportedPref>,
    ) {
        val rollbackRoot = File(
            context.filesDir,
            ROLLBACK_PREFIX + UUID.randomUUID(),
        )
        require(rollbackRoot.mkdirs()) { "could not create rollback directory" }
        val transactionId = rollbackRoot.name.removePrefix(ROLLBACK_PREFIX)
        MEDIA_DIRS.forEach { dirName ->
            if (File(context.filesDir, dirName).exists()) {
                require(File(rollbackRoot, HAD_DIRECTORY_PREFIX + dirName).createNewFile()) {
                    "could not write the import journal"
                }
            }
        }
        val movedOld = ArrayList<String>()
        val installedNew = ArrayList<String>()
        try {
            MEDIA_DIRS.forEach { dirName ->
                val current = File(context.filesDir, dirName)
                val rollback = File(rollbackRoot, dirName)
                if (current.exists()) {
                    require(current.renameTo(rollback)) { "could not stage existing media" }
                    movedOld += dirName
                }
                val staged = File(stagingRoot, dirName)
                require(staged.renameTo(current)) { "could not install imported media" }
                installedNew += dirName
            }
            require(applyPrefs(context, importedPrefs, transactionId)) {
                "could not save imported preferences"
            }
        } catch (error: Throwable) {
            val recoveryErrors = ArrayList<Throwable>()
            installedNew.asReversed().forEach { dirName ->
                runCatching {
                    val installed = File(context.filesDir, dirName)
                    require(installed.deleteRecursively() || !installed.exists())
                }.onFailure { recoveryErrors += it }
            }
            movedOld.asReversed().forEach { dirName ->
                runCatching {
                    require(
                        File(rollbackRoot, dirName)
                            .renameTo(File(context.filesDir, dirName)),
                    ) { "could not restore existing media" }
                }.onFailure { recoveryErrors += it }
            }
            if (recoveryErrors.isEmpty()) {
                runCatching { rollbackRoot.deleteRecursively() }
            } else {
                recoveryErrors.forEach(error::addSuppressed)
            }
            throw error
        }
        val rollbackRemoved = runCatching {
            require(rollbackRoot.deleteRecursively() || !rollbackRoot.exists())
            true
        }.onFailure {
            Log.w(TAG, "could not remove the completed import rollback", it)
        }.getOrDefault(false)
        if (rollbackRemoved) {
            val markerCleared = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .remove(IMPORT_TRANSACTION_KEY)
                .commit()
            if (!markerCleared) Log.w(TAG, "could not clear a completed import marker")
        }
    }

    private fun applyPrefs(
        context: Context,
        values: List<ImportedPref>,
        transactionId: String,
    ): Boolean {
        val preferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val snapshotKeys = IMPORTABLE_TYPES.keys + IMPORT_TRANSACTION_KEY
        val previous = snapshotKeys.associateWith { preferences.all[it] }
        val committed = writePreferences(preferences, values, transactionId)
        if (!committed && !restorePreferences(preferences, previous)) {
            Log.w(TAG, "could not restore settings after a failed import commit")
        }
        return committed
    }

    private fun writePreferences(
        preferences: SharedPreferences,
        values: List<ImportedPref>,
        transactionId: String,
    ): Boolean {
        val editor = preferences.edit()
        IMPORTABLE_TYPES.keys.forEach(editor::remove)
        editor.putString(IMPORT_TRANSACTION_KEY, transactionId)
        values.forEach { value ->
            when (value.type) {
                "bool" -> editor.putBoolean(value.key, value.value as Boolean)
                "int" -> editor.putInt(value.key, value.value as Int)
                "long" -> editor.putLong(value.key, value.value as Long)
                "float" -> editor.putFloat(value.key, value.value as Float)
                "string" -> editor.putString(value.key, value.value as String)
            }
        }
        return editor.commit()
    }

    private fun restoreMediaRollback(context: Context, rollbackRoot: File) {
        MEDIA_DIRS.forEach { dirName ->
            val hadDirectory = File(rollbackRoot, HAD_DIRECTORY_PREFIX + dirName).isFile
            val previous = File(rollbackRoot, dirName)
            val current = File(context.filesDir, dirName)
            when {
                previous.exists() -> {
                    require(current.deleteRecursively() || !current.exists())
                    require(previous.renameTo(current)) { "could not restore existing media" }
                }
                !hadDirectory -> require(current.deleteRecursively() || !current.exists())
            }
        }
        require(rollbackRoot.deleteRecursively() || !rollbackRoot.exists())
    }

    private fun restorePreferences(
        preferences: SharedPreferences,
        values: Map<String, Any?>,
    ): Boolean {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        return editor.commit()
    }

    private fun preferenceType(value: Any): String? = when (value) {
        is Boolean -> "bool"
        is Int -> "int"
        is Long -> "long"
        is Float -> "float"
        is String -> "string"
        else -> null
    }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private data class ImportedPref(
        val key: String,
        val type: String,
        val value: Any,
    )
}
