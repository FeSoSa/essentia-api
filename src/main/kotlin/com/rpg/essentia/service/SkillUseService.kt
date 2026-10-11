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
import java.util.UUID
import kotlin.random.Random

@Service
class SkillUseService(
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
    fun useSkill(playerId: String, request: UseSkillRequest): DamageResult {
        val player = playerRepository.findById(playerId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found")
        }

        // 1. Find and validate slot
        val slot = player.slots.firstOrNull { it.id == request.slotId }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot não encontrado")
        if (slot.cooldownRemaining > 0)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Habilidade em cooldown (${slot.cooldownRemaining} turno${if (slot.cooldownRemaining > 1) "s" else ""} restante${if (slot.cooldownRemaining > 1) "s" else ""})"
            )
        val skillId = slot.skillId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot vazio")

        // 2. Load PlayerSkill and Skill — fallback to skillId lookup for skills equipped before slotId sync was added
        val playerSkill = playerSkillRepository.findByPlayerIdAndSlotId(playerId, slot.id)
            ?: playerSkillRepository.findByPlayerIdAndSkillId(playerId, skillId)?.also { ps ->
                playerSkillRepository.save(ps.copy(slotId = slot.id, equipped = true))
            }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PlayerSkill not found for this slot")
        val skill = skillRepository.findById(skillId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found")
        }

        if (player.statusEffects.isSkillBlocked())
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Habilidades bloqueadas por efeito ativo")

        if (playerSkill.blocked)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta habilidade foi bloqueada pelo mestre")

        ProficienciaRules.categoriaSemProficiencia(skill, player.equipment, player.proficiencias)?.let { cat ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Sem proficiência em $cat: não pode usar esta habilidade")
        }

        // 3. Compute effective attributes
        val essencias = essenciaRepository.findAll()

        // Requisito por origem e tier: a skill fica na ficha, mas bloqueada para uso
        MaestriaRules.requisito(skill, player, essencias)?.let {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$it: habilidade bloqueada")
        }
        val effectiveAttrs = attributeService.computeEffectiveAttributes(player, essencias)
        val attrMap = effectiveAttrs.toMap()

        val state = gameStateService.getOrCreate()
        val combate = state.combateConfig
        // Maestria do grupo da skill (arma ou Essência): +% dano e -% custo para todas as skills do grupo
        val grupo = MaestriaRules.grupoDaSkill(skill, player, essencias)?.first
        val (danoPct, custoPct) = MaestriaRules.bonus(player, grupo, state.maestriaConfig)
        val bonusDano = danoPct / 100.0

        // 4. Compute effective costs: custo_final = floor(custo × (1 - reducaoMaestria - reducaoEfeitoAtivo))
        val costMap = mutableMapOf<String, Int>()
        for (cost in skill.costs) {
            val base = when (cost.type) {
                "flow", "ether", "hp", "charge" -> cost.value ?: 0
                "percentual_flow" -> (player.flow.max * (cost.percentual ?: 0)) / 100
                "percentual_hp"   -> (player.hp.max  * (cost.percentual ?: 0)) / 100
                else              -> cost.value ?: 0
            }
            val reducao = custoPct / 100.0 + player.statusEffects.resourceCostModifierPercent(cost.type)
            costMap[cost.type] = CombatRules.custoFinal(base, reducao)
        }

        // 5. Validate sufficient resources
        costMap["flow"]?.let { c ->
            if (player.flow.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Fluxo insuficiente ($c requerido, ${player.flow.current} disponível)")
        }
        costMap["hp"]?.let { c ->
            if (player.hp.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "HP insuficiente ($c requerido)")
        }
        costMap["ether"]?.let { c ->
            if (!player.ether.unlocked || player.ether.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Éter insuficiente ($c requerido)")
        }
        costMap["percentual_flow"]?.let { c ->
            if (player.flow.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Fluxo insuficiente ($c requerido, ${player.flow.current} disponível)")
        }
        costMap["percentual_hp"]?.let { c ->
            if (player.hp.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "HP insuficiente ($c requerido)")
        }
        // Pressão: validar custo mínimo (se definido) OU verificar que há pelo menos 1 ponto para pressaoDice
        val pressaoCurrent = player.pressao?.current ?: 0
        costMap["pressao"]?.let { c ->
            if (pressaoCurrent < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Pressão insuficiente ($c requerida, $pressaoCurrent disponível)")
        }
        if (skill.pressaoDice && pressaoCurrent == 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Sem Pressão acumulada para usar esta técnica")
        }

        // 6. Calculate damage
        // dano = floor(dano_base + (d20 × mod_atributo) / equilibrio), equilíbrio decimal vindo do tier
        val equilibrioSkill = skill.equilibrio(combate)
        val (totalDamage, displayFormula) = if (skill.damageSource == "weapon") {
            val weapon = when (skill.weaponSlot) {
                "offHand" -> player.equipment.offHand
                "mainHand" -> player.equipment.mainHand
                else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Habilidade sem mão de arma configurada")
            } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nenhuma arma equipada em ${skill.weaponSlot}")

            val modAtributo = CombatRules.resolveAtributo(weapon.damageAttribute.ifBlank { null }, attrMap)
            val equilibrio = equilibrioSkill ?: ProficienciaRules.equilibrioAtaqueBasico(weapon, player.proficiencias, combate)
            val d20 = request.diceRoll ?: 0
            // Somas antes dos multiplicadores; floor só no final
            var bruto = CombatRules.danoBruto(weapon.damageBase, d20, modAtributo, equilibrio, request.critico, combate)
            skill.weaponDamageModifiers.forEach { mod ->
                when (mod.type) {
                    "flat" -> bruto += (mod.value ?: 0)
                    "extra_dice" -> mod.dice?.let { bruto += rollDice(it) }
                }
            }
            if (skill.pressaoDice && pressaoCurrent > 0) {
                repeat(pressaoCurrent) { bruto += rollDice(Dice(1, "d6")) }
            }
            val pctArma = skill.weaponDamageModifiers
                .filter { it.type == "percent" }
                .sumOf { (it.percentual ?: 0) / 100.0 }
            val danoCalculado = CombatRules.danoFinal(bruto, pctArma, bonusDano)

            applyDamageModifiers(danoCalculado, player.statusEffects, emptyList()) to "${weapon.name} (${skill.weaponSlot})"
        } else if (skill.damage != null) {
            val dmg = skill.damage
            val danoFinal = if (equilibrioSkill != null && !dmg.atributo.isNullOrBlank()) {
                val d20 = request.diceRoll ?: 0
                val modAtributo = CombatRules.resolveAtributo(dmg.atributo, attrMap)
                var bruto = CombatRules.danoBruto(dmg.baseFixed, d20, modAtributo, equilibrioSkill, request.critico, combate)
                if (skill.pressaoDice && pressaoCurrent > 0) {
                    repeat(pressaoCurrent) { bruto += rollDice(Dice(1, "d6")) }
                }
                CombatRules.danoFinal(bruto, bonusDano)
            } else {
                var bruto = dmg.baseFixed * (if (request.critico) combate.critMultBase else 1.0)
                if (skill.pressaoDice && pressaoCurrent > 0) {
                    repeat(pressaoCurrent) { bruto += rollDice(Dice(1, "d6")) }
                }
                CombatRules.danoFinal(bruto, bonusDano)
            }
            applyDamageModifiers(danoFinal, player.statusEffects, emptyList()) to
                TierRules.formula(dmg.baseFixed, dmg.atributo, equilibrioSkill)
        } else {
            null to null
        }

        // 7. Todos os custos (incluindo Pressão) são debitados na aprovação ou no erro
        var updatedPlayer = player

        // pressaoDice: inclui a pressão total no costMap para ser debitada ao aprovar/errar
        if (skill.pressaoDice && pressaoCurrent > 0) {
            costMap["pressao"] = pressaoCurrent
        }

        // 7b. Efeitos SELF com duração em rodadas (modificadores e condições da lista `efeitos`)
        val noUsuario = EfeitoRules.noUsuario(skill, effectPresetRepository.findAll())
        if (noUsuario.isNotEmpty()) {
            updatedPlayer = updatedPlayer.copy(
                statusEffects = updatedPlayer.statusEffects.applyOrStack(noUsuario)
            )
        }

        // 7b2. Escudo no próprio usuário (efeito ESCUDO com alvo SELF): soma até o teto
        EfeitoRules.escudoNoUsuario(skill).takeIf { it > 0 }?.let { updatedPlayer = EscudoRules.ganhar(updatedPlayer, it) }
        // 7b3. Cura instantânea no próprio usuário (CURA sem duração): até o PV máximo, nunca vira Escudo
        EfeitoRules.curaNoUsuario(skill).takeIf { it > 0 }?.let { updatedPlayer = EscudoRules.curar(updatedPlayer, it) }

        // 7c. Recalcula effectiveAttributes/vitais para refletir os status effects recém-aplicados;
        // sem isso o efeito fica salvo mas não aparece no personagem até outro fluxo forçar o recálculo.
        if (noUsuario.isNotEmpty()) {
            val updatedEffective = attributeService.computeEffectiveAttributes(updatedPlayer, essencias)
            updatedPlayer = attributeService.recalculateVitals(updatedPlayer, updatedEffective)
        }

        // 8. Set slot cooldown — só no commit real (uso direto sem combate/dano).
        // Quando há alvo e a técnica passa por acerto/dano, o cooldown só é setado
        // na aprovação do mestre (DamageService.approveDamage) ou ao errar (PlayerService.skillMiss).
        val newCooldown = skill.cooldownTurns
        if (request.commit) {
            val newSlots = updatedPlayer.slots.map { s ->
                if (s.id == slot.id) s.copy(cooldownRemaining = newCooldown) else s
            }
            updatedPlayer = updatedPlayer.copy(slots = newSlots)

            // 9. Mark skill as used; uso direto (sem alvo) em combate conta para a maestria
            playerSkillRepository.save(playerSkill.copy(used = true))
            maestriaService.registrarUsoSkill(playerId, skillId)
        }

        // 10. Save, log, broadcast
        val savedPlayer = playerRepository.save(updatedPlayer)

        if (request.commit) {
            val logText = buildLogText(player, skill, totalDamage, costMap)
            gameStateService.addLogEntry(playerId, logText, "skill")
        }
        broadcaster.broadcastPlayer(savedPlayer)

        return DamageResult(
            skillName = skill.name,
            damage = totalDamage,
            costPaid = costMap.map { (type, value) -> Cost(type = type, value = value, percentual = null) },
            cooldownSet = newCooldown
        )
    }

    private fun rollDice(dice: Dice): Int {
        val sides = dice.die.removePrefix("d").toIntOrNull() ?: 6
        return (1..dice.quantity).sumOf { Random.nextInt(1, sides + 1) }
    }

    private fun buildLogText(player: Player, skill: Skill, damage: Int?, costs: Map<String, Int>): String {
        val dmgPart = if (damage != null) " causou $damage de dano com" else " usou"
        val costPart = costs.entries.joinToString(", ") { (type, value) -> "$value $type" }
        val costStr = if (costPart.isNotEmpty()) " [custo: $costPart]" else ""
        return "${player.char.name}$dmgPart ${skill.name}$costStr"
    }
}
