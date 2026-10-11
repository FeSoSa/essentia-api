package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EffectPresetRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class EffectService(
    private val playerRepository: PlayerRepository,
    private val skillRepository: SkillRepository,
    private val effectPresetRepository: EffectPresetRepository,
    private val enemyService: EnemyService,
    private val bossService: BossService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun requestEffect(playerId: String, req: EffectRequestBody) {
        val player = playerRepository.findById(playerId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado")
        }
        // Com skill: os efeitos no alvo vêm da lista `efeitos` da skill
        val onHitEffects = req.skillId
            ?.let { skillRepository.findById(it).orElse(null) }
            ?.let { EfeitoRules.noAlvo(it, effectPresetRepository.findAll()) }
            ?: req.onHitEffects
        val approval = EffectApprovalRequest(
            requestId    = req.requestId,
            playerId     = playerId,
            playerName   = player.char.name,
            targets      = req.targets,
            onHitEffects = onHitEffects,
            skillId      = req.skillId
        )
        broadcaster.broadcastEffectRequest(approval)
    }

    fun approveEffect(req: EffectApproveBody) {
        req.targets.forEach { target ->
            when (target.targetType) {
                "enemy" -> enemyService.applyStatusEffects(target.targetId, req.onHitEffects)
                "boss"  -> bossService.applyStatusEffects(target.targetId, req.onHitEffects)
                // Fogo amigo: o efeito vai para o jogador atingido
                "player" -> playerRepository.findById(target.targetId).ifPresent { alvo ->
                    broadcaster.broadcastPlayer(playerRepository.save(alvo.copy(statusEffects = alvo.statusEffects.applyOrStack(req.onHitEffects))))
                }
                "ally"  -> Unit   // aliado de combate não guarda efeitos de status
                else    -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de alvo inválido")
            }
        }
    }

    fun rejectEffect(requestId: String) {
        // Apenas descarta — sem efeito no estado do jogo
    }
}
