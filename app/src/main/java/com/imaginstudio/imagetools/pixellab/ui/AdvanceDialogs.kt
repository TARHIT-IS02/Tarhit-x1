package com.imaginstudio.imagetools.pixellab.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import com.imaginstudio.imagetools.pixellab.R
import com.imaginstudio.imagetools.pixellab.engine.AdvanceImageResampler
import com.imaginstudio.imagetools.pixellab.engine.BrushEngine
import com.imaginstudio.imagetools.pixellab.engine.ShapeToImageExporter
import com.imaginstudio.imagetools.pixellab.model.GridState
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AdvanceDialogs {

    private fun accentColor(ctx: Context): Int = ThemeManager.accentPrimary(ThemeManager.isDark(ctx))
    private fun accentBlueColor(ctx: Context): Int = ThemeManager.accentBlue(ThemeManager.isDark(ctx))
    private fun slateTextColor(ctx: Context): Int = ThemeManager.textPrimary(ThemeManager.isDark(ctx))
    private fun mutedTextColor(ctx: Context): Int = ThemeManager.textSecondary(ThemeManager.isDark(ctx))
    private fun surfaceBgColor(ctx: Context): Int = ThemeManager.panelSecondaryBg(ThemeManager.isDark(ctx))
    private fun borderStrokeColor(ctx: Context): Int = ThemeManager.borderColor(ThemeManager.isDark(ctx))

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun configureWhiteCardDialog(dialog: Dialog, maxWidthDp: Int = 368, maxHeightFrac: Float = 0.88f): LinearLayout {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dm = dialog.context.resources.displayMetrics
        val dark = ThemeManager.isDark(dialog.context)
        val w = min(dm.widthPixels - dp(dialog.context, 28), dp(dialog.context, maxWidthDp))
        val maxH = (dm.heightPixels * maxHeightFrac).roundToInt()

        val card = LinearLayout(dialog.context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogCardDrawable(dialog.context, dark, 12)
            setPadding(dp(dialog.context, 14), dp(dialog.context, 12), dp(dialog.context, 14), dp(dialog.context, 10))
        }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.58f)
            setGravity(Gravity.CENTER)
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        card.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.height > maxH) {
                dialog.window?.setLayout(w, maxH)
            }
        }
        return card
    }

    private fun makeHeader(ctx: Context, title: String, subtitle: String? = null, trailing: View? = null): LinearLayout {
        val txtPri = slateTextColor(ctx)
        val txtMuted = mutedTextColor(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 2), 0, dp(ctx, 2), dp(ctx, 8))
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = title
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(txtPri)
                })
                if (!subtitle.isNullOrEmpty()) {
                    addView(TextView(ctx).apply {
                        text = subtitle
                        textSize = 11.5f
                        setTextColor(txtMuted)
                    })
                }
            }
            addView(textCol)
            if (trailing != null) addView(trailing)
        }
    }

    private fun makeFooter(ctx: Context, cancelText: String = "CANCEL", okText: String = "OK", onCancel: () -> Unit, onOk: () -> Unit): LinearLayout {
        val act = accentColor(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 2))
            if (cancelText.isNotEmpty()) {
                addView(TextView(ctx).apply {
                    text = cancelText
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(act)
                    setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                    setOnClickListener { onCancel() }
                })
            }
            if (okText.isNotEmpty()) {
                addView(TextView(ctx).apply {
                    text = okText
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(act)
                    setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
                    setOnClickListener { onOk() }
                })
            }
        }
    }

    private fun styleBoxInput(ctx: Context): GradientDrawable =
        ThemeManager.inputDrawable(ctx, ThemeManager.isDark(ctx), 7)

    private fun applyTealSwitch(sw: SwitchMaterial) {
        ThemeManager.applySwitchTheme(sw, ThemeManager.isDark(sw.context))
    }

    // =========================================================================
    // 1. ADVANCE IMAGE SIZE DIALOG (matching reference HTML #tabImgSize)
    // =========================================================================

    fun showAdvanceImageSizeDialog(
        context: Context,
        project: ProjectState,
        onApplied: (ProjectState) -> Unit
    ) {
        val dialog = Dialog(context)
        val card = configureWhiteCardDialog(dialog, maxWidthDp = 382, maxHeightFrac = 0.90f)

        val origW = project.width.coerceAtLeast(1)
        val origH = project.height.coerceAtLeast(1)
        val origDpi = project.dpi.coerceIn(1f, 99999f)

        var pw = origW.toFloat()
        var ph = origH.toFloat()
        var res = origDpi
        var link = true
        var resample = true
        var unit = AdvanceImageResampler.SizeUnit.PX
        var resUnit = AdvanceImageResampler.ResUnit.PPI
        var method = AdvanceImageResampler.ResampleMethod.AUTO
        var reduceNoise = 0
        var fitKey = "orig"
        var syncing = false

        val dark = ThemeManager.isDark(context)
        val teal = accentColor(context)
        val accentBlue = accentBlueColor(context)
        val slateText = slateTextColor(context)
        val mutedText = mutedTextColor(context)
        val surfaceBg = surfaceBgColor(context)
        val borderStroke = borderStrokeColor(context)
        val cardBgCol = ThemeManager.cardBg(dark)
        val labelColW = dp(context, 84)
        val bracketColW = dp(context, 28)
        val innerLabelW = labelColW - bracketColW // 56dp -> colons align vertically across all rows
        val unitColW = dp(context, 114)
        val fieldH = dp(context, 40)

        card.addView(makeHeader(context, "Image Size"))

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 6))
        }
        scroll.addView(content)
        card.addView(scroll)

        fun makeRightLabel(textStr: String, widthPx: Int = labelColW): TextView {
            return TextView(context).apply {
                text = textStr
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(mutedText)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(context, 8), 0)
                layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }

        fun makeNumberEdit(): EditText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 13.5f
            setTextColor(slateText)
            setHintTextColor(mutedText)
            background = styleBoxInput(context)
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }

        lateinit var updateUiRef: (Boolean) -> Unit

        // Row 1: Image Size: [1.63M (was ...)]      [Reset Gear]
        val rowImgSize = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 10) }
        }
        val tvMbInfo = TextView(context).apply {
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(slateText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnReset = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32))
            background = ThemeManager.circleButtonDrawable(context, dark)
            setImageResource(R.drawable.ic_reset)
            setColorFilter(slateText)
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Reset Image Size"
        }
        rowImgSize.addView(makeRightLabel("Image Size:"))
        rowImgSize.addView(tvMbInfo)
        rowImgSize.addView(btnReset)
        content.addView(rowImgSize)

        // Row 2: Dimensions: [1280 × 1280 px]
        val rowDimInfo = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 10) }
        }
        val tvDimInfo = TextView(context).apply {
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(slateText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowDimInfo.addView(makeRightLabel("Dimensions:"))
        rowDimInfo.addView(tvDimInfo)
        content.addView(rowDimInfo)

        // Row 3: Fit To: [Dropdown]
        val fitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
        }
        val fitDropdown = CompactDropdown.create(
            context = context,
            items = AdvanceImageResampler.FIT_PRESETS.map { CompactDropdown.Item(it.key, it.label, it.label) },
            initialId = fitKey
        ) { item ->
            if (syncing) return@create
            val preset = AdvanceImageResampler.FIT_PRESETS.firstOrNull { it.key == item.id } ?: return@create
            fitKey = preset.key
            when {
                preset.key == "orig" -> {
                    pw = origW.toFloat()
                    ph = origH.toFloat()
                    res = origDpi
                }
                preset.w != null && preset.h != null -> {
                    if (!resample) resample = true
                    pw = preset.w.toFloat()
                    ph = preset.h.toFloat()
                }
            }
            updateUiRef(true)
        }
        fitDropdown.trigger.minimumHeight = fieldH
        fitDropdown.trigger.layoutParams = LinearLayout.LayoutParams(0, fieldH, 1f)
        fitRow.addView(makeRightLabel("Fit To:"))
        fitRow.addView(fitDropdown.trigger)
        content.addView(fitRow)

        // Grouped .grp block: Left U-Bracket + Circular Link Toggle spanning Width & Height, plus Resolution aligned below
        val grpSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
        }

        val whRowWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 10) }
        }

        val bracketCol = android.widget.FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(bracketColW, dp(context, 90))
        }
        val bracketLineView = object : View(context) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * resources.displayMetrics.density
            }
            private val rRect = RectF()
            override fun onDraw(canvas: Canvas) {
                p.color = mutedText
                p.alpha = 180
                val leftX = width * 0.5f
                val topY = dp(context, 20).toFloat()
                val botY = (height - dp(context, 20)).toFloat().coerceAtLeast(topY + 4f)
                val rightX = width.toFloat() + dp(context, 4)
                rRect.set(leftX, topY, rightX, botY)
                val rad = dp(context, 5).toFloat()
                canvas.drawRoundRect(rRect, rad, rad, p)
            }
        }.apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val btnLink = ImageButton(context).apply {
            val sz = dp(context, 26)
            layoutParams = android.widget.FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
            setPadding(dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 5))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Constrain proportions"
        }
        fun syncLinkButtonUi() {
            btnLink.setImageResource(if (link) R.drawable.ic_link else R.drawable.ic_link_off)
            btnLink.setColorFilter(if (link) accentBlue else mutedText)
            btnLink.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(cardBgCol)
                setStroke(dp(context, 1), if (link) accentBlue else borderStroke)
            }
        }
        syncLinkButtonUi()
        bracketCol.addView(bracketLineView)
        bracketCol.addView(btnLink)

        val whFieldsCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val etW = makeNumberEdit()
        val etH = makeNumberEdit()

        val allUnits = AdvanceImageResampler.SizeUnit.entries
        fun buildUnitItems(canResample: Boolean) = allUnits.map { u ->
            val en = canResample || (u != AdvanceImageResampler.SizeUnit.PX && u != AdvanceImageResampler.SizeUnit.PCT)
            CompactDropdown.Item(u.name, u.label, u.label, enabled = en)
        }

        val ddUnitW = CompactDropdown.create(context, buildUnitItems(resample), unit.name) { item ->
            if (syncing) return@create
            val picked = allUnits.firstOrNull { it.name == item.id } ?: return@create
            if (!resample && (picked == AdvanceImageResampler.SizeUnit.PX || picked == AdvanceImageResampler.SizeUnit.PCT)) {
                updateUiRef(true)
                return@create
            }
            unit = picked
            updateUiRef(true)
        }
        val ddUnitH = CompactDropdown.create(context, buildUnitItems(resample), unit.name) { item ->
            if (syncing) return@create
            val picked = allUnits.firstOrNull { it.name == item.id } ?: return@create
            if (!resample && (picked == AdvanceImageResampler.SizeUnit.PX || picked == AdvanceImageResampler.SizeUnit.PCT)) {
                updateUiRef(true)
                return@create
            }
            unit = picked
            updateUiRef(true)
        }

        fun makeGrpFieldRow(label: String, edit: EditText, unitTrigger: LinearLayout, bottomGapDp: Int): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(context, bottomGapDp) }
                addView(makeRightLabel(label, innerLabelW))
                edit.layoutParams = LinearLayout.LayoutParams(0, fieldH, 1f).apply {
                    marginEnd = dp(context, 8)
                }
                addView(edit)
                unitTrigger.minimumHeight = fieldH
                unitTrigger.layoutParams = LinearLayout.LayoutParams(unitColW, fieldH)
                addView(unitTrigger)
            }
        }

        whFieldsCol.addView(makeGrpFieldRow("Width:", etW, ddUnitW.trigger, 10))
        whFieldsCol.addView(makeGrpFieldRow("Height:", etH, ddUnitH.trigger, 0))
        whRowWrap.addView(bracketCol)
        whRowWrap.addView(whFieldsCol)
        grpSection.addView(whRowWrap)

        // Resolution Row aligned in column 2 directly below Height:
        val resRowWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        resRowWrap.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(bracketColW, dp(context, 1))
        })
        val etRes = makeNumberEdit()
        val resUnits = AdvanceImageResampler.ResUnit.entries
        val ddResUnit = CompactDropdown.create(
            context,
            resUnits.map { CompactDropdown.Item(it.name, it.label, it.label) },
            resUnit.name
        ) { item ->
            if (syncing) return@create
            resUnit = resUnits.firstOrNull { it.name == item.id } ?: return@create
            updateUiRef(true)
        }
        val resFieldsInner = makeGrpFieldRow("Resolution:", etRes, ddResUnit.trigger, 0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        resRowWrap.addView(resFieldsInner)
        grpSection.addView(resRowWrap)
        content.addView(grpSection)

        // Row: [x] Resample: | Method Dropdown
        val resampRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
        }
        val resampLabelWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(context, 8), 0)
            layoutParams = LinearLayout.LayoutParams(labelColW, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cbResample = CheckBox(context).apply {
            isChecked = resample
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            buttonTintList = ColorStateList.valueOf(accentBlue)
            setPadding(0, 0, 0, 0)
        }
        val tvResampleLbl = TextView(context).apply {
            text = "Resample:"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mutedText)
            setOnClickListener { cbResample.performClick() }
        }
        resampLabelWrap.addView(cbResample)
        resampLabelWrap.addView(tvResampleLbl)

        val methods = AdvanceImageResampler.ResampleMethod.entries
        val ddMethod = CompactDropdown.create(
            context,
            methods.map { CompactDropdown.Item(it.name, it.label, it.label) },
            method.name
        ) { item ->
            if (syncing) return@create
            method = methods.firstOrNull { it.name == item.id } ?: return@create
            updateUiRef(false)
        }
        ddMethod.trigger.minimumHeight = fieldH
        ddMethod.trigger.layoutParams = LinearLayout.LayoutParams(0, fieldH, 1f)
        resampRow.addView(resampLabelWrap)
        resampRow.addView(ddMethod.trigger)
        content.addView(resampRow)

        // Row: Reduce Noise: [SeekBar] [50] [%]
        val noiseRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 6) }
        }
        noiseRow.addView(makeRightLabel("Reduce\nNoise:"))

        val sbNoise = SeekBar(context).apply {
            max = 100
            progress = 0
            ThemeManager.applySeekBarTheme(this, dark)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 8)
            }
        }
        val etNoise = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 13.5f
            setTextColor(slateText)
            background = styleBoxInput(context)
            gravity = Gravity.CENTER
            isSingleLine = true
            setText("0")
            setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(dp(context, 52), fieldH).apply {
                marginEnd = dp(context, 4)
            }
        }
        val tvNoisePct = TextView(context).apply {
            text = "%"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mutedText)
        }
        noiseRow.addView(sbNoise)
        noiseRow.addView(etNoise)
        noiseRow.addView(tvNoisePct)
        content.addView(noiseRow)

        fun updateUiFromState(updateInputs: Boolean = true) {
            syncing = true
            val rw = pw.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
            val rh = ph.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
            val curMb = AdvanceImageResampler.formatMegabytes(rw, rh)
            val origMb = AdvanceImageResampler.formatMegabytes(origW, origH)
            tvMbInfo.text = if (rw != origW || rh != origH) {
                "$curMb (was $origMb)"
            } else {
                curMb
            }
            tvDimInfo.text = "$rw × $rh px"

            if (!resample && (unit == AdvanceImageResampler.SizeUnit.PX || unit == AdvanceImageResampler.SizeUnit.PCT)) {
                unit = AdvanceImageResampler.SizeUnit.IN
            }
            val uItems = buildUnitItems(resample)
            ddUnitW.setItems(uItems, unit.name)
            ddUnitH.setItems(uItems, unit.name)

            if (updateInputs) {
                val wDisp = AdvanceImageResampler.formatValue(AdvanceImageResampler.toUnit(pw, unit, origW, res), unit)
                val hDisp = AdvanceImageResampler.formatValue(AdvanceImageResampler.toUnit(ph, unit, origH, res), unit)
                if (etW.text.toString() != wDisp) etW.setText(wDisp)
                if (etH.text.toString() != hDisp) etH.setText(hDisp)

                val resDispVal = if (resUnit == AdvanceImageResampler.ResUnit.PPI) res else res / 2.54f
                val resDisp = String.format(Locale.US, "%.2f", resDispVal).trimEnd('0').trimEnd('.')
                if (etRes.text.toString() != resDisp) etRes.setText(resDisp)
            }

            fitDropdown.selectById(fitKey, notify = false)
            cbResample.isChecked = resample
            ddMethod.isEnabled = resample
            ddMethod.selectById(method.name, notify = false)
            ddResUnit.selectById(resUnit.name, notify = false)

            val noiseOn = resample && method == AdvanceImageResampler.ResampleMethod.PRESERVE
            sbNoise.isEnabled = noiseOn
            etNoise.isEnabled = noiseOn
            noiseRow.alpha = if (noiseOn) 1f else 0.45f
            if (sbNoise.progress != reduceNoise) sbNoise.progress = reduceNoise
            val noiseStr = reduceNoise.toString()
            if (etNoise.text.toString() != noiseStr) etNoise.setText(noiseStr)
            syncing = false
        }
        updateUiRef = ::updateUiFromState

        btnLink.setOnClickListener {
            link = !link
            syncLinkButtonUi()
        }

        btnReset.setOnClickListener {
            btnReset.animate().rotationBy(-360f).setDuration(420L).start()
            pw = origW.toFloat()
            ph = origH.toFloat()
            res = origDpi
            fitKey = "orig"
            resample = true
            method = AdvanceImageResampler.ResampleMethod.AUTO
            reduceNoise = 0
            updateUiFromState(true)
        }

        etW.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                if (v <= 0f) return
                if (!resample) {
                    res = (pw / AdvanceImageResampler.toInches(v, unit).coerceAtLeast(1e-4f)).coerceIn(1f, 99999f)
                    syncing = true
                    val hDisp = AdvanceImageResampler.formatValue(AdvanceImageResampler.toUnit(ph, unit, origH, res), unit)
                    etH.setText(hDisp)
                    val resDispVal = if (resUnit == AdvanceImageResampler.ResUnit.PPI) res else res / 2.54f
                    etRes.setText(String.format(Locale.US, "%.2f", resDispVal).trimEnd('0').trimEnd('.'))
                    syncing = false
                    return
                }
                val aspect = origW.toFloat() / origH.toFloat()
                pw = AdvanceImageResampler.fromUnit(v, unit, origW, res)
                    .coerceIn(AdvanceImageResampler.MIN_DIM.toFloat(), AdvanceImageResampler.MAX_DIM.toFloat())
                if (link) {
                    ph = (pw / aspect).coerceIn(AdvanceImageResampler.MIN_DIM.toFloat(), AdvanceImageResampler.MAX_DIM.toFloat())
                }
                fitKey = "custom"
                syncing = true
                val hDisp = AdvanceImageResampler.formatValue(AdvanceImageResampler.toUnit(ph, unit, origH, res), unit)
                etH.setText(hDisp)
                val rw = pw.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
                val rh = ph.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
                val curMb = AdvanceImageResampler.formatMegabytes(rw, rh)
                val origMb = AdvanceImageResampler.formatMegabytes(origW, origH)
                tvMbInfo.text = if (rw != origW || rh != origH) "$curMb (was $origMb)" else curMb
                tvDimInfo.text = "$rw × $rh px"
                fitDropdown.selectById("custom", notify = false)
                syncing = false
            }
        })

        etH.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                if (v <= 0f) return
                if (!resample) {
                    res = (ph / AdvanceImageResampler.toInches(v, unit).coerceAtLeast(1e-4f)).coerceIn(1f, 99999f)
                    syncing = true
                    val wDisp = AdvanceImageResampler.formatValue(AdvanceImageResampler.toUnit(pw, unit, origW, res), unit)
                    etW.setText(wDisp)
                    val resDispVal = if (resUnit == AdvanceImageResampler.ResUnit.PPI) res else res / 2.54f
                    etRes.setText(String.format(Locale.US, "%.2f", resDispVal).trimEnd('0').trimEnd('.'))
                    syncing = false
                    return
                }
                val aspect = origW.toFloat() / origH.toFloat()
                ph = AdvanceImageResampler.fromUnit(v, unit, origH, res)
                    .coerceIn(AdvanceImageResampler.MIN_DIM.toFloat(), AdvanceImageResampler.MAX_DIM.toFloat())
                if (link) {
                    pw = (ph * aspect).coerceIn(AdvanceImageResampler.MIN_DIM.toFloat(), AdvanceImageResampler.MAX_DIM.toFloat())
                }
                fitKey = "custom"
                syncing = true
                val wDisp = AdvanceImageResampler.formatValue(AdvanceImageResampler.toUnit(pw, unit, origW, res), unit)
                etW.setText(wDisp)
                val rw = pw.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
                val rh = ph.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
                val curMb = AdvanceImageResampler.formatMegabytes(rw, rh)
                val origMb = AdvanceImageResampler.formatMegabytes(origW, origH)
                tvMbInfo.text = if (rw != origW || rh != origH) "$curMb (was $origMb)" else curMb
                tvDimInfo.text = "$rw × $rh px"
                fitDropdown.selectById("custom", notify = false)
                syncing = false
            }
        })

        etRes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                if (v <= 0f) return
                val newRes = (if (resUnit == AdvanceImageResampler.ResUnit.PPI) v else v * 2.54f).coerceIn(1f, 99999f)
                if (resample && unit != AdvanceImageResampler.SizeUnit.PX && unit != AdvanceImageResampler.SizeUnit.PCT) {
                    val scale = newRes / res.coerceAtLeast(1f)
                    pw = (pw * scale).coerceIn(AdvanceImageResampler.MIN_DIM.toFloat(), AdvanceImageResampler.MAX_DIM.toFloat())
                    ph = (ph * scale).coerceIn(AdvanceImageResampler.MIN_DIM.toFloat(), AdvanceImageResampler.MAX_DIM.toFloat())
                    fitKey = "custom"
                }
                res = newRes
                syncing = true
                if (!resample || unit == AdvanceImageResampler.SizeUnit.PX || unit == AdvanceImageResampler.SizeUnit.PCT) {
                    etW.setText(AdvanceImageResampler.formatValue(AdvanceImageResampler.toUnit(pw, unit, origW, res), unit))
                    etH.setText(AdvanceImageResampler.formatValue(AdvanceImageResampler.toUnit(ph, unit, origH, res), unit))
                }
                val rw = pw.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
                val rh = ph.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
                val curMb = AdvanceImageResampler.formatMegabytes(rw, rh)
                val origMb = AdvanceImageResampler.formatMegabytes(origW, origH)
                tvMbInfo.text = if (rw != origW || rh != origH) "$curMb (was $origMb)" else curMb
                tvDimInfo.text = "$rw × $rh px"
                syncing = false
            }
        })

        cbResample.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            resample = checked
            if (!checked) {
                pw = origW.toFloat()
                ph = origH.toFloat()
            }
            updateUiFromState(true)
        }

        sbNoise.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                syncing = true
                reduceNoise = progress
                etNoise.setText(progress.toString())
                syncing = false
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        etNoise.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val parsed = s?.toString()?.toIntOrNull() ?: return
                val clamped = parsed.coerceIn(0, 100)
                syncing = true
                reduceNoise = clamped
                sbNoise.progress = clamped
                if (parsed != clamped) {
                    etNoise.setText(clamped.toString())
                    etNoise.setSelection(etNoise.text.length)
                }
                syncing = false
            }
        })

        updateUiFromState(true)

        val executor = Executors.newSingleThreadExecutor()
        val mainHandler = Handler(Looper.getMainLooper())

        fun applyAndClose() {
            val targetW = pw.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
            val targetH = ph.roundToInt().coerceIn(AdvanceImageResampler.MIN_DIM, AdvanceImageResampler.MAX_DIM)
            dialog.dismiss()
            if (!resample || (targetW == origW && targetH == origH)) {
                onApplied(AdvanceImageResampler.applyAdvanceResize(context, project, targetW, targetH, res, resample, method, reduceNoise))
            } else {
                Toast.makeText(context, "Resampling to ${targetW}×${targetH}...", Toast.LENGTH_SHORT).show()
                executor.execute {
                    val updated = AdvanceImageResampler.applyAdvanceResize(
                        context.applicationContext,
                        project,
                        targetW,
                        targetH,
                        res,
                        resample,
                        method,
                        reduceNoise
                    )
                    mainHandler.post {
                        onApplied(updated)
                    }
                }
            }
        }

        // Bottom pill buttons matching HTML .isz .btns
        val btnsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 10), 0, dp(context, 2))
        }
        val btnOkPill = TextView(context).apply {
            text = "OK"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(accentBlue)
                cornerRadius = dp(context, 999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 42), 1f).apply {
                marginEnd = dp(context, 10)
            }
            setOnClickListener { applyAndClose() }
        }
        val btnCancelPill = TextView(context).apply {
            text = "Cancel"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(slateText)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(surfaceBg)
                cornerRadius = dp(context, 999).toFloat()
                setStroke(dp(context, 1), borderStroke)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 42), 1f)
            setOnClickListener { dialog.dismiss() }
        }
        btnsRow.addView(btnOkPill)
        btnsRow.addView(btnCancelPill)
        card.addView(btnsRow)

        dialog.setOnDismissListener { executor.shutdown() }
        dialog.show()
    }

    // =========================================================================
    // 2. GRID & MAGNETIC SNAP SETTINGS DIALOG (HTML #tabGrid)
    // =========================================================================

    fun showGridSnapSettingsDialog(
        context: Context,
        initialGrid: GridState,
        project: ProjectState,
        onLiveChange: (GridState) -> Unit,
        onConfirm: (GridState) -> Unit
    ) {
        val dialog = Dialog(context)
        val card = configureWhiteCardDialog(dialog, maxWidthDp = 368, maxHeightFrac = 0.88f)
        var state = initialGrid
        var committed = false

        val dark = ThemeManager.isDark(context)
        val teal = accentColor(context)
        val accentBlue = accentBlueColor(context)
        val slateText = slateTextColor(context)
        val mutedText = mutedTextColor(context)
        val borderStroke = borderStrokeColor(context)

        val btnReset = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32))
            background = ThemeManager.circleButtonDrawable(context, dark)
            setImageResource(R.drawable.ic_reset)
            setColorFilter(slateText)
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        card.addView(makeHeader(context, "Grid & Magnetic Snap", "Guides, Pixel Grid & Snap Field", btnReset))

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 4))
        }
        scroll.addView(content)
        card.addView(scroll)

        fun update(next: GridState) {
            state = next
            onLiveChange(next)
        }

        fun makeSwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit): SwitchMaterial {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 6))
            }
            row.addView(TextView(context).apply {
                text = label
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(slateText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val sw = SwitchMaterial(context).apply {
                isChecked = checked
                applyTealSwitch(this)
                setOnCheckedChangeListener { _, isCh -> onToggle(isCh) }
            }
            row.addView(sw)
            content.addView(row)
            return sw
        }

        val swShowGrid = makeSwitchRow("Show Grid Overlay", state.enabled) { en -> update(state.copy(enabled = en)) }
        val swShowGuides = makeSwitchRow("Show Guides (PixelLab Lines)", state.showGuides) { sg -> update(state.copy(showGuides = sg)) }
        val swShowPixelGrid = makeSwitchRow("Show Pixel Grid Mesh", state.showPixelGrid) { pg -> update(state.copy(showPixelGrid = pg)) }
        val swSnap = makeSwitchRow("Magnetic Snap", state.snap) { sn -> update(state.copy(snap = sn)) }

        // Quick Guide Presets section
        val presetSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 10))
        }
        presetSection.addView(TextView(context).apply {
            text = "Guide Presets"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mutedText)
            setPadding(0, 0, 0, dp(context, 6))
        })
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addPresetChip(title: String, onTap: () -> Unit) {
            presetRow.addView(TextView(context).apply {
                text = title
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (title == "Clear") Color.parseColor("#DC2626") else accentBlue)
                gravity = Gravity.CENTER
                isSingleLine = true
                background = ThemeManager.pillDrawable(context, dark, active = false, radiusDp = 8)
                setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginEnd = dp(context, 4) }
                setOnClickListener { onTap() }
            })
        }
        fun makeEqualGuides(count: Int): List<Float> = (1..count).map { it.toFloat() / (count + 1) }
        addPresetChip("2×2") {
            swShowGrid.isChecked = true
            swShowGuides.isChecked = true
            val g = makeEqualGuides(2)
            update(state.copy(enabled = true, showGuides = true, hGuides = g, vGuides = g))
        }
        addPresetChip("3×3") {
            swShowGrid.isChecked = true
            swShowGuides.isChecked = true
            val g = makeEqualGuides(3)
            update(state.copy(enabled = true, showGuides = true, hGuides = g, vGuides = g))
        }
        addPresetChip("4×4") {
            swShowGrid.isChecked = true
            swShowGuides.isChecked = true
            val g = makeEqualGuides(4)
            update(state.copy(enabled = true, showGuides = true, hGuides = g, vGuides = g))
        }
        addPresetChip("5×5") {
            swShowGrid.isChecked = true
            swShowGuides.isChecked = true
            val g = makeEqualGuides(5)
            update(state.copy(enabled = true, showGuides = true, hGuides = g, vGuides = g))
        }
        addPresetChip("Clear") {
            update(state.copy(hGuides = emptyList(), vGuides = emptyList()))
        }
        presetSection.addView(presetRow)
        content.addView(presetSection)

        // Grid Color picker row
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 10))
        }
        colorRow.addView(TextView(context).apply {
            text = "Grid Color"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(slateText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val colorSwatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 44), dp(context, 28))
            background = GradientDrawable().apply {
                setColor(state.color)
                cornerRadius = dp(context, 6).toFloat()
                setStroke(dp(context, 1), borderStroke)
            }
        }
        colorRow.addView(colorSwatch)
        colorRow.setOnClickListener {
            ColorAndGradientDialogs.showColorPickerDialog(
                context = context,
                initialColor = state.color,
                project = project,
                isNested = true,
                onLiveChange = { c ->
                    (colorSwatch.background as? GradientDrawable)?.setColor(c)
                    update(state.copy(color = c))
                }
            ) { c ->
                (colorSwatch.background as? GradientDrawable)?.setColor(c)
                update(state.copy(color = c))
            }
        }
        content.addView(colorRow)

        fun makeSlider(title: String, minVal: Int, maxVal: Int, curVal: Int, suffix: String, onChanged: (Int) -> Unit): Pair<SeekBar, TextView> {
            val block = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 8))
            }
            val hdr = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            hdr.addView(TextView(context).apply {
                text = title
                textSize = 12.5f
                setTextColor(slateText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val tvVal = TextView(context).apply {
                text = "$curVal$suffix"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentBlue)
            }
            hdr.addView(tvVal)
            val sb = SeekBar(context).apply {
                max = maxVal - minVal
                progress = (curVal - minVal).coerceIn(0, maxVal - minVal)
                ThemeManager.applySeekBarTheme(this, dark)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = minVal + p
                        tvVal.text = "$v$suffix"
                        if (fromUser) onChanged(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) = Unit
                })
            }
            block.addView(hdr)
            block.addView(sb)
            content.addView(block)
            return sb to tvVal
        }

        val (sbSize, _) = makeSlider("Grid Size", 8, 400, state.size, "px") { v -> update(state.copy(size = v)) }
        val (sbOp, _) = makeSlider("Grid Opacity", 5, 100, state.opacity, "%") { v -> update(state.copy(opacity = v)) }
        val (sbStr, _) = makeSlider("Snap Strength", 0, 100, state.snapStrength, "%") { v -> update(state.copy(snapStrength = v)) }
        val (sbFld, _) = makeSlider("Magnetic Field", 2, 60, state.magneticField, "px") { v -> update(state.copy(magneticField = v)) }

        btnReset.setOnClickListener {
            val def = GridState(enabled = state.enabled, snap = state.snap)
            swShowGrid.isChecked = def.enabled
            swShowGuides.isChecked = def.showGuides
            swShowPixelGrid.isChecked = def.showPixelGrid
            swSnap.isChecked = def.snap
            sbSize.progress = def.size - 8
            sbOp.progress = def.opacity - 5
            sbStr.progress = def.snapStrength
            sbFld.progress = def.magneticField - 2
            (colorSwatch.background as? GradientDrawable)?.setColor(def.color)
            update(def)
        }

        card.addView(makeFooter(context, onCancel = {
            onLiveChange(initialGrid)
            dialog.dismiss()
        }) {
            committed = true
            onConfirm(state)
            dialog.dismiss()
        })
        dialog.setOnDismissListener {
            if (!committed) onLiveChange(initialGrid)
        }
        dialog.show()
    }

    // =========================================================================
    // 3. BRUSH PICKER POPUP (15 HTML Procedural Brushes with curved stroke previews)
    // =========================================================================

    fun showBrushPickerDialog(
        context: Context,
        currentPresetId: String,
        brushColor: Int,
        onSelect: (BrushEngine.BrushPreset) -> Unit
    ) {
        val dialog = Dialog(context)
        val card = configureWhiteCardDialog(dialog, maxWidthDp = 368, maxHeightFrac = 0.85f)
        card.addView(makeHeader(context, "Brushes", "15 Procedural Tip & Texture Brushes"))

        val dark = ThemeManager.isDark(context)
        val teal = accentColor(context)
        val slateText = slateTextColor(context)
        val mutedText = mutedTextColor(context)
        val surfaceBg = surfaceBgColor(context)
        val borderStroke = borderStrokeColor(context)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(body)
        card.addView(scroll)

        val previewColor = if (Color.alpha(brushColor) < 40) slateText else brushColor
        val categories = BrushEngine.PRESETS.groupBy { it.category }

        for ((cat, list) in categories) {
            body.addView(TextView(context).apply {
                text = cat
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(mutedText)
                setPadding(dp(context, 2), dp(context, 8), 0, dp(context, 6))
            })

            val grid = GridLayout(context).apply {
                columnCount = 3
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (preset in list) {
                val active = preset.id == currentPresetId
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
                    background = GradientDrawable().apply {
                        setColor(if (active) ThemeManager.accentSoftBg(dark) else surfaceBg)
                        cornerRadius = dp(context, 8).toFloat()
                        setStroke(dp(context, if (active) 2 else 1), if (active) teal else borderStroke)
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
                    }
                    setOnClickListener {
                        onSelect(preset)
                        dialog.dismiss()
                    }
                }
                val bmp = BrushEngine.drawBrushStrokePreview(dp(context, 86), dp(context, 38), preset, previewColor)
                item.addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(context, 86), dp(context, 38))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bmp)
                })
                item.addView(TextView(context).apply {
                    text = preset.name
                    textSize = 11f
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (active) teal else slateText)
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    setPadding(0, dp(context, 3), 0, 0)
                })
                grid.addView(item)
            }
            body.addView(grid)
        }

        card.addView(makeFooter(context, cancelText = "CLOSE", okText = "", onCancel = { dialog.dismiss() }, onOk = { dialog.dismiss() }))
        dialog.show()
    }

    // =========================================================================
    // 4. SHAPE CREATOR DIALOG (11 HTML shapes -> PNG ImageLayer, ZERO color controls)
    // =========================================================================

    fun showShapeCreatorDialog(
        context: Context,
        @Suppress("UNUSED_PARAMETER") project: ProjectState,
        onAddShapeImage: (ShapeToImageExporter.HtmlShapeKind, Int, Int, Boolean) -> Unit
    ) {
        val dialog = Dialog(context)
        val card = configureWhiteCardDialog(dialog, maxWidthDp = 372, maxHeightFrac = 0.88f)
        card.addView(makeHeader(context, "Shapes", "Auto-converts to Image Layer"))

        val dark = ThemeManager.isDark(context)
        val teal = accentColor(context)
        val accentBlue = accentBlueColor(context)
        val slateText = slateTextColor(context)
        val surfaceBg = surfaceBgColor(context)
        val borderStroke = borderStrokeColor(context)

        var selectedShape = ShapeToImageExporter.HtmlShapeKind.RECT
        var lockedRatio = false
        var widthPct = (selectedShape.defaultWidthRatio * 100f).roundToInt()
        var heightPct = (selectedShape.defaultHeightRatio * 100f).roundToInt()
        var lockedAspect = (widthPct.toFloat() / heightPct.coerceAtLeast(1).toFloat()).coerceAtLeast(0.05f)
        var syncing = false

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(body)
        card.addView(scroll)

        val itemCards = mutableMapOf<ShapeToImageExporter.HtmlShapeKind, LinearLayout>()
        val grid = GridLayout(context).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8) }
        }

        lateinit var btnFreeMode: TextView
        lateinit var btnLockMode: TextView
        lateinit var sbW: SeekBar
        lateinit var sbH: SeekBar
        lateinit var tvW: TextView
        lateinit var tvH: TextView

        fun syncModePills() {
            btnFreeMode.setTextColor(if (!lockedRatio) Color.WHITE else slateText)
            btnFreeMode.background = GradientDrawable().apply {
                setColor(if (!lockedRatio) teal else surfaceBg)
                cornerRadius = dp(context, 7).toFloat()
                setStroke(dp(context, 1), if (!lockedRatio) teal else borderStroke)
            }
            btnLockMode.setTextColor(if (lockedRatio) Color.WHITE else slateText)
            btnLockMode.background = GradientDrawable().apply {
                setColor(if (lockedRatio) teal else surfaceBg)
                cornerRadius = dp(context, 7).toFloat()
                setStroke(dp(context, 1), if (lockedRatio) teal else borderStroke)
            }
        }

        fun syncSliders() {
            syncing = true
            sbW.progress = (widthPct - 5).coerceIn(0, 245)
            sbH.progress = (heightPct - 5).coerceIn(0, 245)
            tvW.text = "${widthPct}%"
            tvH.text = "${heightPct}%"
            syncing = false
        }

        fun refreshCardHighlights() {
            for ((k, box) in itemCards) {
                val active = k == selectedShape
                box.background = GradientDrawable().apply {
                    setColor(if (active) ThemeManager.accentSoftBg(dark) else surfaceBg)
                    cornerRadius = dp(context, 8).toFloat()
                    setStroke(dp(context, if (active) 2 else 1), if (active) teal else borderStroke)
                }
            }
        }

        for (kind in ShapeToImageExporter.HtmlShapeKind.entries) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(context, 6), dp(context, 8), dp(context, 6), dp(context, 8))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
                }
                setOnClickListener {
                    selectedShape = kind
                    lockedRatio = kind.isSquareOrCircle
                    widthPct = (kind.defaultWidthRatio * 100f).roundToInt()
                    heightPct = if (lockedRatio) widthPct else (kind.defaultHeightRatio * 100f).roundToInt()
                    lockedAspect = (widthPct.toFloat() / heightPct.coerceAtLeast(1).toFloat()).coerceAtLeast(0.05f)
                    refreshCardHighlights()
                    syncModePills()
                    syncSliders()
                }
            }
            itemCards[kind] = item
            val previewBmp = ShapeToImageExporter.renderPreviewBitmap(kind, dp(context, 42))
            item.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 42), dp(context, 42))
                setImageBitmap(previewBmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            item.addView(TextView(context).apply {
                text = kind.label
                textSize = 11f
                setTextColor(slateText)
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(0, dp(context, 4), 0, 0)
            })
            grid.addView(item)
        }
        body.addView(grid)
        refreshCardHighlights()

        // Relative Size section (Free Resizing vs Locked-Ratio 1:1) — ZERO color options
        val sizeBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ThemeManager.cardElevatedBg(dark))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), borderStroke)
            }
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        }
        sizeBox.addView(TextView(context).apply {
            text = "Relative Size Mode"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(slateText)
            setPadding(0, 0, 0, dp(context, 6))
        })

        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8) }
        }
        btnFreeMode = TextView(context).apply {
            text = "Free Resizing"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(context, 8), dp(context, 7), dp(context, 8), dp(context, 7))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 6)
            }
            setOnClickListener {
                lockedRatio = false
                syncModePills()
            }
        }
        btnLockMode = TextView(context).apply {
            text = "Locked-Ratio (1:1)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(context, 8), dp(context, 7), dp(context, 8), dp(context, 7))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                lockedRatio = true
                if (selectedShape.isSquareOrCircle) {
                    heightPct = widthPct
                    lockedAspect = 1f
                    syncSliders()
                } else {
                    lockedAspect = (widthPct.toFloat() / heightPct.coerceAtLeast(1).toFloat()).coerceAtLeast(0.05f)
                }
                syncModePills()
            }
        }
        modeRow.addView(btnFreeMode)
        modeRow.addView(btnLockMode)
        sizeBox.addView(modeRow)
        syncModePills()

        fun buildPctBar(label: String, onChanged: (Int) -> Unit): Pair<SeekBar, TextView> {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(context, 2), 0, dp(context, 4))
            }
            val hdr = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            hdr.addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(slateText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val tv = TextView(context).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentBlue)
            }
            hdr.addView(tv)
            val sb = SeekBar(context).apply {
                max = 245 // 5%..250%
                ThemeManager.applySeekBarTheme(this, dark)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser || syncing) return
                        onChanged(5 + p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) = Unit
                })
            }
            row.addView(hdr)
            row.addView(sb)
            sizeBox.addView(row)
            return sb to tv
        }

        val wPair = buildPctBar("Width Relative Size") { v ->
            widthPct = v
            if (lockedRatio) {
                heightPct = if (selectedShape.isSquareOrCircle) v else (v / lockedAspect).roundToInt().coerceIn(5, 250)
            } else {
                lockedAspect = (widthPct.toFloat() / heightPct.coerceAtLeast(1).toFloat()).coerceAtLeast(0.05f)
            }
            syncSliders()
        }
        sbW = wPair.first
        tvW = wPair.second

        val hPair = buildPctBar("Height Relative Size") { v ->
            heightPct = v
            if (lockedRatio) {
                widthPct = if (selectedShape.isSquareOrCircle) v else (v * lockedAspect).roundToInt().coerceIn(5, 250)
            } else {
                lockedAspect = (widthPct.toFloat() / heightPct.coerceAtLeast(1).toFloat()).coerceAtLeast(0.05f)
            }
            syncSliders()
        }
        sbH = hPair.first
        tvH = hPair.second
        syncSliders()
        body.addView(sizeBox)

        card.addView(makeFooter(context, cancelText = "CANCEL", okText = "ADD SHAPE", onCancel = { dialog.dismiss() }) {
            dialog.dismiss()
            onAddShapeImage(selectedShape, widthPct, heightPct, lockedRatio)
        })
        dialog.show()
    }
}
