package dev.bmg.edgepanel.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServiceState {
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning = _isServiceRunning.asStateFlow()

    fun setRunning(running: Boolean) {
        _isServiceRunning.value = running
    }
}
