package com.rpg.essentia.service

import com.rpg.essentia.model.*
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
    private val attributeService: AttributeService,
    private val gameStateService: GameStateService,
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

        // Toggle: ativar ou desativar sem fluxo de dano
        if (skill.toggle) {
            if (slot.toggleActive) {
                // Desativar: remove o efeito e inicia cooldown
                val newSlots = player.slots.map { s ->
                    if (s.id == slot.id) s.copy(toggleActive = false, cooldownRemaining = skill.cooldownTurns) else s
                }
                val newEffects = player.statusEffects.filterNot { it.sourceSkillId == skillId }
                val saved = playerRepository.save(player.copy(slots = newSlots, statusEffects = newEffects))
                gameStateService.addLogEntry(playerId, "desativou ${skill.name}", "skill")
                broadcaster.broadcastPlayer(saved)
                return DamageResult(skillName = skill.name, damage = null, costPaid = emptyList(), cooldownSet = skill.cooldownTurns)
            } else {
                // Ativar: valida e debita custo imediatamente, cria efeito permanente
                val essencias = essenciaRepository.findAll()
                attributeService.computeEffectiveAttributes(player, essencias)
                val computed = playerSkill.maestria.computed
                val netCostMod = computed.custoAumento - computed.reducaoCusto
                val costMap = mutableMapOf<String, Int>()
                for (cost in skill.costs) {
                    val base = when (cost.type) {
                        "flow", "ether", "hp" -> cost.value ?: 0
                        "percentual_flow"     -> (player.flow.max * (cost.percentual ?: 0)) / 100
                        "percentual_hp"       -> (player.hp.max  * (cost.percentual ?: 0)) / 100
                        else                  -> cost.value ?: 0
                    }
                    costMap[cost.type] = maxOf(0, Math.round(base * (1.0 + netCostMod)).toInt())
                }
                costMap["flow"]?.let { c ->
                    if (player.flow.current < c)
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Fluxo insuficiente ($c requerido, ${player.flow.current} disponível)")
                }
                costMap["percentual_flow"]?.let { c ->
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
                var withCosts = player
                costMap["flow"]?.let { c -> withCosts = withCosts.copy(flow = withCosts.flow.copy(current = (withCosts.flow.current - c).coerceAtLeast(0))) }
                costMap["percentual_flow"]?.let { c -> withCosts = withCosts.copy(flow = withCosts.flow.copy(current = (withCosts.flow.current - c).coerceAtLeast(0))) }
                costMap["hp"]?.let { c -> withCosts = withCosts.copy(hp = withCosts.hp.copy(current = (withCosts.hp.current - c).coerceAtLeast(0))) }
                costMap["ether"]?.let { c -> withCosts = withCosts.copy(ether = withCosts.ether.copy(current = (withCosts.ether.current - c).coerceAtLeast(0))) }
                val parts = mutableListOf<String>()
                if (!skill.buffAttributes.isNullOrEmpty()) parts.add("atributos")
                skill.hitBonus?.let    { parts.add("acerto ${if (it >= 0) "+$it" else "$it"}") }
                skill.attackBonus?.let { parts.add("ataque ${if (it >= 0) "+$it" else "$it"}") }
                skill.damageBonus?.let { parts.add("dano ${if (it >= 0) "+$it" else "$it"}")   }
                val toggleLimit = skill.buffDurationTurns ?: -1
                val buffEffect = StatusEffect(
                    id             = UUID.randomUUID().toString(),
                    name           = skill.name,
                    desc           = if (parts.isEmpty()) "ativo" else parts.joinToString(", "),
                    color          = "#f97316",
                    durationTurns  = toggleLimit,
                    attributeBonus = skill.buffAttributes,
                    hitBonus       = skill.hitBonus,
                    attackBonus    = skill.attackBonus,
                    damageBonus    = skill.damageBonus,
                    sourceSkillId  = skillId
                )
                val newSlots = withCosts.slots.map { s ->
                    if (s.id == slot.id) s.copy(toggleActive = true) else s
                }
                val saved = playerRepository.save(
                    withCosts.copy(slots = newSlots, statusEffects = withCosts.statusEffects + buffEffect)
                )
                gameStateService.addLogEntry(playerId, "ativou ${skill.name}", "skill")
                broadcaster.broadcastPlayer(saved)
                return DamageResult(
                    skillName  = skill.name,
                    damage     = null,
                    costPaid   = costMap.map { (type, value) -> Cost(type = type, value = value, percentual = null) },
                    cooldownSet = 0
                )
            }
        }

        // 3. Compute effective attributes
        val essencias = essenciaRepository.findAll()
        val effectiveAttrs = attributeService.computeEffectiveAttributes(player, essencias)
        val attrMap = effectiveAttrs.toMap()

        val computed = playerSkill.maestria.computed

        // 4. Compute effective costs (after maestria percentual modifiers)
        // custo_final = custo_base × (1 + custoAumento - reducaoCusto)
        val netCostMod = computed.custoAumento - computed.reducaoCusto
        val costMap = mutableMapOf<String, Int>()
        for (cost in skill.costs) {
            val base = when (cost.type) {
                "flow", "ether", "hp", "charge" -> cost.value ?: 0
                "percentual_flow" -> (player.flow.max * (cost.percentual ?: 0)) / 100
                "percentual_hp"   -> (player.hp.max  * (cost.percentual ?: 0)) / 100
                else              -> cost.value ?: 0
            }
            val effectiveCost = maxOf(0, Math.round(base * (1.0 + netCostMod)).toInt())
            costMap[cost.type] = effectiveCost
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
        // dano_final = dano_base + (d20 × mod_atributo) / equilibrio
        // When equilibrio is null: dano_final = dano_base only
        val (totalDamage, displayFormula) = if (skill.damage != null) {
            val dmg = skill.damage
            val danoFinal = if (dmg.equilibrio != null) {
                val d20 = request.diceRoll ?: 0
                val modAtributo = resolveAtributo(dmg.atributo, attrMap)
                var danoCalculado = dmg.baseFixed + (d20 * modAtributo) / dmg.equilibrio
                if (skill.pressaoDice && pressaoCurrent > 0) {
                    repeat(pressaoCurrent) { danoCalculado += rollDice(Dice(1, "d6")) }
                }
                if (computed.bonusDano > 0.0)
                    Math.round(danoCalculado * (1.0 + computed.bonusDano)).toInt()
                else
                    danoCalculado
            } else {
                var dano = dmg.baseFixed
                if (skill.pressaoDice && pressaoCurrent > 0) {
                    repeat(pressaoCurrent) { dano += rollDice(Dice(1, "d6")) }
                }
                dano
            }
            danoFinal to dmg.formula
        } else {
            null to null
        }

        // 7. Todos os custos (incluindo Pressão) são debitados na aprovação ou no erro
        var updatedPlayer = player

        // pressaoDice: inclui a pressão total no costMap para ser debitada ao aprovar/errar
        if (skill.pressaoDice && pressaoCurrent > 0) {
            costMap["pressao"] = pressaoCurrent
        }

        // 7b. Criar buff se a skill tiver buffAttributes ou bônus de combate
        val hasBuff = (!skill.buffAttributes.isNullOrEmpty()) ||
                      skill.hitBonus != null || skill.attackBonus != null || skill.damageBonus != null
        if (hasBuff) {
            val duration = skill.buffDurationTurns ?: 1
            val parts = mutableListOf<String>()
            if (!skill.buffAttributes.isNullOrEmpty()) parts.add("atributos")
            skill.hitBonus?.let    { parts.add("acerto ${if (it >= 0) "+$it" else "$it"}") }
            skill.attackBonus?.let { parts.add("ataque ${if (it >= 0) "+$it" else "$it"}") }
            skill.damageBonus?.let { parts.add("dano ${if (it >= 0) "+$it" else "$it"}") }
            val buffEffect = StatusEffect(
                id             = UUID.randomUUID().toString(),
                name           = skill.name,
                desc           = "${parts.joinToString(", ")} por ${if (duration == -1) "∞" else "$duration"} turno(s)",
                color          = "#4ade80",
                durationTurns  = duration,
                attributeBonus = skill.buffAttributes,
                hitBonus       = skill.hitBonus,
                attackBonus    = skill.attackBonus,
                damageBonus    = skill.damageBonus
            )
            updatedPlayer = updatedPlayer.copy(
                statusEffects = updatedPlayer.statusEffects + buffEffect
            )
        }

        // 8. Set slot cooldown
        val newCooldown = skill.cooldownTurns
        val newSlots = updatedPlayer.slots.map { s ->
            if (s.id == slot.id) s.copy(cooldownRemaining = newCooldown) else s
        }
        updatedPlayer = updatedPlayer.copy(slots = newSlots)

        // 9. Mark skill as used (maestria is NOT touched here — master updates it manually)
        val updatedPlayerSkill = playerSkill.copy(used = true)

        // 10. Save, log, broadcast
        playerSkillRepository.save(updatedPlayerSkill)
        val savedPlayer = playerRepository.save(updatedPlayer)

        val logText = buildLogText(player, skill, totalDamage, costMap)
        gameStateService.addLogEntry(playerId, logText, "skill")
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

    private fun getModifier(value: Int): Int = when {
        value <= 7  -> -1; value <= 11 -> 0;  value <= 15 -> 1;  value <= 19 -> 2
        value <= 23 -> 3;  value <= 27 -> 4;  value <= 31 -> 5;  value <= 35 -> 6
        value <= 40 -> 7;  value <= 46 -> 8;  value <= 53 -> 9;  value <= 61 -> 10
        else        -> 11
    }

    private val ABBREV_TO_KEY = mapOf(
        "FOR" to "strength", "AGI" to "agility", "INT" to "intelligence",
        "RES" to "resistance", "FLX" to "flow", "SAB" to "wisdom",
        "PRE" to "presence", "DEF" to "defense"
    )

    private fun resolveAtributo(atributo: String?, attrMap: Map<String, Int>): Int {
        if (atributo == null) return 0
        return atributo.split("/")
            .mapNotNull { abbrev -> ABBREV_TO_KEY[abbrev.trim()]?.let { attrMap[it] } }
            .maxOrNull()
            ?.let { getModifier(it) } ?: 0
    }

    private fun buildLogText(player: Player, skill: Skill, damage: Int?, costs: Map<String, Int>): String {
        val dmgPart = if (damage != null) " causou $damage de dano com" else " usou"
        val costPart = costs.entries.joinToString(", ") { (type, value) -> "$value $type" }
        val costStr = if (costPart.isNotEmpty()) " [custo: $costPart]" else ""
        return "${player.char.name}$dmgPart ${skill.name}$costStr"
    }
}
