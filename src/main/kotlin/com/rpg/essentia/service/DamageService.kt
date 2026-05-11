package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class DamageService(
    private val playerRepository: PlayerRepository,
    private val enemyService: EnemyService,
    private val bossService: BossService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun requestDamage(playerId: String, req: DamageRequestBody) {
        val player = playerRepository.findById(playerId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado")
        }
        val approval = DamageApprovalRequest(
            requestId   = req.requestId,
            playerId    = playerId,
            playerName  = player.char.name,
            targetId    = req.targetId,
            targetType  = req.targetType,
            targetName  = req.targetName,
            damage      = req.damage,
            costs       = req.costs
        )
        broadcaster.broadcastDamageRequest(approval)
    }

    fun approveDamage(req: DamageApproveBody) {
        // Aplica dano no alvo
        when (req.targetType) {
            "enemy" -> enemyService.adjustHp(req.targetId, -req.damage)
            "boss"  -> bossService.adjustHp(req.targetId, -req.damage)
            else    -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de alvo inválido")
        }

        // Debita custo do jogador
        if (req.costs.isNotEmpty()) {
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
