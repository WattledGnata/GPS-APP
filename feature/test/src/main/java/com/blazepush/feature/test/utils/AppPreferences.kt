package com.blazepush.feature.test.utils

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {
    private const val PREF_NAME = "race_chrono_prefs"
    private const val KEY_MOCK_MODE = "mock_mode"

    const val MOCK_MODE_REAL = 0
    const val MOCK_MODE_SIMPLE = 1
    const val MOCK_MODE_PROTOCOL = 2

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getMockMode(context: Context): Int {
        // Default to Protocol Mock (2) as per recent development
        return getPrefs(context).getInt(KEY_MOCK_MODE, MOCK_MODE_PROTOCOL)
    }

    fun setMockMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_MOCK_MODE, mode).apply()
    }
}
