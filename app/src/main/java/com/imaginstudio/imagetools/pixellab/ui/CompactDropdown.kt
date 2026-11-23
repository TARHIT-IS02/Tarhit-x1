package com.imaginstudio.imagetools.pixellab.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.imaginstudio.imagetools.pixellab.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compact anchored scrollable dropdown menu with larger readable typography,
 * generous touch targets, and optional leading preview icons per item.
 */
object CompactDropdown {

    data class Item(
        val id: String,
        val label: String,
        val displayLabel: String = label,
        val enabled: Boolean = true,
        val icon: Drawable? = null
    )

    class Controller(
        private val context: Context,
        items: List<Item>,
        initialId: String,
        private val onSelected: (Item) -> Unit
    ) {
        private var currentItems: List<Item> = items
        var selectedId: String = initialId
            private set
        var isEnabled: Boolean = true
            set(value) {
                field = value
                trigger.isEnabled = value
                trigger.alpha = if (value) 1f else 0.45f
            }

        private val ivTriggerIcon: ImageView
        private val tvLabel: TextView
        private val ivChevron: ImageView
        val trigger: LinearLayout

        init {
            val dark = ThemeManager.isDark(context)
            trigger = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = dp(context, 44)
                setPadding(dp(context, 12), dp(context, 8), dp(context, 10), dp(context, 8))
                background = GradientDrawable().apply {
                    setColor(ThemeManager.panelSecondaryBg(dark))
                    cornerRadius = dp(context, 9).toFloat()
                    setStroke(dp(context, 1), ThemeManager.borderColor(dark))
                }
            }
            ivTriggerIcon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)).apply {
                    marginEnd = dp(context, 8)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            tvLabel = TextView(context).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ThemeManager.textPrimary(dark))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(context, 6)
                }
            }
            ivChevron = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 18), dp(context, 18))
                setImageResource(R.drawable.ic_chevron_down)
                setColorFilter(ThemeManager.textSecondary(dark))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            trigger.addView(ivTriggerIcon)
            trigger.addView(tvLabel)
            trigger.addView(ivChevron)
            trigger.setOnClickListener {
                if (isEnabled) showPopup()
            }
            refreshLabel()
        }

        fun setItems(items: List<Item>, selectId: String = selectedId) {
            currentItems = items
            selectedId = items.firstOrNull { it.id == selectId }?.id ?: items.firstOrNull()?.id ?: ""
            refreshLabel()
        }

        fun selectById(id: String, notify: Boolean = false) {
            val found = currentItems.firstOrNull { it.id == id } ?: return
            selectedId = found.id
            refreshLabel()
            if (notify) onSelected(found)
        }

        fun selectByIndex(index: Int, notify: Boolean = false) {
            val found = currentItems.getOrNull(index) ?: return
            selectedId = found.id
            refreshLabel()
            if (notify) onSelected(found)
        }

        fun selectedIndex(): Int = currentItems.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

        fun refreshTheme() {
            val dark = ThemeManager.isDark(context)
            trigger.background = GradientDrawable().apply {
                setColor(ThemeManager.panelSecondaryBg(dark))
                cornerRadius = dp(context, 9).toFloat()
                setStroke(dp(context, 1), ThemeManager.borderColor(dark))
            }
            tvLabel.setTextColor(ThemeManager.textPrimary(dark))
            ivChevron.setColorFilter(ThemeManager.textSecondary(dark))
        }

        private fun refreshLabel() {
            val cur = currentItems.firstOrNull { it.id == selectedId } ?: currentItems.firstOrNull()
            tvLabel.text = cur?.displayLabel ?: ""
            if (cur?.icon != null) {
                ivTriggerIcon.setImageDrawable(cur.icon)
                ivTriggerIcon.visibility = View.VISIBLE
            } else {
                ivTriggerIcon.setImageDrawable(null)
                ivTriggerIcon.visibility = View.GONE
            }
        }

        private fun showPopup() {
            val dark = ThemeManager.isDark(context)
            val dm = context.resources.displayMetrics
            val hasIcons = currentItems.any { it.icon != null }
            val minPopW = dp(context, if (hasIcons) 236 else 184)
            val maxPopW = min(dm.widthPixels - dp(context, 24), dp(context, 336))
            val popW = max(trigger.width, minPopW).coerceAtMost(maxPopW)
            val maxPopH = min((dm.heightPixels * 0.56f).roundToInt(), dp(context, 340))

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(ThemeManager.cardBg(dark))
                    cornerRadius = dp(context, 12).toFloat()
                    setStroke(dp(context, 1), ThemeManager.borderColor(dark))
                }
                elevation = dp(context, 10).toFloat()
                setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            }

            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            val listCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(listCol)
            card.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            val popup = PopupWindow(
                card,
                popW,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = true
                elevation = dp(context, 10).toFloat()
            }

            val accent = ThemeManager.accentPrimary(dark)
            val accentSoft = ThemeManager.accentSoftBg(dark)
            val txtPri = ThemeManager.textPrimary(dark)
            val txtMuted = ThemeManager.textSecondary(dark)

            for (item in currentItems) {
                val isSel = item.id == selectedId
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(context, 46)
                    setPadding(dp(context, 12), dp(context, 9), dp(context, 10), dp(context, 9))
                    if (isSel) {
                        background = GradientDrawable().apply {
                            setColor(accentSoft)
                            cornerRadius = dp(context, 8).toFloat()
                        }
                    }
                    alpha = if (item.enabled) 1f else 0.4f
                    isClickable = item.enabled
                }
                if (item.icon != null) {
                    row.addView(ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(context, 28), dp(context, 28)).apply {
                            marginEnd = dp(context, 10)
                        }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(item.icon)
                    })
                }
                val tv = TextView(context).apply {
                    text = item.label
                    textSize = 14.5f
                    typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(
                        when {
                            !item.enabled -> txtMuted
                            isSel -> accent
                            else -> txtPri
                        }
                    )
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(context, 8)
                    }
                }
                val check = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(context, 17), dp(context, 17))
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(accent)
                    visibility = if (isSel) View.VISIBLE else View.INVISIBLE
                }
                row.addView(tv)
                row.addView(check)
                if (item.enabled) {
                    row.setOnClickListener {
                        if (selectedId != item.id) {
                            selectedId = item.id
                            refreshLabel()
                            onSelected(item)
                        }
                        popup.dismiss()
                    }
                }
                listCol.addView(row)
            }

            card.measure(
                View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST)
            )
            val desiredH = min(card.measuredHeight, maxPopH)
            popup.height = desiredH + dp(context, 2)

            val loc = IntArray(2)
            trigger.getLocationInWindow(loc)
            val spaceBelow = dm.heightPixels - (loc[1] + trigger.height + dp(context, 12))
            val showAbove = spaceBelow < popup.height && loc[1] > popup.height + dp(context, 12)
            val yOff = if (showAbove) -(trigger.height + popup.height + dp(context, 4)) else dp(context, 4)

            ivChevron.animate().rotation(180f).setDuration(140L).start()
            popup.setOnDismissListener {
                ivChevron.animate().rotation(0f).setDuration(140L).start()
            }
            popup.showAsDropDown(trigger, 0, yOff)
        }
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).roundToInt()

    fun create(
        context: Context,
        items: List<Item>,
        initialId: String,
        onSelected: (Item) -> Unit
    ): Controller = Controller(context, items, initialId, onSelected)
}
