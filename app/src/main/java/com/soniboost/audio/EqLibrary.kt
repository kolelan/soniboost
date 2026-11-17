package com.soniboost.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class EqTemplate(
    val id: String,
    val name: String,
    val curve: EqCurve,
    val builtin: Boolean,
)

class EqLibrary(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun active(): EqCurve {
        return decodeCurve(preferences.getString(ACTIVE_CURVE, null)) ?: EqCurves.mild()
    }

    fun activeTemplateId(): String {
        return preferences.getString(ACTIVE_ID, EqCurves.SPEECH) ?: EqCurves.SPEECH
    }

    fun templates(): List<EqTemplate> {
        return listOf(
            EqTemplate(EqCurves.SPEECH, "", EqCurves.mild(), true),
            EqTemplate(EqCurves.STRONG, "", EqCurves.strong(), true),
            EqTemplate(EqCurves.FLAT, "", EqCurves.flat(), true),
        ) + users()
    }

    fun use(template: EqTemplate) {
        preferences.edit()
            .putString(ACTIVE_ID, template.id)
            .putString(ACTIVE_CURVE, encodeCurve(template.curve))
            .apply()
    }

    fun remember(curve: EqCurve) {
        preferences.edit()
            .putString(ACTIVE_ID, "")
            .putString(ACTIVE_CURVE, encodeCurve(curve))
            .apply()
    }

    fun save(name: String, curve: EqCurve): EqTemplate {
        val trimmed = name.trim()
        val current = users().toMutableList()
        val existing = current.indexOfFirst { it.name == trimmed }
        val template = if (existing >= 0) {
            current[existing].copy(curve = curve)
        } else {
            EqTemplate(id = "user-${System.currentTimeMillis()}", name = trimmed, curve = curve, builtin = false)
        }
        if (existing >= 0) current[existing] = template else current.add(template)
        preferences.edit()
            .putString(USERS, encodeUsers(current))
            .putString(ACTIVE_ID, template.id)
            .putString(ACTIVE_CURVE, encodeCurve(curve))
            .apply()
        return template
    }

    fun delete(id: String) {
        val current = users().filterNot { it.id == id }
        val editor = preferences.edit().putString(USERS, encodeUsers(current))
        if (activeTemplateId() == id) {
            editor.putString(ACTIVE_ID, EqCurves.FLAT).putString(ACTIVE_CURVE, encodeCurve(EqCurves.flat()))
        }
        editor.apply()
    }

    private fun users(): List<EqTemplate> {
        val raw = preferences.getString(USERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val curve = decodeCurve(item.getString("curve")) ?: continue
                    add(
                        EqTemplate(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            curve = curve,
                            builtin = false,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeUsers(templates: List<EqTemplate>): String {
        val array = JSONArray()
        templates.forEach { template ->
            array.put(
                JSONObject()
                    .put("id", template.id)
                    .put("name", template.name)
                    .put("curve", encodeCurve(template.curve)),
            )
        }
        return array.toString()
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

    private fun decodeCurve(raw: String?): EqCurve? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
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
            EqCurve(graphic, tones)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val FILE = "soniboost"
        const val ACTIVE_CURVE = "eq_curve"
        const val ACTIVE_ID = "eq_template"
        const val USERS = "eq_templates"
    }
}
