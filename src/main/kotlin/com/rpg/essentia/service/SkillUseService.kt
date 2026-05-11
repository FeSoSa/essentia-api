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

        // 6. Calculate damage
        // dano_calculado = baseFixed + atributo + dados
        // dano_final     = dano_calculado × (1 + bonusDano)
        val (totalDamage, displayFormula) = if (skill.damage != null) {
            val dmg = skill.damage
            var danoCalculado = dmg.baseFixed
            dmg.attribute?.let { attr -> danoCalculado += attrMap[attr] ?: 0 }
            // Use player-provided dice roll when available; otherwise roll internally
            when {
                request.diceRoll != null -> danoCalculado += request.diceRoll
                dmg.baseDice != null     -> danoCalculado += rollDice(dmg.baseDice)
            }
            val danoFinal = if (computed.bonusDano > 0.0)
                Math.round(danoCalculado * (1.0 + computed.bonusDano)).toInt()
            else
                danoCalculado
            danoFinal to dmg.formula
        } else {
            null to null
        }

        // 7. Custo será debitado pelo mestre ao aprovar o dano (não debitar aqui)
        var updatedPlayer = player

        // 7b. Criar buff de atributo se a skill tiver buffAttributes
        if (skill.buffAttributes != null && !skill.buffAttributes.isEmpty()) {
            val duration = skill.buffDurationTurns ?: 1
            val buffEffect = StatusEffect(
                id             = UUID.randomUUID().toString(),
                name           = skill.name,
                desc           = "Bônus de atributos por ${if (duration == -1) "∞" else "$duration"} turno(s)",
                color          = "#4ade80",
                durationTurns  = duration,
                attributeBonus = skill.buffAttributes
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

    private fun buildLogText(player: Player, skill: Skill, damage: Int?, costs: Map<String, Int>): String {
        val dmgPart = if (damage != null) " causou $damage de dano com" else " usou"
        val costPart = costs.entries.joinToString(", ") { (type, value) -> "$value $type" }
        val costStr = if (costPart.isNotEmpty()) " [custo: $costPart]" else ""
        return "${player.char.name}$dmgPart ${skill.name}$costStr"
    }
}
