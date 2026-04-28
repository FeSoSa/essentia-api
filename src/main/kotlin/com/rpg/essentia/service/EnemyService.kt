package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.*
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class EnemyService(
    private val templateRepository: EnemyTemplateRepository,
    private val instanceRepository: EnemyInstanceRepository,
    private val playerRepository: PlayerRepository,
    private val broadcaster: WebSocketBroadcaster
) {

    /* ── Catalog ─────────────────────────────────────────────── */

    fun listTemplates(): List<EnemyTemplate> = templateRepository.findAll()

    fun createTemplate(template: EnemyTemplate): EnemyTemplate =
        templateRepository.save(template.copy(id = UUID.randomUUID().toString()))

    fun updateTemplate(id: String, template: EnemyTemplate): EnemyTemplate {
        if (!templateRepository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Enemy template not found")
        return templateRepository.save(template.copy(id = id))
    }

    fun deleteTemplate(id: String) {
        if (!templateRepository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Enemy template not found")
        templateRepository.deleteById(id)
    }

    /* ── Combat instances ────────────────────────────────────── */

    fun listInstances(): List<EnemyInstance> = instanceRepository.findAll()

    fun addToCombat(req: AddEnemyRequest): List<EnemyInstance> {
        val instance = req.enemy.copy(instanceId = UUID.randomUUID().toString())
        instanceRepository.save(instance)
        return broadcastAndReturn()
    }

    fun adjustHp(instanceId: String, delta: Int): List<EnemyInstance> {
        val enemy = load(instanceId)
        val newHp = (enemy.hpCurrent + delta).coerceIn(0, enemy.hpMax)
        instanceRepository.save(enemy.copy(hpCurrent = newHp))
        return broadcastAndReturn()
    }

    fun updateNotes(instanceId: String, notes: String): List<EnemyInstance> {
        val enemy = load(instanceId)
        instanceRepository.save(enemy.copy(notes = notes))
        return broadcastAndReturn()
    }

    fun defeat(instanceId: String, req: EnemyDefeatRequest): List<EnemyInstance> {
        val enemy = load(instanceId)

        req.drops.forEach { drop ->
            if (drop.playerId.isNotBlank()) {
                playerRepository.findById(drop.playerId).ifPresent { player ->
                    val item = Item(
                        id = UUID.randomUUID().toString(),
                        name = drop.itemName,
                        desc = "Drop de ${enemy.name}",
                        qty = 1,
                        type = "loot",
                        icon = drop.icon
                    )
                    playerRepository.save(player.copy(items = player.items + item))
                }
            }
        }

        if (req.distributeXp && enemy.xp > 0) {
            playerRepository.findAll().forEach { player ->
                playerRepository.save(
                    player.copy(exp = player.exp.copy(
                        available = player.exp.available + enemy.xp,
                        total = player.exp.total + enemy.xp
                    ))
                )
            }
        }

        instanceRepository.deleteById(instanceId)
        return broadcastAndReturn()
    }

    private fun load(instanceId: String): EnemyInstance =
        instanceRepository.findById(instanceId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Enemy instance not found")
        }

    private fun broadcastAndReturn(): List<EnemyInstance> {
        val list = instanceRepository.findAll()
        broadcaster.broadcastCombatEnemies(list)
        return list
    }
}
