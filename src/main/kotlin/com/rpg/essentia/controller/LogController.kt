package com.rpg.essentia.controller

import com.rpg.essentia.model.LogEntry
import com.rpg.essentia.service.LogService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/log")
class LogController(private val logService: LogService) {

    @GetMapping
    fun getLog(): List<LogEntry> = logService.getLog()

    @DeleteMapping
    fun clearLog(): List<LogEntry> = logService.clearLog()
}
