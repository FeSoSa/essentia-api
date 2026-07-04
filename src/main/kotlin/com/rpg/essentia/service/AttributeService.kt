package com.rpg.essentia.service

import com.rpg.essentia.model.*
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

        // Status effect modify_attribute auto-effects (value/percentual sobre o atributo)
        base.keys.toList().forEach { key ->
            val mod = player.statusEffects.attributeModifier(key, base[key] ?: 0)
            if (mod != 0) base.merge(key, mod, Int::plus)
        }

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

fun Equipment.slotToItem(slot: String): Item? = when (slot) {
    "mainHand" -> mainHand?.let { Item(id = it.id, name = it.name, desc = it.desc ?: "", icon = it.icon, type = "weapon", equipSlot = slot, weaponType = it.weaponType, damageBase = it.damageBase, damageAttribute = it.damageAttribute, equilibrio = it.equilibrio, properties = it.properties, attributeBonus = it.attributeBonus, rarity = it.rarity, twoHanded = it.twoHanded) }
    "offHand"  -> offHand?.let  { Item(id = it.id, name = it.name, desc = it.desc ?: "", icon = it.icon, type = "weapon", equipSlot = slot, weaponType = it.weaponType, damageBase = it.damageBase, damageAttribute = it.damageAttribute, equilibrio = it.equilibrio, properties = it.properties, attributeBonus = it.attributeBonus, rarity = it.rarity, twoHanded = it.twoHanded) }
    "armor"    -> armor?.let    { Item(id = it.id, name = it.name, desc = it.desc ?: "", icon = it.icon, type = "armor",  equipSlot = slot, damageReduction = it.damageReduction, armorWeight = it.armorWeight, attributeBonus = it.attributeBonus, rarity = it.rarity) }
    "amulet"   -> amulet?.let   { Item(id = it.id, name = it.name, desc = it.desc ?: "", icon = it.icon, type = "accessory", equipSlot = slot, attributeBonus = it.attributeBonus, rarity = it.rarity) }
    "ring"     -> ring?.let     { Item(id = it.id, name = it.name, desc = it.desc ?: "", icon = it.icon, type = "accessory", equipSlot = slot, attributeBonus = it.attributeBonus, rarity = it.rarity) }
    "utility"  -> utility?.let  { Item(id = it.id, name = it.name, desc = it.desc ?: "", icon = it.icon, type = "accessory", equipSlot = slot, attributeBonus = it.attributeBonus, rarity = it.rarity) }
    else -> null
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
