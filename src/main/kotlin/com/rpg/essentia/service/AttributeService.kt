package com.rpg.essentia.service

import com.rpg.essentia.model.Attributes
import com.rpg.essentia.model.Essencia
import com.rpg.essentia.model.Player
import com.rpg.essentia.repository.SkillRepository
import org.springframework.stereotype.Service

const val HP_BASE = 20
const val HP_PER_RESISTANCE = 5
const val FLOW_BASE = 20
const val FLOW_PER_FLOW_ATTR = 5

@Service
class AttributeService(private val skillRepository: SkillRepository) {

    fun computeEffectiveAttributes(player: Player, essencias: List<Essencia>): Attributes {
        val base = player.attributes.toMap().toMutableMap()

        // Equipment bonuses
        val eq = player.equipment
        listOfNotNull(
            eq.armor?.attributeBonus,
            eq.amulet?.attributeBonus,
            eq.ring?.attributeBonus,
            eq.utility?.attributeBonus
        ).forEach { bonus -> bonus.forEach { (k, v) -> base.merge(k, v, Int::plus) } }

        // Essencia bonuses
        val essenciaMap = essencias.associateBy { it.id }
        player.essenciasObtidas
            .filter { it.attributeBonusActive }
            .forEach { obtained ->
                essenciaMap[obtained.essenciaId]?.attributeBonus
                    ?.forEach { (k, v) -> base.merge(k, v, Int::plus) }
            }

        // Passive skill bonuses (skills equipadas com passiveAttributes)
        val equippedIds = player.slots.mapNotNull { it.skillId }
        if (equippedIds.isNotEmpty()) {
            skillRepository.findAllById(equippedIds)
                .filter { it.passiveAttributes != null }
                .forEach { skill ->
                    skill.passiveAttributes!!.forEach { (k, v) -> base.merge(k, v, Int::plus) }
                }
        }

        // Status effect attribute bonuses
        player.statusEffects
            .filter { it.durationTurns != 0 }
            .mapNotNull { it.attributeBonus }
            .forEach { bonus -> bonus.forEach { (k, v) -> base.merge(k, v, Int::plus) } }

        return base.toAttributes()
    }

    fun recalculateVitals(player: Player, effective: Attributes): Player {
        val newHpMax   = HP_BASE + effective.resistance * HP_PER_RESISTANCE
        val newFlowMax = FLOW_BASE + effective.flow * FLOW_PER_FLOW_ATTR
        return player.copy(
            hp   = player.hp.copy(max = newHpMax,   current = minOf(player.hp.current,   newHpMax)),
            flow = player.flow.copy(max = newFlowMax, current = minOf(player.flow.current, newFlowMax)),
            effectiveAttributes = effective
        )
    }
}

// Canonical keys matching the Attributes field names and attributeBonus maps in MongoDB
fun Attributes.toMap(): Map<String, Int> = mapOf(
    "strength" to strength,
    "agility" to agility,
    "intelligence" to intelligence,
    "resistance" to resistance,
    "flow" to flow,
    "wisdom" to wisdom,
    "presence" to presence,
    "defense" to defense
)

fun Map<String, Int>.toAttributes() = Attributes(
    strength = this["strength"] ?: 0,
    agility = this["agility"] ?: 0,
    intelligence = this["intelligence"] ?: 0,
    resistance = this["resistance"] ?: 0,
    flow = this["flow"] ?: 0,
    wisdom = this["wisdom"] ?: 0,
    presence = this["presence"] ?: 0,
    defense = this["defense"] ?: 0
)
