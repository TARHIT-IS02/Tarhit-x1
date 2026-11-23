package com.imaginstudio.imagetools.pixellab.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.imaginstudio.imagetools.pixellab.model.EmojiCategory
import com.imaginstudio.imagetools.pixellab.model.RecentStickerItem
import com.imaginstudio.imagetools.pixellab.model.StickerRepository
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object StickerDialog {

    private val bgExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastActiveTab = 2 // Default to Emoji Stickers on first open

    fun show(
        context: Context,
        onRequestAddStickers: (onAdded: () -> Unit) -> Unit,
        onStickerSelected: (file: File, label: String) -> Unit
    ) {
        val dark = ThemeManager.isDark(context)
        fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()

        var activeTab = lastActiveTab
        var selectedEmojiCatId = "all"

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Top Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        val titleTv = TextView(context).apply {
            text = "Stickers"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.textPrimary(dark))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(ThemeManager.textSecondary(dark))
            background = ThemeManager.circleButtonDrawable(context, dark, active = false)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }
        header.addView(titleTv)
        header.addView(closeBtn)
        root.addView(header)

        // 3 Unified Tabs: Recent | My Stickers | Emoji Stickers
        val tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(ThemeManager.panelSecondaryBg(dark))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), ThemeManager.borderColor(dark))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { bottomMargin = dp(10) }
        }

        val tabTitles = listOf("Recent", "My Stickers", "Emoji Stickers")
        val tabButtons = ArrayList<TextView>(3)

        // Category pills container (only visible in Emoji Stickers tab)
        val catScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val catRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        catScroll.addView(catRow)

        // Add sticker top action bar (only visible in My Stickers tab)
        val myStickersActionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val btnAddYourSticker = TextView(context).apply {
            text = "+ Add Your Sticker"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(ThemeManager.accentPrimary(dark))
                cornerRadius = dp(9).toFloat()
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        }
        myStickersActionBar.addView(btnAddYourSticker)

        // Scrollable content area with fixed height so tabs never jump
        val maxContentHeight = (context.resources.displayMetrics.heightPixels * 0.56f).roundToInt()
            .coerceIn(dp(260), dp(440))
        val contentScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxContentHeight
            )
        }
        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        contentScroll.addView(contentContainer)

        root.addView(tabsRow)
        root.addView(myStickersActionBar)
        root.addView(catScroll)
        root.addView(contentScroll)

        val dlg = AlertDialog.Builder(context)
            .setView(root)
            .create()
        dlg.window?.setBackgroundDrawable(ThemeManager.dialogCardDrawable(context, dark, 14))
        closeBtn.setOnClickListener { dlg.dismiss() }

        val checkerDrawable = createSubtleCheckerDrawable(context, dark)

        fun styleTabs() {
            tabButtons.forEachIndexed { idx, tv ->
                val active = idx == activeTab
                tv.setTextColor(if (active) Color.WHITE else ThemeManager.textSecondary(dark))
                tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tv.background = if (active) {
                    GradientDrawable().apply {
                        setColor(ThemeManager.accentPrimary(dark))
                        cornerRadius = dp(7).toFloat()
                    }
                } else {
                    null
                }
            }
        }

        fun pickEmoji(emoji: String) {
            StickerRepository.recordRecentEmoji(context, emoji)
            val file = StickerRepository.ensureEmojiStickerPng(context, emoji)
            dlg.dismiss()
            onStickerSelected(file, "Emoji $emoji")
        }

        fun pickCustomFile(file: File) {
            if (!file.exists()) {
                Toast.makeText(context, "Sticker file not found", Toast.LENGTH_SHORT).show()
                return
            }
            StickerRepository.recordRecentCustomFile(context, file)
            dlg.dismiss()
            onStickerSelected(file, file.nameWithoutExtension.ifBlank { "Sticker" })
        }

        fun buildStickerTileFrame(): FrameLayout {
            return FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(ThemeManager.cardElevatedBg(dark))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), ThemeManager.borderColor(dark))
                }
                clipToOutline = true
            }
        }

        fun buildEmptyMessage(msg: String): TextView {
            return TextView(context).apply {
                text = msg
                textSize = 13.5f
                gravity = Gravity.CENTER
                setTextColor(ThemeManager.textSecondary(dark))
                setPadding(dp(16), dp(48), dp(16), dp(48))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        }

        fun populateGridRows(
            target: LinearLayout,
            columns: Int,
            itemCount: Int,
            tileHeightDp: Int,
            bindItem: (index: Int, cell: FrameLayout) -> Unit
        ) {
            var idx = 0
            while (idx < itemCount) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                for (c in 0 until columns) {
                    if (idx < itemCount) {
                        val cell = buildStickerTileFrame().apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(tileHeightDp), 1f).apply {
                                if (c < columns - 1) marginEnd = dp(8)
                            }
                        }
                        bindItem(idx, cell)
                        row.addView(cell)
                        idx++
                    } else {
                        val spacer = View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(tileHeightDp), 1f).apply {
                                if (c < columns - 1) marginEnd = dp(8)
                            }
                        }
                        row.addView(spacer)
                    }
                }
                target.addView(row)
            }
        }

        fun bindEmojiCell(cell: FrameLayout, emoji: String) {
            cell.tag = emoji
            val img = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val fallbackTv = TextView(context).apply {
                text = emoji
                textSize = 26f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            cell.addView(fallbackTv)
            cell.addView(img)
            cell.setOnClickListener { pickEmoji(emoji) }

            bgExecutor.execute {
                val bmp = runCatching { StickerRepository.getEmojiThumbnail(context, emoji) }.getOrNull()
                if (bmp != null) {
                    mainHandler.post {
                        if (cell.tag == emoji && !bmp.isRecycled && dlg.isShowing) {
                            img.setImageBitmap(bmp)
                            fallbackTv.visibility = View.GONE
                        }
                    }
                }
            }
        }

        fun bindCustomFileCell(
            cell: FrameLayout,
            file: File,
            showDelete: Boolean,
            onDeleted: (() -> Unit)?
        ) {
            val tagKey = "${file.absolutePath}:${file.lastModified()}"
            cell.tag = tagKey

            val checkerView = View(context).apply {
                background = checkerDrawable
                alpha = if (dark) 0.35f else 0.65f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val img = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            cell.addView(checkerView)
            cell.addView(img)
            cell.setOnClickListener { pickCustomFile(file) }

            if (showDelete) {
                val delBtn = TextView(context).apply {
                    text = "✕"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(190, 220, 38, 38))
                    }
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = dp(4)
                        marginEnd = dp(4)
                    }
                    setOnClickListener {
                        StickerRepository.deleteCustomSticker(context, file)
                        onDeleted?.invoke()
                    }
                }
                cell.addView(delBtn)
            }

            bgExecutor.execute {
                val bmp = runCatching { StickerRepository.getCustomStickerThumbnail(file) }.getOrNull()
                if (bmp != null) {
                    mainHandler.post {
                        if (cell.tag == tagKey && !bmp.isRecycled && dlg.isShowing) {
                            img.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }

        lateinit var renderContent: () -> Unit

        fun renderCategoryChips() {
            catRow.removeAllViews()
            val items = mutableListOf("all" to "All")
            StickerRepository.emojiCategories.forEach { items.add(it.id to it.title) }
            for ((catId, label) in items) {
                val active = catId == selectedEmojiCatId
                val chip = TextView(context).apply {
                    text = label
                    textSize = 12f
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (active) ThemeManager.accentPrimary(dark) else ThemeManager.textSecondary(dark))
                    background = ThemeManager.pillDrawable(context, dark, active = active, radiusDp = 8)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
                    setOnClickListener {
                        selectedEmojiCatId = catId
                        renderCategoryChips()
                        renderContent()
                    }
                }
                catRow.addView(chip)
            }
        }

        renderContent = {
            styleTabs()
            contentContainer.removeAllViews()
            myStickersActionBar.visibility = if (activeTab == 1) View.VISIBLE else View.GONE
            catScroll.visibility = if (activeTab == 2) View.VISIBLE else View.GONE

            when (activeTab) {
                0 -> {
                    val recents = StickerRepository.getRecentStickers(context)
                    if (recents.isEmpty()) {
                        contentContainer.addView(
                            buildEmptyMessage("No recent stickers yet.\nPick any Emoji or My Sticker to see it here.")
                        )
                    } else {
                        populateGridRows(contentContainer, 4, recents.size, 74) { idx, cell ->
                            when (val item = recents[idx]) {
                                is RecentStickerItem.Emoji -> bindEmojiCell(cell, item.emoji)
                                is RecentStickerItem.CustomFile -> bindCustomFileCell(
                                    cell = cell,
                                    file = File(item.filePath),
                                    showDelete = false,
                                    onDeleted = null
                                )
                            }
                        }
                    }
                }

                1 -> {
                    val files = StickerRepository.listMyStickers(context)
                    if (files.isEmpty()) {
                        contentContainer.addView(
                            buildEmptyMessage("No custom stickers yet.\nTap \"+ Add Your Sticker\" to import PNG or image stickers.")
                        )
                    } else {
                        populateGridRows(contentContainer, 4, files.size, 78) { idx, cell ->
                            bindCustomFileCell(
                                cell = cell,
                                file = files[idx],
                                showDelete = true,
                                onDeleted = { renderContent() }
                            )
                        }
                    }
                }

                else -> {
                    renderCategoryChips()
                    val categoriesToShow: List<EmojiCategory> = if (selectedEmojiCatId == "all") {
                        StickerRepository.emojiCategories
                    } else {
                        StickerRepository.emojiCategories.filter { it.id == selectedEmojiCatId }
                    }
                    for (cat in categoriesToShow) {
                        val headerTv = TextView(context).apply {
                            text = cat.title
                            textSize = 13f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(ThemeManager.textSecondary(dark))
                            setPadding(dp(2), dp(6), dp(2), dp(6))
                        }
                        contentContainer.addView(headerTv)
                        populateGridRows(contentContainer, 5, cat.emojis.size, 60) { idx, cell ->
                            bindEmojiCell(cell, cat.emojis[idx])
                        }
                    }
                }
            }
            contentScroll.scrollTo(0, 0)
        }

        tabTitles.forEachIndexed { idx, label ->
            val btn = TextView(context).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    activeTab = idx
                    lastActiveTab = idx
                    renderContent()
                }
            }
            tabButtons.add(btn)
            tabsRow.addView(btn)
        }

        btnAddYourSticker.setOnClickListener {
            onRequestAddStickers {
                mainHandler.post {
                    if (dlg.isShowing) {
                        activeTab = 1
                        lastActiveTab = 1
                        renderContent()
                    }
                }
            }
        }

        dlg.show()
        renderContent()
    }

    private fun createSubtleCheckerDrawable(context: Context, dark: Boolean): BitmapDrawable {
        val cell = (8 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        val c1 = if (dark) Color.parseColor("#2A2A30") else Color.parseColor("#EFEFEF")
        val c2 = if (dark) Color.parseColor("#202026") else Color.parseColor("#DCDCDC")
        p.color = c1
        c.drawRect(0f, 0f, (cell * 2).toFloat(), (cell * 2).toFloat(), p)
        p.color = c2
        c.drawRect(cell.toFloat(), 0f, (cell * 2).toFloat(), cell.toFloat(), p)
        c.drawRect(0f, cell.toFloat(), cell.toFloat(), (cell * 2).toFloat(), p)
        return BitmapDrawable(context.resources, bmp).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
    }
}
