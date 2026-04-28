package com.rpg.essentia.service

import com.rpg.essentia.model.Attributes
import com.rpg.essentia.model.Essencia
import com.rpg.essentia.model.Player
import org.springframework.stereotype.Service

const val HP_BASE = 20
const val HP_PER_RESISTANCE = 5
const val FLOW_BASE = 20
const val FLOW_PER_FLOW_ATTR = 5

@Service
class AttributeService {

    fun computeEffectiveAttributes(player: Player, essencias: List<Essencia>): Attributes {
        val base = player.attributes.toMap().toMutableMap()

        // Weapons (mainHand, offHand) do NOT contribute to attribute calculation
        val eq = player.equipment
        listOfNotNull(
            eq.armor?.attributeBonus,
            eq.amulet?.attributeBonus,
            eq.ring?.attributeBonus,
            eq.utility?.attributeBonus
        ).forEach { bonus -> bonus.forEach { (k, v) -> base.merge(k, v, Int::plus) } }

        // Add active essencia bonuses
        val essenciaMap = essencias.associateBy { it.id }
        player.essenciasObtidas
            .filter { it.attributeBonusActive }
            .forEach { obtained ->
                essenciaMap[obtained.essenciaId]?.attributeBonus
                    ?.forEach { (k, v) -> base.merge(k, v, Int::plus) }
            }

        return base.toAttributes()
    }

    fun recalculateVitals(player: Player, effective: Attributes): Player =
        player.copy(
            hp = player.hp.copy(max = HP_BASE + effective.resistance * HP_PER_RESISTANCE),
            flow = player.flow.copy(max = FLOW_BASE + effective.flow * FLOW_PER_FLOW_ATTR)
            // TODO: adicionar bônus de nível quando definido
        )
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
