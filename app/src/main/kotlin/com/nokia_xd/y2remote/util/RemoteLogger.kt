package com.nokia_xd.y2remote.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RemoteLogger {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _logs = MutableStateFlow<List<String>>(listOf("[--:--:--] [App] Ready. Select a device and tap Connect."))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val formatted = "[$timestamp] [$tag] $message"
        Log.d(tag, message)
        val current = _logs.value.toMutableList()
        if (current.size > 300) {
            current.removeAt(0)
        }
        current.add(formatted)
        _logs.value = current
    }

    fun clear() {
        _logs.value = listOf("[--:--:--] [Logs] Cleared.")
    }
}
