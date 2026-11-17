package com.soniboost.audio

import android.content.Context

class MicrophoneChoiceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(): MicrophoneChoice {
        return if (preferences.getString(KEY, PHONE) == HEADSET) {
            MicrophoneChoice.Headset
        } else {
            MicrophoneChoice.Phone
        }
    }

    fun set(choice: MicrophoneChoice) {
        val value = if (choice == MicrophoneChoice.Headset) HEADSET else PHONE
        preferences.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val FILE = "soniboost"
        const val KEY = "microphone"
        const val PHONE = "phone"
        const val HEADSET = "headset"
    }
}
