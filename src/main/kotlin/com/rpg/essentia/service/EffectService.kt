package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class EffectService(
    private val playerRepository: PlayerRepository,
    private val enemyService: EnemyService,
    private val bossService: BossService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun requestEffect(playerId: String, req: EffectRequestBody) {
        val player = playerRepository.findById(playerId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado")
        }
        val approval = EffectApprovalRequest(
            requestId    = req.requestId,
            playerId     = playerId,
            playerName   = player.char.name,
            targets      = req.targets,
            onHitEffects = req.onHitEffects,
            skillId      = req.skillId
        )
        broadcaster.broadcastEffectRequest(approval)
    }

    fun approveEffect(req: EffectApproveBody) {
        req.targets.forEach { target ->
            when (target.targetType) {
                "enemy" -> enemyService.applyStatusEffects(target.targetId, req.onHitEffects)
                "boss"  -> bossService.applyStatusEffects(target.targetId, req.onHitEffects)
                else    -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de alvo inválido")
            }
        }
    }

    fun rejectEffect(requestId: String) {
        // Apenas descarta — sem efeito no estado do jogo
    }
}
