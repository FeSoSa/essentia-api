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
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot not found")
        if (slot.cooldownRemaining > 0)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Skill is on cooldown (${slot.cooldownRemaining} turns remaining)"
            )
        val skillId = slot.skillId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot is empty")

        // 2. Load PlayerSkill and Skill
        val playerSkill = playerSkillRepository.findByPlayerIdAndSlotId(playerId, slot.id)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PlayerSkill not found for this slot")
        val skill = skillRepository.findById(skillId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found")
        }

        // 3. Compute effective attributes
        val essencias = essenciaRepository.findAll()
        val effectiveAttrs = attributeService.computeEffectiveAttributes(player, essencias)
        val attrMap = effectiveAttrs.toMap()

        val computed = playerSkill.maestria.computed

        // 4. Compute effective costs (after maestria cost reduction)
        val costMap = mutableMapOf<String, Int>()
        for (cost in skill.costs) {
            val effectiveCost = when (cost.type) {
                "flow", "ether", "hp", "charge" -> maxOf(0, (cost.value ?: 0) - computed.costReduction)
                "percentual_flow" -> (player.flow.max * (cost.percentual ?: 0)) / 100
                "percentual_hp" -> (player.hp.max * (cost.percentual ?: 0)) / 100
                else -> cost.value ?: 0
            }
            costMap[cost.type] = effectiveCost
        }

        // 5. Validate sufficient resources
        costMap["flow"]?.let { c ->
            if (player.flow.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient flow ($c required, ${player.flow.current} available)")
        }
        costMap["hp"]?.let { c ->
            if (player.hp.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient HP")
        }
        costMap["ether"]?.let { c ->
            if (!player.ether.unlocked || player.ether.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient ether")
        }
        costMap["percentual_flow"]?.let { c ->
            if (player.flow.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient flow")
        }
        costMap["percentual_hp"]?.let { c ->
            if (player.hp.current < c)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient HP")
        }

        // 6. Calculate damage
        val (totalDamage, displayFormula) = if (skill.damage != null) {
            val dmg = skill.damage
            var total = dmg.baseFixed + computed.damageFixedBonus
            dmg.attribute?.let { attr -> total += attrMap[attr] ?: 0 }
            dmg.baseDice?.let { dice -> total += rollDice(dice) }
            getMaestriaBonusDice(playerSkill.maestria.upgrades)?.let { total += rollDice(it) }
            total to dmg.formula
        } else {
            null to null
        }

        // 7. Deduct costs
        var updatedPlayer = player
        costMap["flow"]?.let { c ->
            updatedPlayer = updatedPlayer.copy(flow = updatedPlayer.flow.copy(current = (updatedPlayer.flow.current - c).coerceAtLeast(0)))
        }
        costMap["hp"]?.let { c ->
            updatedPlayer = updatedPlayer.copy(hp = updatedPlayer.hp.copy(current = (updatedPlayer.hp.current - c).coerceAtLeast(0)))
        }
        costMap["ether"]?.let { c ->
            updatedPlayer = updatedPlayer.copy(ether = updatedPlayer.ether.copy(current = (updatedPlayer.ether.current - c).coerceAtLeast(0)))
        }
        costMap["percentual_flow"]?.let { c ->
            updatedPlayer = updatedPlayer.copy(flow = updatedPlayer.flow.copy(current = (updatedPlayer.flow.current - c).coerceAtLeast(0)))
        }
        costMap["percentual_hp"]?.let { c ->
            updatedPlayer = updatedPlayer.copy(hp = updatedPlayer.hp.copy(current = (updatedPlayer.hp.current - c).coerceAtLeast(0)))
        }

        // 8. Set slot cooldown
        val newCooldown = maxOf(0, skill.cooldownTurns - computed.cooldownReduction)
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
        gameStateService.addLogEntry(playerId, logText)
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

    private fun getMaestriaBonusDice(upgrades: List<MaestriaUpgrade>): Dice? {
        val aumUpgrades = upgrades.filter { it.path == "aumento" }
        return when {
            aumUpgrades.any { it.level == 5 } -> Dice(1, "d6")
            aumUpgrades.any { it.level == 3 } -> Dice(1, "d4")
            else -> null
        }
    }

    private fun buildLogText(player: Player, skill: Skill, damage: Int?, costs: Map<String, Int>): String {
        val dmgPart = if (damage != null) " causou $damage de dano com" else " usou"
        val costPart = costs.entries.joinToString(", ") { (type, value) -> "$value $type" }
        val costStr = if (costPart.isNotEmpty()) " [custo: $costPart]" else ""
        return "${player.char.name}$dmgPart ${skill.name}$costStr"
    }
}
