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
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import com.soniboost.audio.AdaptiveSettings
import com.soniboost.audio.EqCurve
import com.soniboost.audio.EqCurves
import com.soniboost.audio.LoweringMethod
import com.soniboost.audio.NoiseGateSettings
import com.soniboost.audio.LoweringSettings
import com.soniboost.audio.LoweringStore
import com.soniboost.audio.SoundProfile
import com.soniboost.audio.SoundProfileLibrary
import com.soniboost.audio.SoundProfiles
import com.soniboost.audio.ToneBand
import com.soniboost.audio.ToneShape
import com.soniboost.audio.allowsAdaptiveBoost
import com.soniboost.audio.allowsFrequencyLowering
import com.soniboost.audio.currentPowerProfile
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class EqActivity : AppCompatActivity() {
    private lateinit var library: SoundProfileLibrary
    private lateinit var profiles: List<SoundProfile>
    private lateinit var templateSpinner: Spinner
    private lateinit var profileDescription: TextView
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
    private lateinit var loweringSwitch: SwitchCompat
    private lateinit var powerNote: TextView
    private lateinit var compressionButton: Button
    private lateinit var shiftButton: Button
    private lateinit var cutoffSeek: SeekBar
    private lateinit var cutoffValue: TextView
    private lateinit var amountLabel: TextView
    private lateinit var amountSeek: SeekBar
    private lateinit var amountValue: TextView
    private lateinit var mixSeek: SeekBar
    private lateinit var mixValue: TextView
    private lateinit var adaptiveSwitch: SwitchCompat
    private lateinit var adaptivePowerNote: TextView
    private lateinit var strengthSeek: SeekBar
    private lateinit var strengthValue: TextView
    private lateinit var cautionSeek: SeekBar
    private lateinit var cautionValue: TextView
    private lateinit var gateSwitch: SwitchCompat
    private lateinit var gateThresholdSeek: SeekBar
    private lateinit var gateThresholdValue: TextView
    private lateinit var gateDepthSeek: SeekBar
    private lateinit var gateDepthValue: TextView
    private lateinit var gateAttackSeek: SeekBar
    private lateinit var gateAttackValue: TextView
    private lateinit var gateHoldSeek: SeekBar
    private lateinit var gateHoldValue: TextView
    private lateinit var gateReleaseSeek: SeekBar
    private lateinit var gateReleaseValue: TextView
    private var loweringMethod = LoweringMethod.Compression
    private var ratio = 2f
    private var shiftHz = 1_000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)
        keepClearOfSystemBars(findViewById(R.id.eqContent))
        library = SoundProfileLibrary(this)
        templateSpinner = findViewById(R.id.templateSpinner)
        profileDescription = findViewById(R.id.profileDescription)
        deleteButton = findViewById(R.id.deleteTemplateButton)
        bindGate()
        bindLowering()
        bindAdaptive()
        buildToneRows(findViewById(R.id.toneBands))
        buildGraphicRows(findViewById(R.id.graphicBands))
        findViewById<Button>(R.id.saveTemplateButton).setOnClickListener { askNameAndSave() }
        findViewById<Button>(R.id.restoreTemplateButton).setOnClickListener { restoreSelected() }
        deleteButton.setOnClickListener { deleteSelected() }
        val shownId = library.activeId().ifEmpty { library.originId() }
        reloadProfiles(selectId = shownId)
        show(library.active())
        loading = false
    }

    override fun onResume() {
        super.onResume()
        if (::powerNote.isInitialized) refreshPowerNote()
    }

    private fun reloadProfiles(selectId: String) {
        profiles = library.profiles()
        val names = profiles.map { title(it) }
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
                val profile = profiles[position]
                library.use(profile)
                show(profile)
                deleteButton.isEnabled = !profile.builtin
                ListeningService.applyProfile(this@EqActivity)
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        val index = profiles.indexOfFirst { it.id == selectId }.coerceAtLeast(0)
        templateSpinner.setSelection(index)
        deleteButton.isEnabled = !profiles[index].builtin
        loading = false
    }

    private fun show(profile: SoundProfile) {
        loading = true
        val curve = profile.curve
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
        showGate(profile.gate)
        showLowering(profile.lowering)
        showAdaptive(profile.adaptive)
        showPurpose(profile)
        loading = false
        refreshPowerNote()
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

    private fun currentProfile(): SoundProfile {
        return SoundProfile(
            id = "",
            name = "",
            curve = currentCurve(),
            lowering = currentLowering(),
            adaptive = currentAdaptive(),
            gate = currentGate(),
            builtin = false,
        )
    }

    private fun publish() {
        if (loading) return
        library.remember(currentProfile())
        ListeningService.applyProfile(this)
        refreshPowerNote()
    }

    private fun askNameAndSave() {
        val nameInput = field(getString(R.string.template_name_hint))
        val descriptionInput = field(getString(R.string.template_description_hint))
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(24)
            setPadding(pad, pad, pad, 0)
            addView(nameInput)
            addView(descriptionInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.template_name_title)
            .setView(form)
            .setPositiveButton(R.string.template_save) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val saved = library.save(
                    name,
                    currentProfile().copy(description = descriptionInput.text.toString().trim()),
                )
                reloadProfiles(saved.id)
                ListeningService.applyProfile(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun field(hint: String): EditText {
        return EditText(this).apply {
            setTextColor(getColor(R.color.on_background))
            setHintTextColor(getColor(R.color.on_background_muted))
            this.hint = hint
            setSingleLine()
        }
    }

    private fun restoreSelected() {
        val profile = profiles.getOrNull(templateSpinner.selectedItemPosition) ?: return
        library.use(profile)
        show(profile)
        deleteButton.isEnabled = !profile.builtin
        ListeningService.applyProfile(this)
    }

    private fun deleteSelected() {
        val profile = profiles.getOrNull(templateSpinner.selectedItemPosition) ?: return
        if (profile.builtin) return
        library.delete(profile.id)
        reloadProfiles(EqCurves.FLAT)
        show(library.active())
        ListeningService.applyProfile(this)
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

    private fun title(profile: SoundProfile): String {
        return when (profile.id) {
            EqCurves.SPEECH -> getString(R.string.template_speech)
            EqCurves.STRONG -> getString(R.string.template_strong)
            SoundProfiles.CLARITY -> getString(R.string.template_clarity)
            SoundProfiles.ADAPTIVE -> getString(R.string.template_adaptive)
            SoundProfiles.EVERYDAY -> getString(R.string.template_everyday)
            SoundProfiles.NOISE -> getString(R.string.template_noise)
            SoundProfiles.QUIET -> getString(R.string.template_quiet)
            SoundProfiles.MUSIC -> getString(R.string.template_music)
            SoundProfiles.TV -> getString(R.string.template_tv)
            SoundProfiles.PHONE -> getString(R.string.template_phone)
            SoundProfiles.OUTDOOR -> getString(R.string.template_outdoor)
            SoundProfiles.CAR -> getString(R.string.template_car)
            SoundProfiles.FL_MODERATE -> getString(R.string.template_fl_moderate)
            SoundProfiles.FL_STRONG -> getString(R.string.template_fl_strong)
            EqCurves.FLAT -> getString(R.string.template_flat)
            else -> profile.name
        }
    }

    private fun showPurpose(profile: SoundProfile) {
        val text = when (profile.id) {
            EqCurves.SPEECH -> getString(R.string.profile_speech_purpose)
            EqCurves.STRONG -> getString(R.string.profile_strong_purpose)
            SoundProfiles.CLARITY -> getString(R.string.profile_clarity_purpose)
            SoundProfiles.ADAPTIVE -> getString(R.string.profile_adaptive_purpose)
            SoundProfiles.EVERYDAY -> getString(R.string.profile_everyday_purpose)
            SoundProfiles.NOISE -> getString(R.string.profile_noise_purpose)
            SoundProfiles.QUIET -> getString(R.string.profile_quiet_purpose)
            SoundProfiles.MUSIC -> getString(R.string.profile_music_purpose)
            SoundProfiles.TV -> getString(R.string.profile_tv_purpose)
            SoundProfiles.PHONE -> getString(R.string.profile_phone_purpose)
            SoundProfiles.OUTDOOR -> getString(R.string.profile_outdoor_purpose)
            SoundProfiles.CAR -> getString(R.string.profile_car_purpose)
            SoundProfiles.FL_MODERATE -> getString(R.string.profile_fl_moderate_purpose)
            SoundProfiles.FL_STRONG -> getString(R.string.profile_fl_strong_purpose)
            EqCurves.FLAT -> getString(R.string.profile_flat_purpose)
            else -> profile.description
        }
        profileDescription.text = text
        profileDescription.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
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

    private fun bindLowering() {
        loweringSwitch = findViewById(R.id.loweringSwitch)
        powerNote = findViewById(R.id.powerNote)
        compressionButton = findViewById(R.id.compressionButton)
        shiftButton = findViewById(R.id.shiftButton)
        cutoffSeek = findViewById(R.id.cutoffSeek)
        cutoffValue = findViewById(R.id.cutoffValue)
        amountLabel = findViewById(R.id.amountLabel)
        amountSeek = findViewById(R.id.amountSeek)
        amountValue = findViewById(R.id.amountValue)
        mixSeek = findViewById(R.id.mixSeek)
        mixValue = findViewById(R.id.mixValue)
        cutoffSeek.setOnSeekBarChangeListener(loweringListener { showCutoff(); publish() })
        amountSeek.setOnSeekBarChangeListener(loweringListener { showAmount(); publish() })
        mixSeek.setOnSeekBarChangeListener(loweringListener { showMix(); publish() })
        loweringSwitch.setOnCheckedChangeListener { _, _ -> if (!loading) publish() }
        compressionButton.setOnClickListener { selectLowering(LoweringMethod.Compression) }
        shiftButton.setOnClickListener { selectLowering(LoweringMethod.Transposition) }
    }

    private fun selectLowering(method: LoweringMethod) {
        loweringMethod = method
        markLowering()
        showAmount()
        publish()
    }

    private fun showLowering(settings: LoweringSettings) {
        loweringMethod = settings.method
        ratio = settings.ratio
        shiftHz = settings.shiftHz
        loweringSwitch.isChecked = settings.enabled
        cutoffSeek.progress = ((settings.cutoffHz - LoweringStore.MIN_CUTOFF) / CUTOFF_STEP)
            .coerceIn(0, cutoffSeek.max)
        amountSeek.max = if (settings.method == LoweringMethod.Compression) RATIO_STEPS else SHIFT_STEPS
        amountSeek.progress = if (settings.method == LoweringMethod.Compression) {
            ((ratio - LoweringStore.MIN_RATIO) / RATIO_STEP).toInt()
        } else {
            (shiftHz - LoweringStore.MIN_SHIFT) / SHIFT_STEP
        }.coerceIn(0, amountSeek.max)
        mixSeek.progress = (settings.mix * 100f).toInt().coerceIn(0, 100)
        markLowering()
        showCutoff()
        showAmount()
        showMix()
    }

    private fun currentLowering(): LoweringSettings {
        if (loweringMethod == LoweringMethod.Compression) {
            ratio = LoweringStore.MIN_RATIO + amountSeek.progress * RATIO_STEP
        } else {
            shiftHz = LoweringStore.MIN_SHIFT + amountSeek.progress * SHIFT_STEP
        }
        return LoweringSettings(
            enabled = loweringSwitch.isChecked,
            method = loweringMethod,
            cutoffHz = LoweringStore.MIN_CUTOFF + cutoffSeek.progress * CUTOFF_STEP,
            ratio = ratio,
            shiftHz = shiftHz,
            mix = mixSeek.progress / 100f,
        )
    }

    private fun markLowering() {
        compressionButton.isSelected = loweringMethod == LoweringMethod.Compression
        shiftButton.isSelected = loweringMethod == LoweringMethod.Transposition
    }

    private fun showCutoff() {
        cutoffValue.text = getString(R.string.lowering_hz, LoweringStore.MIN_CUTOFF + cutoffSeek.progress * CUTOFF_STEP)
    }

    private fun showAmount() {
        if (loweringMethod == LoweringMethod.Compression) {
            if (amountSeek.max != RATIO_STEPS) {
                amountSeek.max = RATIO_STEPS
                amountSeek.progress = ((ratio - LoweringStore.MIN_RATIO) / RATIO_STEP).toInt().coerceIn(0, RATIO_STEPS)
            }
            amountLabel.text = getString(R.string.lowering_ratio)
            amountValue.text = String.format(Locale.getDefault(), "%.1f", LoweringStore.MIN_RATIO + amountSeek.progress * RATIO_STEP)
        } else {
            if (amountSeek.max != SHIFT_STEPS) {
                amountSeek.max = SHIFT_STEPS
                amountSeek.progress = ((shiftHz - LoweringStore.MIN_SHIFT) / SHIFT_STEP).coerceIn(0, SHIFT_STEPS)
            }
            amountLabel.text = getString(R.string.lowering_shift_amount)
            amountValue.text = getString(R.string.lowering_hz, LoweringStore.MIN_SHIFT + amountSeek.progress * SHIFT_STEP)
        }
    }

    private fun showMix() {
        mixValue.text = getString(R.string.lowering_mix_value, mixSeek.progress)
    }

    private fun bindGate() {
        gateSwitch = findViewById(R.id.gateSwitch)
        gateThresholdSeek = findViewById(R.id.gateThresholdSeek)
        gateThresholdValue = findViewById(R.id.gateThresholdValue)
        gateDepthSeek = findViewById(R.id.gateDepthSeek)
        gateDepthValue = findViewById(R.id.gateDepthValue)
        gateAttackSeek = findViewById(R.id.gateAttackSeek)
        gateAttackValue = findViewById(R.id.gateAttackValue)
        gateHoldSeek = findViewById(R.id.gateHoldSeek)
        gateHoldValue = findViewById(R.id.gateHoldValue)
        gateReleaseSeek = findViewById(R.id.gateReleaseSeek)
        gateReleaseValue = findViewById(R.id.gateReleaseValue)
        gateThresholdSeek.setOnSeekBarChangeListener(loweringListener { showGateThreshold(); publish() })
        gateDepthSeek.setOnSeekBarChangeListener(loweringListener { showGateDepth(); publish() })
        gateAttackSeek.setOnSeekBarChangeListener(loweringListener { showGateAttack(); publish() })
        gateHoldSeek.setOnSeekBarChangeListener(loweringListener { showGateHold(); publish() })
        gateReleaseSeek.setOnSeekBarChangeListener(loweringListener { showGateRelease(); publish() })
        gateSwitch.setOnCheckedChangeListener { _, _ -> if (!loading) publish() }
    }

    private fun showGate(settings: NoiseGateSettings) {
        gateSwitch.isChecked = settings.enabled
        gateThresholdSeek.progress = (settings.thresholdDb - NoiseGateSettings.MIN_THRESHOLD)
            .coerceIn(0, NoiseGateSettings.MAX_THRESHOLD - NoiseGateSettings.MIN_THRESHOLD)
        gateDepthSeek.progress = (settings.depthDb - NoiseGateSettings.MIN_DEPTH)
            .coerceIn(0, NoiseGateSettings.MAX_DEPTH - NoiseGateSettings.MIN_DEPTH)
        gateAttackSeek.progress = (settings.attackMs - NoiseGateSettings.MIN_ATTACK)
            .coerceIn(0, NoiseGateSettings.MAX_ATTACK - NoiseGateSettings.MIN_ATTACK)
        gateHoldSeek.progress = (settings.holdMs / NoiseGateSettings.HOLD_STEP)
            .coerceIn(0, NoiseGateSettings.MAX_HOLD / NoiseGateSettings.HOLD_STEP)
        gateReleaseSeek.progress = ((settings.releaseMs - NoiseGateSettings.MIN_RELEASE) / NoiseGateSettings.RELEASE_STEP)
            .coerceIn(0, (NoiseGateSettings.MAX_RELEASE - NoiseGateSettings.MIN_RELEASE) / NoiseGateSettings.RELEASE_STEP)
        showGateThreshold()
        showGateDepth()
        showGateAttack()
        showGateHold()
        showGateRelease()
    }

    private fun currentGate(): NoiseGateSettings {
        return NoiseGateSettings(
            enabled = gateSwitch.isChecked,
            thresholdDb = NoiseGateSettings.MIN_THRESHOLD + gateThresholdSeek.progress,
            depthDb = NoiseGateSettings.MIN_DEPTH + gateDepthSeek.progress,
            attackMs = NoiseGateSettings.MIN_ATTACK + gateAttackSeek.progress,
            holdMs = gateHoldSeek.progress * NoiseGateSettings.HOLD_STEP,
            releaseMs = NoiseGateSettings.MIN_RELEASE + gateReleaseSeek.progress * NoiseGateSettings.RELEASE_STEP,
        )
    }

    private fun showGateThreshold() {
        val db = NoiseGateSettings.MIN_THRESHOLD + gateThresholdSeek.progress
        gateThresholdValue.text = getString(R.string.gate_db, db)
    }

    private fun showGateDepth() {
        val db = NoiseGateSettings.MIN_DEPTH + gateDepthSeek.progress
        gateDepthValue.text = getString(R.string.gate_db, db)
    }

    private fun showGateAttack() {
        gateAttackValue.text = getString(R.string.gate_ms, NoiseGateSettings.MIN_ATTACK + gateAttackSeek.progress)
    }

    private fun showGateHold() {
        gateHoldValue.text = getString(R.string.gate_ms, gateHoldSeek.progress * NoiseGateSettings.HOLD_STEP)
    }

    private fun showGateRelease() {
        val ms = NoiseGateSettings.MIN_RELEASE + gateReleaseSeek.progress * NoiseGateSettings.RELEASE_STEP
        gateReleaseValue.text = getString(R.string.gate_ms, ms)
    }

    private fun bindAdaptive() {
        adaptiveSwitch = findViewById(R.id.adaptiveSwitch)
        adaptivePowerNote = findViewById(R.id.adaptivePowerNote)
        strengthSeek = findViewById(R.id.strengthSeek)
        strengthValue = findViewById(R.id.strengthValue)
        cautionSeek = findViewById(R.id.cautionSeek)
        cautionValue = findViewById(R.id.cautionValue)
        strengthSeek.setOnSeekBarChangeListener(loweringListener { showStrength(); publish() })
        cautionSeek.setOnSeekBarChangeListener(loweringListener { showCaution(); publish() })
        adaptiveSwitch.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            if (checked && strengthSeek.progress == 0) {
                strengthSeek.progress = AdaptiveSettings.DEFAULT_STRENGTH
                showStrength()
            }
            publish()
        }
    }

    private fun showAdaptive(settings: AdaptiveSettings) {
        adaptiveSwitch.isChecked = settings.enabled
        strengthSeek.progress = settings.strengthDb.coerceIn(AdaptiveSettings.MIN_STRENGTH, AdaptiveSettings.MAX_STRENGTH)
        cautionSeek.progress = settings.caution.coerceIn(AdaptiveSettings.MIN_CAUTION, AdaptiveSettings.MAX_CAUTION)
        showStrength()
        showCaution()
    }

    private fun currentAdaptive(): AdaptiveSettings {
        return AdaptiveSettings(
            enabled = adaptiveSwitch.isChecked,
            strengthDb = strengthSeek.progress,
            caution = cautionSeek.progress,
        )
    }

    private fun showStrength() {
        strengthValue.text = getString(R.string.adaptive_db, strengthSeek.progress)
    }

    private fun showCaution() {
        cautionValue.text = getString(R.string.adaptive_percent, cautionSeek.progress)
    }

    private fun refreshPowerNote() {
        val power = currentPowerProfile()
        val loweringBlocked = loweringSwitch.isChecked && !power.allowsFrequencyLowering()
        powerNote.visibility = if (loweringBlocked) View.VISIBLE else View.GONE
        val adaptiveBlocked = adaptiveSwitch.isChecked && !power.allowsAdaptiveBoost()
        adaptivePowerNote.visibility = if (adaptiveBlocked) View.VISIBLE else View.GONE
    }

    private fun loweringListener(onChange: () -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !loading) onChange()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
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
        const val CUTOFF_STEP = 100
        const val RATIO_STEP = 0.1f
        const val RATIO_STEPS = 15
        const val SHIFT_STEP = 50
        const val SHIFT_STEPS = 40
    }
}
