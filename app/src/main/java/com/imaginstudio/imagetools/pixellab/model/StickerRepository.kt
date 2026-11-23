package com.imaginstudio.imagetools.pixellab.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class EmojiCategory(
    val id: String,
    val title: String,
    val emojis: List<String>
)

sealed class RecentStickerItem {
    abstract val key: String

    data class Emoji(val emoji: String) : RecentStickerItem() {
        override val key: String get() = "emoji:$emoji"
    }

    data class CustomFile(val filePath: String) : RecentStickerItem() {
        override val key: String get() = "file:$filePath"
    }
}

object StickerRepository {

    private const val PREFS_STICKERS = "tz_stickers_prefs"
    private const val KEY_RECENT_LIST = "recent_stickers_v1"
    private const val MAX_RECENT = 48
    private const val EMOJI_RENDER_SIZE = 512
    private const val EMOJI_THUMB_SIZE = 128

    private val thumbCache = object : LruCache<String, Bitmap>(160) {}

    val emojiCategories: List<EmojiCategory> = listOf(
        EmojiCategory(
            id = "hand_gestures",
            title = "Hand Gestures",
            emojis = listOf("👍", "🖐️", "🤟", "🙏", "🤝")
        ),
        EmojiCategory(
            id = "cheerful_faces",
            title = "Cheerful Faces",
            emojis = listOf(
                "😁", "😀", "😉", "😎", "😂", "🤣", "😆",
                "😜", "🤪", "😛", "😋", "🤤", "🥲",
                "🙂\u200D↕️", "🙂\u200D↔️"
            )
        ),
        EmojiCategory(
            id = "discomfort_annoyance",
            title = "Discomfort & Annoyance",
            emojis = listOf(
                "😱", "😤", "😮\u200D💨", "🙄", "🤬", "😡",
                "🥶", "🤢", "🤮", "🥵", "😴", "😪",
                "🫨", "😑", "😬", "🫩"
            )
        ),
        EmojiCategory(
            id = "mysterious_scary",
            title = "Mysterious & Scary Entities",
            emojis = listOf(
                "💀", "☠️", "🤡", "😈", "👿", "👻", "👽",
                "🌚", "🌝", "😶\u200D🌫️", "🫥", "🫪",
                "😐", "😶", "🫢", "🤔", "🤫", "🫡", "🤐"
            )
        ),
        EmojiCategory(
            id = "natural_energy",
            title = "Natural Elements & Energy",
            emojis = listOf("⚡", "💥", "🔥", "⭐", "✨", "💢", "💨", "💤", "🕳️")
        ),
        EmojiCategory(
            id = "objects_symbols",
            title = "Objects & Symbols",
            emojis = listOf(
                "🤑", "🫂", "👤", "⚔️", "🗡️", "💣", "🔔",
                "📢", "📌", "🧲", "🧰", "⚙️", "🔗", "📝"
            )
        ),
        EmojiCategory(
            id = "signs_indicators",
            title = "Signs & Indicators",
            emojis = listOf(
                "⚠️", "🔰", "✅", "❎", "❌", "❗",
                "🗨️", "💬", "🗯️", "💭", "♦️", "🛑",
                "⛔", "🌐", "🔷", "🏴\u200D☠️"
            )
        )
    )

    // =========================================================================
    // Emoji PNG Rendering & Caching (High-res transparent PNG for sharp zooming)
    // =========================================================================

    private fun getEmojiCacheDir(context: Context): File =
        File(context.filesDir, "emoji_stickers_v2").apply { mkdirs() }

    private fun hashEmoji(emoji: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(emoji.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun ensureEmojiStickerPng(context: Context, emoji: String): File {
        val cacheFile = File(getEmojiCacheDir(context), "emoji_${hashEmoji(emoji)}.png")
        if (cacheFile.exists() && cacheFile.length() > 128L) {
            return cacheFile
        }
        val bmp = renderEmojiBitmap(emoji, EMOJI_RENDER_SIZE)
        runCatching {
            FileOutputStream(cacheFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        if (!bmp.isRecycled) bmp.recycle()
        return cacheFile
    }

    fun getEmojiThumbnail(context: Context, emoji: String): Bitmap {
        val key = "em_thumb:$emoji"
        thumbCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        val fullFile = ensureEmojiStickerPng(context, emoji)
        val thumb = decodeSampledBitmap(fullFile, EMOJI_THUMB_SIZE)
            ?: renderEmojiBitmap(emoji, EMOJI_THUMB_SIZE)
        thumbCache.put(key, thumb)
        return thumb
    }

    private fun renderEmojiBitmap(emoji: String, targetMaxDim: Int): Bitmap {
        val padCanvasSize = (targetMaxDim * 1.45f).roundToInt().coerceAtLeast(192)
        val raw = Bitmap.createBitmap(padCanvasSize, padCanvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(raw)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textSize = targetMaxDim * 0.86f
        }

        val layoutW = padCanvasSize
        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(emoji, 0, emoji.length, paint, layoutW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(emoji, paint, layoutW, Layout.Alignment.ALIGN_CENTER, 1f, 0f, true)
        }

        val dy = ((padCanvasSize - layout.height) * 0.5f).coerceAtLeast(0f)
        canvas.save()
        canvas.translate(0f, dy)
        layout.draw(canvas)
        canvas.restore()

        val trimmed = trimTransparentBounds(raw, padding = max(6, targetMaxDim / 40))
        if (trimmed !== raw && !raw.isRecycled) {
            raw.recycle()
        }

        // Fit inside targetMaxDim preserving crisp aspect ratio
        val w = trimmed.width.coerceAtLeast(1)
        val h = trimmed.height.coerceAtLeast(1)
        val maxSide = max(w, h)
        if (maxSide == targetMaxDim) return trimmed

        val scale = targetMaxDim.toFloat() / maxSide.toFloat()
        val outW = (w * scale).roundToInt().coerceAtLeast(1)
        val outH = (h * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(trimmed, outW, outH, true)
        if (scaled !== trimmed && !trimmed.isRecycled) {
            trimmed.recycle()
        }
        return scaled
    }

    private fun trimTransparentBounds(src: Bitmap, padding: Int): Bitmap {
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) > 4) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return src
        }
        val left = (minX - padding).coerceAtLeast(0)
        val top = (minY - padding).coerceAtLeast(0)
        val right = (maxX + 1 + padding).coerceAtMost(w)
        val bottom = (maxY + 1 + padding).coerceAtMost(h)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(src, left, top, cropW, cropH)
    }

    // =========================================================================
    // My Stickers (Persistent User-Imported Image Stickers)
    // =========================================================================

    private val supportedExts = setOf("png", "webp", "jpg", "jpeg", "gif", "bmp")

    fun listMyStickers(context: Context): List<File> {
        val dir = AppStorageManager.getMyStickersDir(context)
        return dir.listFiles { f ->
            f.isFile && supportedExts.contains(f.extension.lowercase())
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun getCustomStickerThumbnail(file: File): Bitmap? {
        val key = "file_thumb:${file.absolutePath}:${file.lastModified()}"
        thumbCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        val bmp = decodeSampledBitmap(file, 192) ?: return null
        thumbCache.put(key, bmp)
        return bmp
    }

    fun importStickerUris(context: Context, uris: List<Uri>): List<File> {
        val dir = AppStorageManager.getMyStickersDir(context)
        val imported = ArrayList<File>()
        for (uri in uris) {
            val saved = importSingleStickerUri(context, dir, uri)
            if (saved != null) imported.add(saved)
        }
        return imported
    }

    private fun importSingleStickerUri(context: Context, dir: File, uri: Uri): File? {
        val resolver = context.contentResolver
        val rawName = queryDisplayName(context, uri) ?: "sticker_${System.currentTimeMillis()}"
        val base = AppStorageManager.sanitizeFileName(rawName.substringBeforeLast('.'), "sticker")

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxSide = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide / sample > 2048) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        }.getOrNull() ?: return null

        val oriented = applyExifOrientation(decoded, orientation)
        if (oriented !== decoded && !decoded.isRecycled) decoded.recycle()

        var outFile = File(dir, "$base.png")
        var idx = 1
        while (outFile.exists()) {
            outFile = File(dir, "${base}_${idx}.png")
            idx++
        }

        val ok = runCatching {
            FileOutputStream(outFile).use { out ->
                oriented.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        }.getOrDefault(false)

        if (!oriented.isRecycled) oriented.recycle()
        return if (ok) outFile else null
    }

    fun deleteCustomSticker(context: Context, file: File) {
        val path = file.absolutePath
        runCatching { file.delete() }
        // Also strip deleted file from Recent stickers list
        val current = getRecentStickers(context)
        val filtered = current.filterNot {
            it is RecentStickerItem.CustomFile && it.filePath == path
        }
        if (filtered.size != current.size) {
            saveRecentStickers(context, filtered)
        }
    }

    private fun applyExifOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.preScale(-1f, 1f)
                m.postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.preScale(-1f, 1f)
                m.postRotate(90f)
            }
            else -> return bmp
        }
        return runCatching {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }.getOrDefault(bmp)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            val longest = max(w, h)
            while (longest / (sample * 2) >= maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
    }

    // =========================================================================
    // Recent Stickers Persistence
    // =========================================================================

    fun getRecentStickers(context: Context): List<RecentStickerItem> {
        val raw = context.getSharedPreferences(PREFS_STICKERS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_LIST, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val list = ArrayList<RecentStickerItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val type = obj.optString("type")
            val value = obj.optString("value")
            if (value.isBlank()) continue
            when (type) {
                "emoji" -> list.add(RecentStickerItem.Emoji(value))
                "file" -> {
                    val f = File(value)
                    if (f.exists() && f.isFile) {
                        list.add(RecentStickerItem.CustomFile(value))
                    }
                }
            }
        }
        return list
    }

    fun recordRecentEmoji(context: Context, emoji: String) {
        recordRecentItem(context, RecentStickerItem.Emoji(emoji))
    }

    fun recordRecentCustomFile(context: Context, file: File) {
        recordRecentItem(context, RecentStickerItem.CustomFile(file.absolutePath))
    }

    private fun recordRecentItem(context: Context, item: RecentStickerItem) {
        val existing = getRecentStickers(context).toMutableList()
        existing.removeAll { it.key == item.key }
        existing.add(0, item)
        if (existing.size > MAX_RECENT) {
            saveRecentStickers(context, existing.take(MAX_RECENT))
        } else {
            saveRecentStickers(context, existing)
        }
    }

    private fun saveRecentStickers(context: Context, items: List<RecentStickerItem>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            when (item) {
                is RecentStickerItem.Emoji -> {
                    obj.put("type", "emoji")
                    obj.put("value", item.emoji)
                }
                is RecentStickerItem.CustomFile -> {
                    obj.put("type", "file")
                    obj.put("value", item.filePath)
                }
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_STICKERS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_LIST, arr.toString())
            .apply()
    }
}
