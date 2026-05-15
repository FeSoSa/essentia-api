package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.AllyRepository
import com.rpg.essentia.repository.AllyTemplateRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class AllyService(
    private val repository: AllyRepository,
    private val templateRepository: AllyTemplateRepository,
    private val broadcaster: WebSocketBroadcaster
) {

    /* ── Templates ───────────────────────────────────────────── */

    fun listTemplates(): List<AllyTemplate> = templateRepository.findAll()

    fun createTemplate(t: AllyTemplate): AllyTemplate =
        templateRepository.save(t.copy(id = UUID.randomUUID().toString()))

    fun updateTemplate(id: String, t: AllyTemplate): AllyTemplate {
        if (!templateRepository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Ally template not found")
        return templateRepository.save(t.copy(id = id))
    }

    fun deleteTemplate(id: String) {
        if (!templateRepository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Ally template not found")
        templateRepository.deleteById(id)
    }

    /* ── Combat instances ────────────────────────────────────── */

    fun listAllies(): List<CombatAlly> = repository.findAll()

    fun addAlly(req: AddAllyRequest): List<CombatAlly> {
        val ally = req.ally.copy(id = UUID.randomUUID().toString())
        repository.save(ally)
        return broadcastAndReturn()
    }

    fun adjustHp(id: String, delta: Int): List<CombatAlly> {
        val ally = load(id)
        val newHp = (ally.hpCurrent + delta).coerceIn(0, ally.hpMax)
        repository.save(ally.copy(hpCurrent = newHp))
        return broadcastAndReturn()
    }

    fun updateNotes(id: String, notes: String): List<CombatAlly> {
        val ally = load(id)
        repository.save(ally.copy(notes = notes))
        return broadcastAndReturn()
    }

    fun removeAlly(id: String): List<CombatAlly> {
        if (!repository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Ally not found")
        repository.deleteById(id)
        return broadcastAndReturn()
    }

    private fun load(id: String): CombatAlly =
        repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Ally not found")
        }

    private fun broadcastAndReturn(): List<CombatAlly> {
        val list = repository.findAll()
        broadcaster.broadcastCombatAllies(list)
        return list
    }
}
