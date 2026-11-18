package com.soniboost

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import com.soniboost.audio.EqCurve
import com.soniboost.audio.EqCurves
import com.soniboost.audio.EqLibrary
import com.soniboost.audio.EqTemplate
import com.soniboost.audio.ToneBand
import com.soniboost.audio.ToneShape
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class EqActivity : AppCompatActivity() {
    private lateinit var library: EqLibrary
    private lateinit var templates: List<EqTemplate>
    private lateinit var templateSpinner: Spinner
    private lateinit var deleteButton: Button
    private var loading = true
    private var ignoreSpinner = false

    private val graphicSeeks = mutableListOf<SeekBar>()
    private val graphicValues = mutableListOf<TextView>()
    private val toneShapes = MutableList(EqCurve.TONE_COUNT) { ToneShape.Peak }
    private val toneShapeButtons = mutableListOf<List<ImageButton>>()
    private val frequencySeeks = mutableListOf<SeekBar>()
    private val frequencyValues = mutableListOf<TextView>()
    private val qualitySeeks = mutableListOf<SeekBar>()
    private val qualityValues = mutableListOf<TextView>()
    private val toneGainSeeks = mutableListOf<SeekBar>()
    private val toneGainValues = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)
        keepClearOfSystemBars(findViewById(R.id.eqContent))
        library = EqLibrary(this)
        templateSpinner = findViewById(R.id.templateSpinner)
        deleteButton = findViewById(R.id.deleteTemplateButton)
        buildGraphicRows(findViewById(R.id.graphicBands))
        buildToneRows(findViewById(R.id.toneBands))
        findViewById<Button>(R.id.saveTemplateButton).setOnClickListener { askNameAndSave() }
        deleteButton.setOnClickListener { deleteSelected() }
        reloadTemplates(selectId = library.activeTemplateId())
        show(library.active())
        loading = false
    }

    private fun reloadTemplates(selectId: String) {
        templates = library.templates()
        val names = templates.map { title(it) }
        loading = true
        templateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        ignoreSpinner = true
        templateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (loading || ignoreSpinner) {
                    ignoreSpinner = false
                    return
                }
                val template = templates[position]
                library.use(template)
                show(template.curve)
                deleteButton.isEnabled = !template.builtin
                ListeningService.applyEq(this@EqActivity)
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        val index = templates.indexOfFirst { it.id == selectId }.coerceAtLeast(0)
        templateSpinner.setSelection(index)
        deleteButton.isEnabled = !templates[index].builtin
        loading = false
    }

    private fun show(curve: EqCurve) {
        loading = true
        curve.graphic.forEachIndexed { index, gain ->
            graphicSeeks[index].progress = gain - EqCurve.MIN_DB
            graphicValues[index].text = dbText(gain)
        }
        curve.tones.forEachIndexed { index, band ->
            toneShapes[index] = shapeForGain(band.shape, band.gainDb)
            markShape(index)
            frequencySeeks[index].progress = hzToProgress(band.frequencyHz)
            frequencyValues[index].text = getString(R.string.eq_hz, band.frequencyHz)
            qualitySeeks[index].progress = qToProgress(band.q)
            qualityValues[index].text = qText(band.q)
            toneGainSeeks[index].progress = band.gainDb - EqCurve.MIN_DB
            toneGainValues[index].text = dbText(band.gainDb)
        }
        loading = false
    }

    private fun currentCurve(): EqCurve {
        return EqCurve(
            graphic = graphicSeeks.map { it.progress + EqCurve.MIN_DB },
            tones = toneShapes.indices.map { index ->
                ToneBand(
                    shape = toneShapes[index],
                    frequencyHz = progressToHz(frequencySeeks[index].progress),
                    q = progressToQ(qualitySeeks[index].progress),
                    gainDb = toneGainSeeks[index].progress + EqCurve.MIN_DB,
                )
            },
        )
    }

    private fun publish() {
        if (loading) return
        library.remember(currentCurve())
        ListeningService.applyEq(this)
    }

    private fun askNameAndSave() {
        val input = EditText(this).apply {
            setTextColor(getColor(R.color.on_background))
            setHintTextColor(getColor(R.color.on_background_muted))
            hint = getString(R.string.template_name_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.template_name_title)
            .setView(input)
            .setPositiveButton(R.string.template_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val saved = library.save(name, currentCurve())
                reloadTemplates(saved.id)
                ListeningService.applyEq(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteSelected() {
        val template = templates.getOrNull(templateSpinner.selectedItemPosition) ?: return
        if (template.builtin) return
        library.delete(template.id)
        reloadTemplates(EqCurves.FLAT)
        show(library.active())
        ListeningService.applyEq(this)
    }

    private fun buildGraphicRows(parent: LinearLayout) {
        EqCurve.LABELS.forEach { label ->
            val value = valueText()
            val seek = dbSeek {
                value.text = dbText(progress + EqCurve.MIN_DB)
                publish()
            }
            graphicValues += value
            graphicSeeks += seek
            parent.addView(row(labelView(label), seek, value))
        }
    }

    private fun buildToneRows(parent: LinearLayout) {
        repeat(EqCurve.TONE_COUNT) { index ->
            val title = TextView(this).apply {
                text = getString(R.string.tone_band, index + 1)
                setTextColor(getColor(R.color.on_background))
                textSize = 18f
                setPadding(0, dp(16), 0, 0)
            }
            parent.addView(title)
            val buttons = ToneShape.entries.map { shape ->
                ImageButton(this).apply {
                    setImageResource(shapeIcon(shape))
                    contentDescription = shapeDescription(shape)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = ContextCompat.getDrawable(this@EqActivity, R.drawable.shape_choice_bg)
                    backgroundTintList = null
                    ImageViewCompat.setImageTintList(
                        this,
                        ContextCompat.getColorStateList(this@EqActivity, R.color.mic_button_text),
                    )
                    minimumHeight = dp(48)
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        marginEnd = dp(4)
                    }
                    setOnClickListener { applyShape(index, shape) }
                }
            }
            toneShapeButtons += buttons
            parent.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { addView(it) }
            })
            val frequencyValue = valueText()
            val frequency = SeekBar(this).apply {
                max = FREQUENCY_STEPS
                setOnSeekBarChangeListener(listener {
                    frequencyValue.text = getString(R.string.eq_hz, progressToHz(progress))
                    publish()
                })
            }
            frequencySeeks += frequency
            frequencyValues += frequencyValue
            parent.addView(row(labelView(getString(R.string.eq_frequency)), frequency, frequencyValue))
            val qualityValue = valueText()
            val quality = SeekBar(this).apply {
                max = QUALITY_STEPS
                setOnSeekBarChangeListener(listener {
                    qualityValue.text = qText(progressToQ(progress))
                    publish()
                })
            }
            qualitySeeks += quality
            qualityValues += qualityValue
            parent.addView(row(labelView(getString(R.string.eq_quality)), quality, qualityValue))
            val gainValue = valueText()
            val gain = dbSeek {
                val db = progress + EqCurve.MIN_DB
                gainValue.text = dbText(db)
                toneShapes[index] = shapeForGain(toneShapes[index], db)
                markShape(index)
                publish()
            }
            toneGainSeeks += gain
            toneGainValues += gainValue
            parent.addView(row(labelView(getString(R.string.eq_gain)), gain, gainValue))
        }
    }

    private fun applyShape(index: Int, shape: ToneShape) {
        val gain = toneGainSeeks[index].progress + EqCurve.MIN_DB
        val nextGain = when (shape) {
            ToneShape.Peak -> kotlin.math.abs(gain)
            ToneShape.PeakCut -> -kotlin.math.abs(gain)
            else -> gain
        }
        toneShapes[index] = shape
        if (nextGain != gain) {
            toneGainSeeks[index].progress = nextGain - EqCurve.MIN_DB
            toneGainValues[index].text = dbText(nextGain)
        }
        markShape(index)
        publish()
    }

    private fun shapeForGain(shape: ToneShape, gain: Int): ToneShape {
        return when (shape) {
            ToneShape.Peak, ToneShape.PeakCut -> when {
                gain > 0 -> ToneShape.Peak
                gain < 0 -> ToneShape.PeakCut
                else -> shape
            }
            else -> shape
        }
    }

    private fun markShape(index: Int) {
        toneShapeButtons[index].forEachIndexed { shapeIndex, button ->
            button.isSelected = ToneShape.entries[shapeIndex] == toneShapes[index]
        }
    }

    private fun title(template: EqTemplate): String {
        return when (template.id) {
            EqCurves.SPEECH -> getString(R.string.template_speech)
            EqCurves.STRONG -> getString(R.string.template_strong)
            EqCurves.FLAT -> getString(R.string.template_flat)
            else -> template.name
        }
    }

    private fun row(label: TextView, seek: SeekBar, value: TextView): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(label)
            addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }
    }

    private fun labelView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.on_background))
            textSize = 16f
            width = dp(112)
        }
    }

    private fun valueText(): TextView {
        return TextView(this).apply {
            setTextColor(getColor(R.color.on_background))
            textSize = 16f
            width = dp(72)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
    }

    private fun dbSeek(onChange: SeekBar.() -> Unit): SeekBar {
        return SeekBar(this).apply {
            max = EqCurve.MAX_DB - EqCurve.MIN_DB
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
            thumbTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
            setOnSeekBarChangeListener(listener(onChange))
        }
    }

    private fun listener(onChange: SeekBar.() -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) seekBar.onChange()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        }
    }

    private fun dbText(gain: Int): String = getString(R.string.eq_db, gain)

    private fun qText(q: Float): String = getString(R.string.eq_q, q)

    private fun progressToHz(progress: Int): Int {
        val min = ln(MIN_HZ)
        val max = ln(MAX_HZ)
        return exp(min + (max - min) * progress / FREQUENCY_STEPS).roundToInt()
    }

    private fun hzToProgress(hz: Int): Int {
        val min = ln(MIN_HZ)
        val max = ln(MAX_HZ)
        val position = (ln(hz.coerceIn(MIN_HZ.toInt(), MAX_HZ.toInt()).toDouble()) - min) / (max - min)
        return (position * FREQUENCY_STEPS).roundToInt().coerceIn(0, FREQUENCY_STEPS)
    }

    private fun progressToQ(progress: Int): Float = 0.5f + progress * 0.1f

    private fun qToProgress(q: Float): Int = ((q - 0.5f) / 0.1f).roundToInt().coerceIn(0, QUALITY_STEPS)

    private fun shapeIcon(shape: ToneShape): Int {
        return when (shape) {
            ToneShape.Peak -> R.drawable.ic_eq_peak_up
            ToneShape.PeakCut -> R.drawable.ic_eq_peak_down
            ToneShape.LowShelf -> R.drawable.ic_eq_low_shelf
            ToneShape.HighShelf -> R.drawable.ic_eq_high_shelf
        }
    }

    private fun shapeDescription(shape: ToneShape): String {
        return when (shape) {
            ToneShape.Peak -> getString(R.string.tone_peak_up)
            ToneShape.PeakCut -> getString(R.string.tone_peak_down)
            ToneShape.LowShelf -> getString(R.string.tone_low_shelf)
            ToneShape.HighShelf -> getString(R.string.tone_high_shelf)
        }
    }

    private fun keepClearOfSystemBars(content: View) {
        val baseLeft = content.paddingLeft
        val baseTop = content.paddingTop
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom,
            )
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val FREQUENCY_STEPS = 100
        const val QUALITY_STEPS = 75
        const val MIN_HZ = 100.0
        const val MAX_HZ = 8_000.0
    }
}
