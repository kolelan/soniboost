package com.soniboost.disclaimer

import android.content.Context

class DisclaimerStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isAccepted(): Boolean = preferences.getBoolean(KEY, false)

    fun accept() {
        preferences.edit().putBoolean(KEY, true).apply()
    }

    private companion object {
        const val FILE = "soniboost"
        const val KEY = "disclaimer_accepted"
    }
}
