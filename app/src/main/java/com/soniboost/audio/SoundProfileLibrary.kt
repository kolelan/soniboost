package com.soniboost.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SoundProfileLibrary(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val legacyEq = EqLibrary(context)
    private val legacyLowering = LoweringStore(context)

    fun active(): SoundProfile {
        migrate()
        val id = preferences.getString(ACTIVE_ID, null)
        if (!id.isNullOrEmpty()) {
            SoundProfiles.byId(id)?.let { return it }
        }
        return decodeProfile(preferences.getString(ACTIVE, null)) ?: SoundProfiles.byId(EqCurves.SPEECH)!!
    }

    fun activeId(): String {
        migrate()
        return preferences.getString(ACTIVE_ID, EqCurves.SPEECH) ?: EqCurves.SPEECH
    }

    fun profiles(): List<SoundProfile> {
        migrate()
        return SoundProfiles.builtins() + users()
    }

    fun originId(): String {
        migrate()
        val origin = preferences.getString(ORIGIN, null)
        if (!origin.isNullOrEmpty()) return origin
        val active = preferences.getString(ACTIVE_ID, null)
        if (!active.isNullOrEmpty()) return active
        return EqCurves.SPEECH
    }

    fun use(profile: SoundProfile) {
        preferences.edit()
            .putString(ACTIVE_ID, profile.id)
            .putString(ORIGIN, profile.id)
            .putString(ACTIVE, encodeProfile(profile))
            .apply()
    }

    fun remember(profile: SoundProfile) {
        preferences.edit()
            .putString(ACTIVE_ID, "")
            .putString(ACTIVE, encodeProfile(profile.copy(id = "", name = "", builtin = false)))
            .apply()
    }

    fun save(name: String, profile: SoundProfile): SoundProfile {
        val trimmed = name.trim()
        val current = users().toMutableList()
        val existing = current.indexOfFirst { it.name == trimmed }
        val saved = if (existing >= 0) {
            current[existing].copy(
                curve = profile.curve,
                lowering = profile.lowering,
                adaptive = profile.adaptive,
                gate = profile.gate,
                clarity = profile.clarity,
                limiterDb = profile.limiterDb,
                description = profile.description,
            )
        } else {
            profile.copy(id = "user-${System.currentTimeMillis()}", name = trimmed, builtin = false)
        }
        if (existing >= 0) current[existing] = saved else current.add(saved)
        preferences.edit()
            .putString(USERS, encodeUsers(current))
            .putString(ACTIVE_ID, saved.id)
            .putString(ORIGIN, saved.id)
            .putString(ACTIVE, encodeProfile(saved))
            .apply()
        return saved
    }

    fun delete(id: String) {
        val current = users().filterNot { it.id == id }
        val editor = preferences.edit().putString(USERS, encodeUsers(current))
        if (activeId() == id) {
            val flat = SoundProfiles.byId(EqCurves.FLAT)!!
            editor.putString(ACTIVE_ID, flat.id).putString(ORIGIN, flat.id).putString(ACTIVE, encodeProfile(flat))
        }
        editor.apply()
    }

    private fun migrate() {
        if (preferences.contains(ACTIVE)) return
        val lowering = legacyLowering.get()
        val id = legacyEq.activeTemplateId()
        val curve = legacyEq.active()
        val builtin = SoundProfiles.byId(id)
        val active = if (builtin != null && curve == builtin.curve) {
            builtin.copy(lowering = if (id == EqCurves.SPEECH || id == EqCurves.STRONG || id == EqCurves.FLAT) lowering else builtin.lowering)
        } else {
            SoundProfile("", "", curve, lowering, AdaptiveSettings(), false)
        }
        val imported = legacyEq.templates().filterNot { it.builtin }.map { template ->
            SoundProfile(template.id, template.name, template.curve, LoweringSettings(), AdaptiveSettings(), false)
        }
        preferences.edit()
            .putString(USERS, encodeUsers(imported))
            .putString(ACTIVE_ID, active.id)
            .putString(ACTIVE, encodeProfile(active))
            .apply()
    }

    private fun users(): List<SoundProfile> {
        val raw = preferences.getString(USERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decodeProfile(array.getJSONObject(index).toString())?.let { add(it.copy(builtin = false)) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeUsers(profiles: List<SoundProfile>): String {
        val array = JSONArray()
        profiles.forEach { array.put(JSONObject(encodeProfile(it))) }
        return array.toString()
    }

    private fun encodeProfile(profile: SoundProfile): String {
        return JSONObject()
            .put("id", profile.id)
            .put("name", profile.name)
            .put("description", profile.description)
            .put("curve", JSONObject(encodeCurve(profile.curve)))
            .put("lowering", encodeLowering(profile.lowering))
            .put("adaptive", encodeAdaptive(profile.adaptive))
            .put("gate", encodeGate(profile.gate))
            .put("clarity", encodeClarity(profile.clarity))
            .put("limiter", profile.limiterDb)
            .toString()
    }

    private fun decodeProfile(raw: String?): SoundProfile? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val id = json.optString("id")
            SoundProfile(
                id = id,
                name = json.optString("name"),
                description = json.optString("description"),
                curve = decodeCurve(json.getJSONObject("curve")) ?: EqCurves.mild(),
                lowering = decodeLowering(json.optJSONObject("lowering")),
                adaptive = decodeAdaptive(json),
                gate = decodeGate(json.optJSONObject("gate")),
                clarity = decodeClarity(json.optJSONObject("clarity")),
                limiterDb = json.optInt("limiter", OutputLevel.DEFAULT_DB)
                    .coerceIn(OutputLevel.MIN_DB, OutputLevel.MAX_DB),
                builtin = SoundProfiles.byId(id) != null,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeCurve(curve: EqCurve): String {
        val tones = JSONArray()
        curve.tones.forEach { band ->
            tones.put(
                JSONObject()
                    .put("shape", band.shape.name)
                    .put("hz", band.frequencyHz)
                    .put("q", band.q.toDouble())
                    .put("db", band.gainDb),
            )
        }
        return JSONObject()
            .put("graphic", JSONArray(curve.graphic))
            .put("tones", tones)
            .toString()
    }

    private fun decodeCurve(json: JSONObject?): EqCurve? {
        if (json == null) return null
        val graphicJson = json.getJSONArray("graphic")
        val graphic = List(EqCurve.BAND_COUNT) { index ->
            if (index < graphicJson.length()) graphicJson.getInt(index).coerceIn(EqCurve.MIN_DB, EqCurve.MAX_DB) else 0
        }
        val tonesJson = json.optJSONArray("tones")
        val tones = List(EqCurve.TONE_COUNT) { index ->
            if (tonesJson == null || index >= tonesJson.length()) {
                ToneBand()
            } else {
                val band = tonesJson.getJSONObject(index)
                ToneBand(
                    shape = runCatching { ToneShape.valueOf(band.getString("shape")) }.getOrDefault(ToneShape.Peak),
                    frequencyHz = band.optInt("hz", 1_000).coerceIn(100, 8_000),
                    q = band.optDouble("q", 1.0).toFloat().coerceIn(0.5f, 8f),
                    gainDb = band.optInt("db", 0).coerceIn(EqCurve.MIN_DB, EqCurve.MAX_DB),
                )
            }
        }
        return EqCurve(graphic, tones)
    }

    private fun encodeClarity(settings: ClaritySettings): JSONObject {
        return JSONObject()
            .put("enabled", settings.enabled)
            .put("strength", settings.strength)
            .put("threshold", settings.thresholdDb)
    }

    private fun decodeClarity(json: JSONObject?): ClaritySettings {
        if (json == null) return ClaritySettings()
        return ClaritySettings(
            enabled = json.optBoolean("enabled", false),
            strength = json.optInt("strength", ClaritySettings.DEFAULT_STRENGTH)
                .coerceIn(ClaritySettings.MIN_STRENGTH, ClaritySettings.MAX_STRENGTH),
            thresholdDb = json.optInt("threshold", ClaritySettings.DEFAULT_THRESHOLD)
                .coerceIn(ClaritySettings.MIN_THRESHOLD, ClaritySettings.MAX_THRESHOLD),
        )
    }

    private fun encodeGate(settings: NoiseGateSettings): JSONObject {
        return JSONObject()
            .put("enabled", settings.enabled)
            .put("threshold", settings.thresholdDb)
            .put("depth", settings.depthDb)
            .put("attack", settings.attackMs)
            .put("hold", settings.holdMs)
            .put("release", settings.releaseMs)
    }

    private fun decodeGate(json: JSONObject?): NoiseGateSettings {
        if (json == null) return NoiseGateSettings()
        return NoiseGateSettings(
            enabled = json.optBoolean("enabled", false),
            thresholdDb = json.optInt("threshold", NoiseGateSettings.DEFAULT_THRESHOLD)
                .coerceIn(NoiseGateSettings.MIN_THRESHOLD, NoiseGateSettings.MAX_THRESHOLD),
            depthDb = json.optInt("depth", NoiseGateSettings.DEFAULT_DEPTH)
                .coerceIn(NoiseGateSettings.MIN_DEPTH, NoiseGateSettings.MAX_DEPTH),
            attackMs = json.optInt("attack", NoiseGateSettings.DEFAULT_ATTACK)
                .coerceIn(NoiseGateSettings.MIN_ATTACK, NoiseGateSettings.MAX_ATTACK),
            holdMs = json.optInt("hold", NoiseGateSettings.DEFAULT_HOLD)
                .coerceIn(NoiseGateSettings.MIN_HOLD, NoiseGateSettings.MAX_HOLD),
            releaseMs = json.optInt("release", NoiseGateSettings.DEFAULT_RELEASE)
                .coerceIn(NoiseGateSettings.MIN_RELEASE, NoiseGateSettings.MAX_RELEASE),
        )
    }

    private fun encodeAdaptive(settings: AdaptiveSettings): JSONObject {
        return JSONObject()
            .put("enabled", settings.enabled)
            .put("strength", settings.strengthDb)
            .put("caution", settings.caution)
    }

    private fun decodeAdaptive(json: JSONObject): AdaptiveSettings {
        val raw = json.opt("adaptive")
        if (raw is Boolean) return AdaptiveSettings(enabled = raw)
        val objectValue = raw as? JSONObject ?: return AdaptiveSettings()
        return AdaptiveSettings(
            enabled = objectValue.optBoolean("enabled", false),
            strengthDb = objectValue.optInt("strength", AdaptiveSettings.DEFAULT_STRENGTH)
                .coerceIn(AdaptiveSettings.MIN_STRENGTH, AdaptiveSettings.MAX_STRENGTH),
            caution = objectValue.optInt("caution", AdaptiveSettings.DEFAULT_CAUTION)
                .coerceIn(AdaptiveSettings.MIN_CAUTION, AdaptiveSettings.MAX_CAUTION),
        )
    }

    private fun encodeLowering(settings: LoweringSettings): JSONObject {
        return JSONObject()
            .put("enabled", settings.enabled)
            .put("method", settings.method.name)
            .put("cutoff", settings.cutoffHz)
            .put("ratio", settings.ratio.toDouble())
            .put("shift", settings.shiftHz)
            .put("mix", settings.mix.toDouble())
    }

    private fun decodeLowering(json: JSONObject?): LoweringSettings {
        if (json == null) return LoweringSettings()
        val method = runCatching {
            LoweringMethod.valueOf(json.optString("method", LoweringMethod.Compression.name))
        }.getOrDefault(LoweringMethod.Compression)
        return LoweringSettings(
            enabled = json.optBoolean("enabled", false),
            method = method,
            cutoffHz = json.optInt("cutoff", 2_500).coerceIn(LoweringStore.MIN_CUTOFF, LoweringStore.MAX_CUTOFF),
            ratio = json.optDouble("ratio", 2.0).toFloat().coerceIn(LoweringStore.MIN_RATIO, LoweringStore.MAX_RATIO),
            shiftHz = json.optInt("shift", 1_000).coerceIn(LoweringStore.MIN_SHIFT, LoweringStore.MAX_SHIFT),
            mix = json.optDouble("mix", 1.0).toFloat().coerceIn(0f, 1f),
        )
    }

    private companion object {
        const val FILE = "soniboost"
        const val ACTIVE = "sound_profile"
        const val ACTIVE_ID = "sound_profile_id"
        const val ORIGIN = "sound_profile_origin"
        const val USERS = "sound_profiles"
    }
}
