package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.*
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class BossService(
    private val templateRepository: BossTemplateRepository,
    private val instanceRepository: BossInstanceRepository,
    private val playerRepository: PlayerRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster,
    private val masterService: MasterService
) {

    /* ── Catalog ─────────────────────────────────────────────── */

    fun listTemplates(): List<BossTemplate> = templateRepository.findAll()

    fun createTemplate(template: BossTemplate): BossTemplate =
        templateRepository.save(template.copy(id = UUID.randomUUID().toString()))

    fun updateTemplate(id: String, template: BossTemplate): BossTemplate {
        if (!templateRepository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Boss template not found")
        return templateRepository.save(template.copy(id = id))
    }

    fun deleteTemplate(id: String) {
        if (!templateRepository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Boss template not found")
        templateRepository.deleteById(id)
    }

    /* ── Combat instances ────────────────────────────────────── */

    fun listInstances(): List<BossInstance> = instanceRepository.findAll()

    fun addToCombat(req: AddBossRequest): List<BossInstance> {
        val firstPhaseHp = req.boss.phases.firstOrNull()?.hpMax ?: req.boss.hpCurrent
        val instance = req.boss.copy(
            instanceId = UUID.randomUUID().toString(),
            currentPhase = 0,
            hpCurrent = firstPhaseHp
        )
        instanceRepository.save(instance)
        return broadcastAndReturn()
    }

    fun adjustHp(instanceId: String, delta: Int): List<BossInstance> {
        val boss = load(instanceId)
        val phaseHpMax = boss.phases.getOrNull(boss.currentPhase)?.hpMax ?: boss.hpCurrent
        val newHp = (boss.hpCurrent + delta).coerceIn(0, phaseHpMax)
        instanceRepository.save(boss.copy(hpCurrent = newHp))
        return broadcastAndReturn()
    }

    fun updateNotes(instanceId: String, notes: String): List<BossInstance> {
        val boss = load(instanceId)
        instanceRepository.save(boss.copy(notes = notes))
        return broadcastAndReturn()
    }

    fun applyStatusEffects(instanceId: String, effects: List<StatusEffect>): List<BossInstance> {
        val boss = load(instanceId)
        val merged = boss.statusEffects + effects
        instanceRepository.save(boss.copy(statusEffects = merged))
        return broadcastAndReturn()
    }

    fun nextPhase(instanceId: String): List<BossInstance> {
        val boss = load(instanceId)
        val nextIndex = boss.currentPhase + 1
        if (nextIndex >= boss.phases.size)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Already on last phase")
        val nextPhaseHp = boss.phases[nextIndex].hpMax
        instanceRepository.save(boss.copy(currentPhase = nextIndex, hpCurrent = nextPhaseHp))
        return broadcastAndReturn()
    }

    fun defeat(instanceId: String, req: BossDefeatRequest): List<BossInstance> {
        val boss = load(instanceId)

        req.drops.forEach { drop ->
            if (drop.playerId.isNotBlank()) {
                playerRepository.findById(drop.playerId).ifPresent { player ->
                    val item = Item(
                        id = UUID.randomUUID().toString(),
                        name = drop.itemName,
                        desc = "Drop de ${boss.name}",
                        qty = 1,
                        type = "loot",
                        icon = drop.icon
                    )
                    playerRepository.save(player.copy(items = player.items + item))
                }
            }
        }

        req.specialReward?.let { reward ->
            if (reward.playerId.isNotBlank()) {
                playerRepository.findById(reward.playerId).ifPresent { player ->
                    boss.specialReward?.let { sr ->
                        val item = Item(
                            id = UUID.randomUUID().toString(),
                            name = sr.name,
                            desc = sr.desc,
                            qty = 1,
                            type = sr.type,
                            icon = when (sr.type) { "essencia" -> "✨"; "skill" -> "⚡"; else -> "🎁" }
                        )
                        playerRepository.save(player.copy(items = player.items + item))
                    }
                }
            }
        }

        val xpToGrant = req.xpAmount ?: boss.xp
        if (req.distributeXp && xpToGrant > 0) {
            playerRepository.findAll().filter { it.active }.forEach { player ->
                masterService.grantExp(player.id, xpToGrant)
            }
        }

        instanceRepository.deleteById(instanceId)
        return broadcastAndReturn()
    }

    private fun load(instanceId: String): BossInstance =
        instanceRepository.findById(instanceId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Boss instance not found")
        }

    private fun broadcastAndReturn(): List<BossInstance> {
        val list = instanceRepository.findAll()
        broadcaster.broadcastCombatBosses(list)
        return list
    }
}
