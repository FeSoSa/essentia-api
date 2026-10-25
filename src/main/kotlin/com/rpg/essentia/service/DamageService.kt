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
class DamageService(
    private val playerRepository: PlayerRepository,
    private val skillRepository: SkillRepository,
    private val effectPresetRepository: EffectPresetRepository,
    private val enemyService: EnemyService,
    private val bossService: BossService,
    private val playerService: PlayerService,
    private val allyService: AllyService,
    private val maestriaService: MaestriaService,
    private val gameStateService: GameStateService,
    private val degrauService: DegrauService,
    private val descobertaService: DescobertaService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun requestDamage(playerId: String, req: DamageRequestBody) {
        val player = playerRepository.findById(playerId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado")
        }
        val skill = req.skillId?.let { skillRepository.findById(it).orElse(null) }
        val onHitEffects = skill?.let { EfeitoRules.noAlvo(it, effectPresetRepository.findAll()) } ?: emptyList()
        // Prévia para o mestre; a aprovação recalcula com o estado do momento
        val degrau = degrauService.resolver(degrauService.tiposDoJogador(player, skill), req.targetType, req.targetId)
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
            skillId      = req.skillId,
            critico      = req.critico,
            danoSemCritico = req.danoSemCritico,
            ataqueBasico = req.ataqueBasico,
            degrau       = degrau
        )
        broadcaster.broadcastDamageRequest(approval)
    }

    /**
     * Skill em área: vira um pedido por alvo. Custo e recarga vão só no primeiro (o id dele é o do pedido,
     * que o app espera para fechar); os demais não cobram de novo nem contam maestria outra vez.
     */
    fun requestMultiDamage(playerId: String, req: MultiDamageRequestBody) {
        if (req.targets.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha ao menos um alvo")
        req.targets.forEachIndexed { i, t ->
            requestDamage(playerId, DamageRequestBody(
                requestId = if (i == 0) req.requestId else "${req.requestId}-$i",
                targetId = t.targetId, targetType = t.targetType, targetName = t.targetName, damage = t.damage,
                costs = if (i == 0) req.costs else emptyMap(),
                skillId = req.skillId,
                slotId = if (i == 0) req.slotId else null,
            ))
        }
    }

    fun approveDamage(req: DamageApproveBody) {
        // Aplica modificadores passivos de dano causado/recebido (modify_damage_dealt / modify_damage_received)
        val atacante = playerRepository.findById(req.playerId).orElse(null)
        val attackerEffects = atacante?.statusEffects ?: emptyList()
        val targetEffects = when (req.targetType) {
            "enemy" -> enemyService.findInstance(req.targetId)?.statusEffects ?: emptyList()
            "boss"  -> bossService.findInstance(req.targetId)?.statusEffects ?: emptyList()
            "player" -> playerRepository.findById(req.targetId).orElse(null)?.statusEffects ?: emptyList()
            else    -> emptyList()
        }
        // Negação de crítico do alvo (ex: Manto do Vento): checada antes de valer o crítico, 1x por rodada
        val criticoNegado = req.critico && negarCritico(req.targetType, req.targetId)
        val danoRolado = if (criticoNegado) (req.danoSemCritico ?: req.damage) else req.damage
        // Fraqueza/resistência do alvo entra por último, sobre o dano final
        val skill = req.skillId?.let { skillRepository.findById(it).orElse(null) }
        val degrau = atacante?.let { degrauService.resolver(degrauService.tiposDoJogador(it, skill), req.targetType, req.targetId) }
            ?: DegrauAplicado()
        val finalDamage = DegrauRules.aplicar(applyDamageModifiers(danoRolado, attackerEffects, targetEffects), degrau.degrau)
        // Antes do dano: se o golpe derrubar o alvo, a instância ainda existe para achar a criatura
        atacante?.let { descobertaService.registrar(it, req.targetType, req.targetId, degrau) }

        // Aplica dano no alvo
        when (req.targetType) {
            "enemy" -> enemyService.adjustHp(req.targetId, -finalDamage)
            "boss"  -> bossService.adjustHp(req.targetId, -finalDamage)
            // Fogo amigo: o jogador escolheu atingir um aliado (o escudo absorve primeiro)
            "player" -> playerService.adjustHp(req.targetId, -finalDamage)
            "ally"   -> allyService.adjustHp(req.targetId, -finalDamage)
            else    -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de alvo inválido")
        }

        // Aplica status effects no alvo
        if (req.onHitEffects.isNotEmpty()) {
            when (req.targetType) {
                "enemy" -> enemyService.applyStatusEffects(req.targetId, req.onHitEffects)
                "boss"  -> bossService.applyStatusEffects(req.targetId, req.onHitEffects)
                "player" -> playerRepository.findById(req.targetId).ifPresent { alvo ->
                    broadcaster.broadcastPlayer(playerRepository.save(alvo.copy(statusEffects = alvo.statusEffects.applyOrStack(req.onHitEffects))))
                }
            }
        }

        // Uso válido em combate conta para a maestria
        // Uso válido em combate conta para a maestria do grupo (skill) ou da arma (ataque básico)
        // Em área, só o primeiro pedido (o que leva custo ou slot) conta o uso
        if (req.slotId != null || req.costs.isNotEmpty()) req.skillId?.let { maestriaService.registrarUsoSkill(req.playerId, it) }
        if (req.ataqueBasico) maestriaService.registrarUsoArma(req.playerId)

        // Debita custo do jogador e seta cooldown da habilidade (só agora que o dano foi aprovado)
        val esRecuperada = if (req.ataqueBasico) gameStateService.combateConfig().esPorAcertoBasico else 0
        val primeiroPedido = req.slotId != null || req.costs.isNotEmpty()
        if (primeiroPedido || esRecuperada > 0) {
            val player = playerRepository.findById(req.playerId).orElse(null)
            if (player != null) {
                // Escudo e cura da própria skill só entram quando o golpe acerta (uma vez, mesmo em área)
                var updated = if (primeiroPedido && skill != null) EfeitoRules.escudoECuraNoUsuario(skill, player) else player
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
                if (esRecuperada > 0) {
                    updated = updated.copy(flow = updated.flow.copy(current = (updated.flow.current + esRecuperada).coerceAtMost(updated.flow.max)))
                }
                if (req.slotId != null) {
                    val cooldownTurns = skill?.cooldownTurns ?: 0
                    updated = updated.copy(slots = updated.slots.map { s ->
                        if (s.id == req.slotId) s.copy(cooldownRemaining = cooldownTurns) else s
                    })
                }
                val saved = playerRepository.save(updated)
                broadcaster.broadcastPlayer(saved)
            }
        }

        broadcaster.broadcastDamageResult(req.playerId, DamageResultNotification(req.requestId, true, criticoNegado, degrau, finalDamage))
    }

    /** Consome a negação de crítico do alvo nesta rodada. Retorna true se o crítico foi negado. */
    private fun negarCritico(targetType: String, targetId: String): Boolean {
        val rodada = gameStateService.rodadaAtual()
        return when (targetType) {
            "enemy" -> enemyService.findInstance(targetId)
                ?.takeIf { it.negaCritico && it.negaCriticoRodada != rodada }
                ?.let { enemyService.saveInstance(it.copy(negaCriticoRodada = rodada)); true } ?: false
            "boss" -> bossService.findInstance(targetId)
                ?.takeIf { it.negaCritico && it.negaCriticoRodada != rodada }
                ?.let { bossService.saveInstance(it.copy(negaCriticoRodada = rodada)); true } ?: false
            else -> false
        }
    }

    fun rejectDamage(requestId: String, playerId: String) {
        broadcaster.broadcastDamageResult(playerId, DamageResultNotification(requestId, false))
    }
}
