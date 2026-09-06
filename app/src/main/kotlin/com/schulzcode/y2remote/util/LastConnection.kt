package com.schulzcode.y2remote.util

import android.content.Context

object LastConnection {
    private const val PREFS = "y2remote_prefs"
    private const val KEY_LAST_ADDRESS = "last_connected_address"

    fun save(context: Context, address: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ADDRESS, address)
            .apply()
    }

    fun load(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ADDRESS, null)
}
