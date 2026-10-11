package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EffectPresetRepository
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Skill usada em aliados (cura, Escudo, buffs e condições com alvo ALIADOS).
 * O jogador escolhe quem recebe (até o limite de alvos da skill), o mestre aprova na Mesa e então os efeitos
 * entram em cada aliado; custo, recarga e uso de maestria caem em quem usou a skill.
 */
@Service
class AliadoEfeitoService(
    private val playerRepository: PlayerRepository,
    private val playerSkillRepository: PlayerSkillRepository,
    private val skillRepository: SkillRepository,
    private val essenciaRepository: EssenciaRepository,
    private val effectPresetRepository: EffectPresetRepository,
    private val attributeService: AttributeService,
    private val gameStateService: GameStateService,
    private val maestriaService: MaestriaService,
    private val broadcaster: WebSocketBroadcaster
) {
    /** Jogadores que podem receber (os ativos na campanha, incluindo quem usa a skill). */
    fun aliados(): List<AliadoResumo> =
        playerRepository.findAll().filter { it.active }.map { resumo(it) }

    fun solicitar(playerId: String, req: AllyEffectRequestBody) {
        val (player, skill, alvos) = validar(playerId, req.slotId, req.alvos)
        val presets = effectPresetRepository.findAll()
        val (cura, escudo, efeitos) = EfeitoRules.paraAliados(skill, presets)
        broadcaster.broadcastAllyEffectRequest(AllyEffectApprovalRequest(
            requestId = req.requestId, playerId = playerId, playerName = player.char.name,
            skillId = skill.id ?: "", skillName = skill.name, slotId = req.slotId,
            alvos = alvos.map { resumo(it) }, cura = cura, escudo = escudo, efeitos = efeitos,
            costs = custos(player, skill)
        ))
    }

    fun aprovar(req: AllyEffectApproveBody) {
        val (_, skill, alvos) = validar(req.playerId, req.slotId, req.alvos)
        val presets = effectPresetRepository.findAll()
        val essencias = essenciaRepository.findAll()
        val (cura, escudo, _) = EfeitoRules.paraAliados(skill, presets)

        // Efeitos em cada aliado (status com id próprio por aliado)
        for (alvo in alvos) {
            var p = playerRepository.findById(alvo.id).orElse(null) ?: continue
            p = EscudoRules.ganhar(EscudoRules.curar(p, cura), escudo)
            val efeitos = EfeitoRules.paraAliados(skill, presets).third
            if (efeitos.isNotEmpty()) {
                p = p.copy(statusEffects = p.statusEffects.applyOrStack(efeitos))
                p = attributeService.recalculateVitals(p, attributeService.computeEffectiveAttributes(p, essencias))
            }
            broadcaster.broadcastPlayer(playerRepository.save(p))
        }

        // Quem usou: custo, recarga e maestria (relê, porque pode ter sido um dos alvos)
        var caster = playerRepository.findById(req.playerId).orElseThrow()
        val custos = custos(caster, skill)
        custos["flow"]?.let { c -> caster = caster.copy(flow = caster.flow.copy(current = (caster.flow.current - c).coerceAtLeast(0))) }
        custos["percentual_flow"]?.let { c -> caster = caster.copy(flow = caster.flow.copy(current = (caster.flow.current - c).coerceAtLeast(0))) }
        custos["hp"]?.let { c -> caster = caster.copy(hp = caster.hp.copy(current = (caster.hp.current - c).coerceAtLeast(0))) }
        custos["percentual_hp"]?.let { c -> caster = caster.copy(hp = caster.hp.copy(current = (caster.hp.current - c).coerceAtLeast(0))) }
        custos["ether"]?.let { c -> caster = caster.copy(ether = caster.ether.copy(current = (caster.ether.current - c).coerceAtLeast(0))) }
        caster = caster.copy(slots = caster.slots.map { if (it.id == req.slotId) it.copy(cooldownRemaining = skill.cooldownTurns) else it })
        broadcaster.broadcastPlayer(playerRepository.save(caster))
        playerSkillRepository.findByPlayerIdAndSkillId(req.playerId, skill.id ?: "")?.let { playerSkillRepository.save(it.copy(used = true)) }
        maestriaService.registrarUsoSkill(req.playerId, skill.id ?: "")

        val partes = listOfNotNull(
            cura.takeIf { it > 0 }?.let { "cura $it" }, escudo.takeIf { it > 0 }?.let { "escudo $it" }
        ).joinToString(", ")
        gameStateService.addLogEntry(req.playerId, "${caster.char.name} usou ${skill.name} em ${alvos.joinToString(", ") { it.char.name }}" +
            (if (partes.isNotEmpty()) " ($partes)" else ""), "skill")
        broadcaster.broadcastDamageResult(req.playerId, DamageResultNotification(req.requestId, true))
    }

    fun rejeitar(requestId: String, playerId: String) =
        broadcaster.broadcastDamageResult(playerId, DamageResultNotification(requestId, false))

    /** Slot, skill de aliados, recarga, bloqueios, requisito e quantidade de alvos. */
    private fun validar(playerId: String, slotId: String, alvoIds: List<String>): Triple<Player, Skill, List<Player>> {
        val player = playerRepository.findById(playerId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado") }
        val slot = player.slots.firstOrNull { it.id == slotId } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot não encontrado")
        if (slot.cooldownRemaining > 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Habilidade em recarga")
        val skill = slot.skillId?.let { skillRepository.findById(it).orElse(null) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot vazio")
        if (skill.alvo != "ALIADOS" && skill.efeitos.none { it.alvo == Efeito.ALVO_ALIADOS })
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta habilidade não é usada em aliados")
        if (player.statusEffects.isSkillBlocked())
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Habilidades bloqueadas por efeito ativo")
        MaestriaRules.requisito(skill, player, essenciaRepository.findAll())?.let {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$it: habilidade bloqueada")
        }
        val max = skill.multiTarget?.maxTargets ?: 1
        val ids = alvoIds.distinct()
        if (ids.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha pelo menos um aliado")
        if (ids.size > max) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta habilidade atinge até $max aliado${if (max == 1) "" else "s"}")
        val alvos = playerRepository.findAllById(ids).filter { it.active }
        if (alvos.size != ids.size) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Aliado não encontrado ou fora da campanha")
        return Triple(player, skill, alvos)
    }

    /** Custos com a redução da maestria do grupo e dos efeitos ativos (mesma conta do uso de skill). */
    private fun custos(player: Player, skill: Skill): Map<String, Int> {
        val state = gameStateService.getOrCreate()
        val grupo = MaestriaRules.grupoDaSkill(skill, player, essenciaRepository.findAll())?.first
        val (_, custoPct) = MaestriaRules.bonus(player, grupo, state.maestriaConfig)
        return skill.costs.associate { c ->
            val base = when (c.type) {
                "percentual_flow" -> player.flow.max * (c.percentual ?: 0) / 100
                "percentual_hp"   -> player.hp.max * (c.percentual ?: 0) / 100
                else              -> c.value ?: 0
            }
            c.type to CombatRules.custoFinal(base, custoPct / 100.0 + player.statusEffects.resourceCostModifierPercent(c.type))
        }
    }

    private fun resumo(p: Player) = AliadoResumo(p.id, p.char.name, p.hp.current, p.hp.max, p.escudo, p.char.portraitUrl)
}
