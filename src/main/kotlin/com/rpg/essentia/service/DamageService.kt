package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class DamageService(
    private val playerRepository: PlayerRepository,
    private val skillRepository: SkillRepository,
    private val enemyService: EnemyService,
    private val bossService: BossService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun requestDamage(playerId: String, req: DamageRequestBody) {
        val player = playerRepository.findById(playerId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado")
        }
        val onHitEffects = req.skillId
            ?.let { skillRepository.findById(it).orElse(null) }
            ?.onHitEffects
            ?: emptyList()
        val approval = DamageApprovalRequest(
            requestId    = req.requestId,
            playerId     = playerId,
            playerName   = player.char.name,
            targetId     = req.targetId,
            targetType   = req.targetType,
            targetName   = req.targetName,
            damage       = req.damage,
            costs        = req.costs,
            onHitEffects = onHitEffects,
            slotId       = req.slotId,
            skillId      = req.skillId
        )
        broadcaster.broadcastDamageRequest(approval)
    }

    fun approveDamage(req: DamageApproveBody) {
        // Aplica modificadores passivos de dano causado/recebido (modify_damage_dealt / modify_damage_received)
        val attackerEffects = playerRepository.findById(req.playerId).orElse(null)?.statusEffects ?: emptyList()
        val targetEffects = when (req.targetType) {
            "enemy" -> enemyService.findInstance(req.targetId)?.statusEffects ?: emptyList()
            "boss"  -> bossService.findInstance(req.targetId)?.statusEffects ?: emptyList()
            else    -> emptyList()
        }
        val finalDamage = applyDamageModifiers(req.damage, attackerEffects, targetEffects)

        // Aplica dano no alvo
        when (req.targetType) {
            "enemy" -> enemyService.adjustHp(req.targetId, -finalDamage)
            "boss"  -> bossService.adjustHp(req.targetId, -finalDamage)
            else    -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de alvo inválido")
        }

        // Aplica status effects no alvo
        if (req.onHitEffects.isNotEmpty()) {
            when (req.targetType) {
                "enemy" -> enemyService.applyStatusEffects(req.targetId, req.onHitEffects)
                "boss"  -> bossService.applyStatusEffects(req.targetId, req.onHitEffects)
            }
        }

        // Debita custo do jogador e seta cooldown da habilidade (só agora que o dano foi aprovado)
        if (req.costs.isNotEmpty() || req.slotId != null) {
            val player = playerRepository.findById(req.playerId).orElse(null)
            if (player != null) {
                var updated = player
                req.costs["flow"]?.let { c ->
                    updated = updated.copy(flow = updated.flow.copy(current = (updated.flow.current - c).coerceAtLeast(0)))
                }
                req.costs["percentual_flow"]?.let { c ->
                    updated = updated.copy(flow = updated.flow.copy(current = (updated.flow.current - c).coerceAtLeast(0)))
                }
                req.costs["hp"]?.let { c ->
                    updated = updated.copy(hp = updated.hp.copy(current = (updated.hp.current - c).coerceAtLeast(0)))
                }
                req.costs["percentual_hp"]?.let { c ->
                    updated = updated.copy(hp = updated.hp.copy(current = (updated.hp.current - c).coerceAtLeast(0)))
                }
                req.costs["ether"]?.let { c ->
                    updated = updated.copy(ether = updated.ether.copy(current = (updated.ether.current - c).coerceAtLeast(0)))
                }
                req.costs["pressao"]?.let { c ->
                    updated.pressao?.let { p ->
                        updated = updated.copy(pressao = p.copy(current = (p.current - c).coerceAtLeast(0)))
                    }
                }
                if (req.slotId != null) {
                    val cooldownTurns = req.skillId?.let { skillRepository.findById(it).orElse(null)?.cooldownTurns } ?: 0
                    updated = updated.copy(slots = updated.slots.map { s ->
                        if (s.id == req.slotId) s.copy(cooldownRemaining = cooldownTurns) else s
                    })
                }
                val saved = playerRepository.save(updated)
                broadcaster.broadcastPlayer(saved)
            }
        }

        broadcaster.broadcastDamageResult(req.playerId, DamageResultNotification(req.requestId, true))
    }

    fun rejectDamage(requestId: String, playerId: String) {
        broadcaster.broadcastDamageResult(playerId, DamageResultNotification(requestId, false))
    }
}
