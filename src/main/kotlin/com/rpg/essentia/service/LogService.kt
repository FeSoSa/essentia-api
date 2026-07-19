package com.rpg.essentia.service

import com.rpg.essentia.model.LogEntry
import org.springframework.stereotype.Service

@Service
class LogService(private val gameStateService: GameStateService) {
    fun getLog(): List<LogEntry> = gameStateService.getOrCreate().log
    fun clearLog(): List<LogEntry> = gameStateService.clearLog().log
}
