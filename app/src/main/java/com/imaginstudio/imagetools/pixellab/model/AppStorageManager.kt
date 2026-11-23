package com.imaginstudio.imagetools.pixellab.model

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.imaginstudio.imagetools.pixellab.engine.PixelLabRenderer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages TZ Photo Edit's dedicated local storage hierarchy:
 *   TZ Photo Edit/
 *     My Projects/      (.tz files)
 *     My Text Styles/   (.json files)
 *     Colors/           (.json files)
 *     Gradients/        (.json files)
 *     Saved Images/     (.png / .jpg exports)
 *     Auto Save/        (.tz history snapshots)
 *
 * Also manages an in-memory thumbnail LruCache to prevent template strip flickering.
 */
object AppStorageManager {

    const val ROOT_FOLDER_NAME = "TZ Photo Edit"
    const val DIR_MY_PROJECTS = "My Projects"
    const val DIR_MY_TEXT_STYLES = "My Text Styles"
    const val DIR_COLORS = "Colors"
    const val DIR_GRADIENTS = "Gradients"
    const val DIR_SAVED_IMAGES = "Saved Images"
    const val DIR_AUTO_SAVE = "Auto Save"
    const val DIR_MY_STICKERS = "My Stickers"

    const val EXT_PROJECT = "tz"
    const val EXT_LEGACY_PROJECT = "plab"
    private const val MAX_AUTO_SAVE_FILES = 25
    private const val PREFS_STORAGE = "tz_storage_prefs"
    private const val KEY_MIGRATED_PROJECTS = "migrated_legacy_projects_v1"

    enum class JsonFolderType(val folderName: String, val label: String) {
        COLORS(DIR_COLORS, "Colors"),
        GRADIENTS(DIR_GRADIENTS, "Gradients"),
        TEXT_STYLES(DIR_MY_TEXT_STYLES, "My Text Styles")
    }

    data class AutoSaveEntry(
        val file: File,
        val projectName: String,
        val width: Int,
        val height: Int,
        val layerCount: Int,
        val timestamp: Long
    )

    /**
     * Activity-level bridge for SAF Import/Export JSON pickers so nested color/gradient/swatch popups
     * can let users browse any system folder in addition to the dedicated TZ Photo Edit folders.
     */
    object JsonBridge {
        var requestSystemImportJson: ((onLoaded: (String) -> Unit) -> Unit)? = null
        var requestSystemExportJson: ((suggestedFileName: String, content: String) -> Unit)? = null
    }

    private val thumbCache = object : LruCache<String, Bitmap>(48) {}
    private val presetThumbCache = object : LruCache<String, Bitmap>(24) {}

    @Volatile
    private var lastAutoSaveHash: Int = 0

    @Volatile
    private var lastAutoSaveHistoryTimeMs: Long = 0L

    fun hasDirectRootStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun buildManageStorageIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            }.getOrElse {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }
    }

    fun getRootDir(context: Context): File {
        val extRoot = runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        }.getOrNull()

        if (extRoot != null && isDirWritable(extRoot)) {
            val publicRoot = File(extRoot, ROOT_FOLDER_NAME)
            if (publicRoot.exists() || publicRoot.mkdirs()) {
                if (isDirWritable(publicRoot)) {
                    return publicRoot
                }
            }
        }

        val appExt = context.getExternalFilesDir(null)
        if (appExt != null) {
            val appExtRoot = File(appExt, ROOT_FOLDER_NAME)
            if (appExtRoot.exists() || appExtRoot.mkdirs()) {
                return appExtRoot
            }
        }

        return File(context.filesDir, ROOT_FOLDER_NAME).apply { mkdirs() }
    }

    private fun isDirWritable(dir: File): Boolean {
        if (!dir.exists() && !dir.mkdirs()) return false
        if (!dir.canWrite()) return false
        return runCatching {
            val probe = File(dir, ".tz_probe_${System.nanoTime()}")
            if (probe.createNewFile()) {
                probe.delete()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun getDisplayRootPath(context: Context): String {
        val root = getRootDir(context)
        val ext = runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        }.getOrNull()
        return if (ext != null && root.absolutePath.startsWith(ext.absolutePath)) {
            val rel = root.absolutePath.removePrefix(ext.absolutePath).trimStart('/')
            "Internal Storage/$rel"
        } else {
            root.absolutePath
        }
    }

    fun getMyProjectsDir(context: Context): File =
        File(getRootDir(context), DIR_MY_PROJECTS).apply { mkdirs() }

    fun getAutoSaveDir(context: Context): File =
        File(getRootDir(context), DIR_AUTO_SAVE).apply { mkdirs() }

    fun getSavedImagesDir(context: Context): File =
        File(getRootDir(context), DIR_SAVED_IMAGES).apply { mkdirs() }

    fun getMyStickersDir(context: Context): File =
        File(getRootDir(context), DIR_MY_STICKERS).apply { mkdirs() }

    fun getJsonFolderDir(context: Context, type: JsonFolderType): File =
        File(getRootDir(context), type.folderName).apply { mkdirs() }

    fun getLatestAutosaveFile(context: Context): File {
        val tz = File(context.filesDir, "autosave.tz")
        if (tz.exists()) return tz
        val legacy = File(context.filesDir, "autosave.plab")
        return if (legacy.exists()) legacy else tz
    }

    fun ensureAllFolders(context: Context) {
        runCatching {
            getRootDir(context)
            getMyProjectsDir(context)
            getJsonFolderDir(context, JsonFolderType.TEXT_STYLES)
            getJsonFolderDir(context, JsonFolderType.COLORS)
            getJsonFolderDir(context, JsonFolderType.GRADIENTS)
            getSavedImagesDir(context)
            getAutoSaveDir(context)
            getMyStickersDir(context)
        }
    }

    fun migrateLegacyFilesIfNeeded(context: Context) {
        ensureAllFolders(context)
        val legacyProjectsDir = File(context.filesDir, "projects")
        val targetProjectsDir = getMyProjectsDir(context)
        if (legacyProjectsDir.exists()) {
            val legacyFiles = legacyProjectsDir.listFiles { f ->
                f.isFile && (f.extension.equals(EXT_LEGACY_PROJECT, true) || f.extension.equals(EXT_PROJECT, true))
            }.orEmpty()
            for (src in legacyFiles) {
                val dest = File(targetProjectsDir, "${src.nameWithoutExtension}.$EXT_PROJECT")
                if (!dest.exists()) {
                    runCatching {
                        src.copyTo(dest, overwrite = false)
                        dest.setLastModified(src.lastModified())
                    }
                }
                val legacyThumb = File(legacyProjectsDir, "${src.nameWithoutExtension}.png")
                if (legacyThumb.exists()) {
                    val thumbDest = getThumbnailCacheFile(context, dest)
                    if (!thumbDest.exists()) {
                        runCatching { legacyThumb.copyTo(thumbDest, overwrite = false) }
                    }
                }
            }
        }
        // Also migrate any old app-internal root projects if user later granted root storage permission
        val appExtRoot = context.getExternalFilesDir(null)?.let { File(it, ROOT_FOLDER_NAME) }
        val activeRoot = getRootDir(context)
        if (appExtRoot != null && appExtRoot.exists() && appExtRoot.absolutePath != activeRoot.absolutePath) {
            copyFolderContentsIfMissing(File(appExtRoot, DIR_MY_PROJECTS), getMyProjectsDir(context))
            copyFolderContentsIfMissing(File(appExtRoot, DIR_AUTO_SAVE), getAutoSaveDir(context))
            copyFolderContentsIfMissing(File(appExtRoot, DIR_COLORS), getJsonFolderDir(context, JsonFolderType.COLORS))
            copyFolderContentsIfMissing(File(appExtRoot, DIR_GRADIENTS), getJsonFolderDir(context, JsonFolderType.GRADIENTS))
            copyFolderContentsIfMissing(File(appExtRoot, DIR_MY_TEXT_STYLES), getJsonFolderDir(context, JsonFolderType.TEXT_STYLES))
        }
        context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MIGRATED_PROJECTS, true).apply()
    }

    private fun copyFolderContentsIfMissing(srcDir: File, dstDir: File) {
        if (!srcDir.exists() || !srcDir.isDirectory) return
        dstDir.mkdirs()
        srcDir.listFiles()?.forEach { src ->
            if (src.isFile) {
                val dst = File(dstDir, src.name)
                if (!dst.exists()) {
                    runCatching {
                        src.copyTo(dst, overwrite = false)
                        dst.setLastModified(src.lastModified())
                    }
                }
            }
        }
    }

    fun sanitizeFileName(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
        return cleaned.ifBlank { fallback }
    }

    // =========================================================================
    // My Projects (.tz) + Duplicate Name Resolution + Import
    // =========================================================================

    fun listProjectFiles(context: Context): List<File> {
        ensureAllFolders(context)
        val dir = getMyProjectsDir(context)
        val rawFiles = dir.listFiles { f ->
            f.isFile && (f.extension.equals(EXT_PROJECT, true) || f.extension.equals(EXT_LEGACY_PROJECT, true))
        }.orEmpty()
        // If both foo.tz and foo.plab exist, prefer .tz
        val byBase = LinkedHashMap<String, File>()
        for (f in rawFiles.sortedByDescending { if (it.extension.equals(EXT_PROJECT, true)) 1 else 0 }) {
            val key = f.nameWithoutExtension.lowercase(Locale.ROOT)
            if (!byBase.containsKey(key)) {
                byBase[key] = f
            }
        }
        return byBase.values.sortedByDescending { it.lastModified() }
    }

    fun findExistingProjectFile(context: Context, projectName: String): File? {
        val safeBase = sanitizeFileName(projectName, "")
        if (safeBase.isEmpty()) return null
        val files = listProjectFiles(context)
        val exactMatch = files.firstOrNull {
            it.nameWithoutExtension.equals(safeBase, ignoreCase = true) ||
                it.nameWithoutExtension.equals(projectName.trim(), ignoreCase = true)
        }
        if (exactMatch != null) return exactMatch
        return files.firstOrNull { f ->
            runCatching {
                val pName = JSONObject(f.readText()).optString("name", "")
                pName.equals(projectName.trim(), ignoreCase = true)
            }.getOrDefault(false)
        }
    }

    fun generateUniqueCopyName(context: Context, projectName: String): String {
        val trimmed = projectName.trim().ifBlank { "Untitled Design" }
        val existingNames = listProjectFiles(context).mapTo(HashSet()) {
            it.nameWithoutExtension.lowercase(Locale.ROOT)
        }
        val existingJsonNames = listProjectFiles(context).mapNotNullTo(HashSet()) { f ->
            runCatching { JSONObject(f.readText()).optString("name", "").trim().lowercase(Locale.ROOT) }.getOrNull()
        }

        fun isTaken(candidate: String): Boolean {
            val safe = sanitizeFileName(candidate, candidate).lowercase(Locale.ROOT)
            val low = candidate.lowercase(Locale.ROOT)
            return existingNames.contains(safe) || existingNames.contains(low) || existingJsonNames.contains(low)
        }

        val firstCopy = "$trimmed copy"
        if (!isTaken(firstCopy)) return firstCopy
        var idx = 2
        while (idx < 999) {
            val cand = "$trimmed copy $idx"
            if (!isTaken(cand)) return cand
            idx++
        }
        return "${trimmed}_${System.currentTimeMillis()}"
    }

    fun saveProjectToMyProjects(
        context: Context,
        project: ProjectState,
        targetName: String = project.name,
        existingFileToOverwrite: File? = null
    ): File {
        ensureAllFolders(context)
        val finalName = targetName.trim().ifBlank { "Untitled Design" }
        val updated = project.copy(name = finalName, updatedAt = System.currentTimeMillis())
        val dir = getMyProjectsDir(context)
        val safeBase = sanitizeFileName(finalName, "project_${System.currentTimeMillis()}")
        val outFile = if (existingFileToOverwrite != null) {
            if (existingFileToOverwrite.extension.equals(EXT_PROJECT, true)) {
                existingFileToOverwrite
            } else {
                File(dir, "${existingFileToOverwrite.nameWithoutExtension}.$EXT_PROJECT")
            }
        } else {
            File(dir, "$safeBase.$EXT_PROJECT")
        }
        outFile.writeText(updated.toJson().toString(), Charsets.UTF_8)
        if (existingFileToOverwrite != null && existingFileToOverwrite.absolutePath != outFile.absolutePath) {
            runCatching { existingFileToOverwrite.delete() }
        }
        runCatching {
            val thumbBmp = PixelLabRenderer.renderProjectPreviewBitmap(context, updated, 220)
            saveThumbnailForProject(context, outFile, thumbBmp)
        }
        return outFile
    }

    fun deleteProjectFile(context: Context, file: File) {
        runCatching {
            val legacySidecar = File(file.parentFile, "${file.nameWithoutExtension}.png")
            if (legacySidecar.exists()) legacySidecar.delete()
            getThumbnailCacheFile(context, file).delete()
            thumbCache.remove(thumbCacheKey(file))
            file.delete()
        }
    }

    fun importProjectFromRawText(context: Context, rawText: String, fallbackName: String? = null): ProjectState {
        val json = JSONObject(rawText)
        val parsed = ProjectState.fromJson(json)
        val candidateName = parsed.name.takeIf { it.isNotBlank() && it != "Untitled Design" }
            ?: fallbackName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "Imported Project"
        val uniqueName = if (findExistingProjectFile(context, candidateName) != null) {
            generateUniqueCopyName(context, candidateName)
        } else {
            candidateName
        }
        val toSave = parsed.copy(name = uniqueName, updatedAt = System.currentTimeMillis())
        saveProjectToMyProjects(context, toSave, uniqueName)
        return toSave
    }

    // =========================================================================
    // Thumbnail Caching (Anti-Flicker)
    // =========================================================================

    private fun thumbCacheKey(file: File): String = "${file.absolutePath}:${file.lastModified()}"

    private fun getThumbnailCacheFile(context: Context, projectFile: File): File {
        val dir = File(context.filesDir, "project_thumbs").apply { mkdirs() }
        val hash = projectFile.absolutePath.hashCode().toUInt().toString(16)
        return File(dir, "${sanitizeFileName(projectFile.nameWithoutExtension, "p")}_${hash}.png")
    }

    fun getCachedThumbnail(file: File): Bitmap? {
        val bmp = thumbCache.get(thumbCacheKey(file)) ?: return null
        return if (!bmp.isRecycled) bmp else null
    }

    fun getCachedPresetThumb(uri: String): Bitmap? {
        val bmp = presetThumbCache.get(uri) ?: return null
        return if (!bmp.isRecycled) bmp else null
    }

    fun putCachedPresetThumb(uri: String, bmp: Bitmap) {
        if (!bmp.isRecycled) {
            presetThumbCache.put(uri, bmp)
        }
    }

    fun saveThumbnailForProject(context: Context, projectFile: File, bmp: Bitmap) {
        if (bmp.isRecycled) return
        val thumbFile = getThumbnailCacheFile(context, projectFile)
        runCatching {
            FileOutputStream(thumbFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            thumbFile.setLastModified(projectFile.lastModified())
        }
        thumbCache.put(thumbCacheKey(projectFile), bmp)
    }

    fun loadOrRenderThumbnail(context: Context, projectFile: File): Bitmap? {
        getCachedThumbnail(projectFile)?.let { return it }

        val thumbFile = getThumbnailCacheFile(context, projectFile)
        if (thumbFile.exists() && thumbFile.lastModified() >= projectFile.lastModified()) {
            BitmapFactory.decodeFile(thumbFile.absolutePath)?.let { decoded ->
                thumbCache.put(thumbCacheKey(projectFile), decoded)
                return decoded
            }
        }

        // Legacy sidecar support
        val legacyThumb = File(projectFile.parentFile, "${projectFile.nameWithoutExtension}.png")
        if (legacyThumb.exists() && legacyThumb.lastModified() >= projectFile.lastModified()) {
            BitmapFactory.decodeFile(legacyThumb.absolutePath)?.let { decoded ->
                thumbCache.put(thumbCacheKey(projectFile), decoded)
                return decoded
            }
        }

        return runCatching {
            val json = JSONObject(projectFile.readText())
            val proj = ProjectState.fromJson(json)
            val bmp = PixelLabRenderer.renderProjectPreviewBitmap(context, proj, 220)
            saveThumbnailForProject(context, projectFile, bmp)
            bmp
        }.getOrNull()
    }

    // =========================================================================
    // Auto Save & Auto Save History (.tz snapshots)
    // =========================================================================

    @Synchronized
    fun writeAutoSaveSnapshot(context: Context, snapshotJson: String, forceHistory: Boolean = false) {
        runCatching {
            File(context.filesDir, "autosave.tz").writeText(snapshotJson, Charsets.UTF_8)
        }
        val hash = snapshotJson.hashCode()
        val now = System.currentTimeMillis()
        val elapsed = now - lastAutoSaveHistoryTimeMs
        val dir = getAutoSaveDir(context)
        val hasAnyHistory = dir.listFiles { f -> f.isFile && f.extension.equals(EXT_PROJECT, true) }?.isNotEmpty() == true
        val shouldRecordHistory = forceHistory || !hasAnyHistory || (hash != lastAutoSaveHash && elapsed >= 4500L)
        if (shouldRecordHistory && hash != lastAutoSaveHash) {
            lastAutoSaveHash = hash
            lastAutoSaveHistoryTimeMs = now
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
                val outFile = File(dir, "AutoSave_${stamp}.$EXT_PROJECT")
                outFile.writeText(snapshotJson, Charsets.UTF_8)
                pruneAutoSaveHistory(dir)
            }
        }
    }

    private fun pruneAutoSaveHistory(dir: File) {
        val files = dir.listFiles { f ->
            f.isFile && (f.extension.equals(EXT_PROJECT, true) || f.extension.equals(EXT_LEGACY_PROJECT, true))
        }?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_AUTO_SAVE_FILES) {
            files.drop(MAX_AUTO_SAVE_FILES).forEach { runCatching { it.delete() } }
        }
    }

    fun listAutoSaveEntries(context: Context): List<AutoSaveEntry> {
        ensureAllFolders(context)
        val dir = getAutoSaveDir(context)
        val files = dir.listFiles { f ->
            f.isFile && (f.extension.equals(EXT_PROJECT, true) || f.extension.equals(EXT_LEGACY_PROJECT, true))
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        return files.map { f ->
            runCatching {
                val obj = JSONObject(f.readText())
                AutoSaveEntry(
                    file = f,
                    projectName = obj.optString("name").ifBlank { f.nameWithoutExtension },
                    width = obj.optInt("width", 1280),
                    height = obj.optInt("height", 1280),
                    layerCount = obj.optJSONArray("layers")?.length() ?: 0,
                    timestamp = f.lastModified()
                )
            }.getOrElse {
                AutoSaveEntry(f, f.nameWithoutExtension, 1280, 1280, 0, f.lastModified())
            }
        }
    }

    fun clearAutoSaveHistory(context: Context) {
        getAutoSaveDir(context).listFiles()?.forEach { runCatching { it.delete() } }
        lastAutoSaveHash = 0
    }

    // =========================================================================
    // JSON Files (Colors, Gradients, My Text Styles) & Saved Images
    // =========================================================================

    fun listJsonFiles(context: Context, type: JsonFolderType): List<File> {
        ensureAllFolders(context)
        val dir = getJsonFolderDir(context, type)
        return dir.listFiles { f -> f.isFile && f.extension.equals("json", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun saveJsonToFolder(
        context: Context,
        type: JsonFolderType,
        rawFileName: String,
        jsonContent: String
    ): File? {
        ensureAllFolders(context)
        val safeBase = sanitizeFileName(rawFileName.removeSuffix(".json"), "${type.name.lowercase(Locale.ROOT)}_${System.currentTimeMillis()}")
        val fileName = "$safeBase.json"
        val dir = getJsonFolderDir(context, type)
        val outFile = File(dir, fileName)
        val directOk = runCatching {
            outFile.writeText(jsonContent, Charsets.UTF_8)
            true
        }.getOrDefault(false)

        // If external root wasn't directly writable, also publish to Documents/TZ Photo Edit/<Folder>
        if (!hasDirectRootStorageAccess(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$ROOT_FOLDER_NAME/${type.folderName}")
                }
                val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(jsonContent.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
        return if (directOk) outFile else null
    }

    fun saveExportedBitmap(context: Context, bmp: Bitmap, isPng: Boolean): Pair<Boolean, String> {
        ensureAllFolders(context)
        val ext = if (isPng) "png" else "jpg"
        val mime = if (isPng) "image/png" else "image/jpeg"
        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "TZ_$stamp.$ext"

        // 1. Write to TZ Photo Edit/Saved Images/
        val savedImagesDir = getSavedImagesDir(context)
        val localSaved = runCatching {
            val outFile = File(savedImagesDir, fileName)
            FileOutputStream(outFile).use { out -> bmp.compress(format, 95, out) }
            true
        }.getOrDefault(false)

        // 2. Register in MediaStore / Gallery under Pictures/TZ Photo Edit
        val gallerySaved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ROOT_FOLDER_NAME/$DIR_SAVED_IMAGES")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(format, 95, out)
                } ?: false
                if (!ok) runCatching { context.contentResolver.delete(uri, null, null) }
                ok
            } else {
                @Suppress("DEPRECATION")
                val pics = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "$ROOT_FOLDER_NAME/$DIR_SAVED_IMAGES")
                if (pics.exists() || pics.mkdirs()) {
                    val f = File(pics, fileName)
                    FileOutputStream(f).use { out -> bmp.compress(format, 95, out) }
                    true
                } else false
            }
        }.getOrDefault(false)

        val ok = localSaved || gallerySaved
        val msg = if (ok) "Saved to TZ Photo Edit/Saved Images" else "Failed to save image"
        return ok to msg
    }
}
