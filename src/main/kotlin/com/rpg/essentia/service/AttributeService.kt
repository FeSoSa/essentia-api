package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.ClassKitRepository
import com.rpg.essentia.repository.EffectPresetRepository
import org.springframework.stereotype.Service

const val HP_BASE = 20
const val HP_PER_RESISTANCE = 20
const val FLOW_BASE = 20
const val FLOW_PER_FLOW_ATTR = 15

@Service
class AttributeService(
    private val effectPresetRepository: EffectPresetRepository,
    private val classKitRepository: ClassKitRepository
) {

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

        // Essencia bonuses: o que a Essência soma até o nível dela no jogador
        val essenciaMap = essencias.associateBy { it.id }
        player.essenciasObtidas
            .filter { it.attributeBonusActive }
            .forEach { obtained ->
                val essencia = essenciaMap[obtained.essenciaId] ?: return@forEach
                EssenciaRules.bonusNoNivel(essencia, obtained.nivel).forEach { (k, v) -> base.merge(k, v, Int::plus) }
            }

        // Efeitos PASSIVO de skills equipadas já chegam como StatusEffect (syncPassiveSelfEffects)

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

        // Penalidade por excedente de Essência: modificador efetivo em RES/FLX (SAB não é afetada)
        val essencia = EssenciaRules.estado(player, base["wisdom"] ?: 0)
        if (essencia.penalidadeRes != 0) base["resistance"] = ((base["resistance"] ?: 0) + essencia.penalidadeRes).coerceAtLeast(0)
        if (essencia.penalidadeFlx != 0) base["flow"] = ((base["flow"] ?: 0) + essencia.penalidadeFlx).coerceAtLeast(0)

        return base.toAttributes()
    }

    /** Regenera do zero os StatusEffects dos efeitos PASSIVO das skills equipadas (marcados via sourceSkillId). */
    fun syncPassiveSelfEffects(player: Player, skills: List<Skill>): Player {
        val skillById = skills.associateBy { it.id }
        // Passiva só vale nos slots de passiva; skills ativas com efeito passivo valem em qualquer slot
        val equippedIds = player.slots
            .filter { s -> s.type == Slot.TIPO_PASSIVA || skillById[s.skillId]?.passive != true }
            .mapNotNull { it.skillId }.toSet()
        val presets = effectPresetRepository.findAll()

        val withoutStale = player.statusEffects.filterNot { it.sourceSkillId != null }
        val generated = equippedIds
            .mapNotNull { skillById[it] }
            .flatMap { EfeitoRules.passivos(it, presets) }
        return player.copy(statusEffects = withoutStale + generated)
    }

    /**
     * Recalcula PV/Fluxo máximos a partir dos atributos efetivos (já com a penalidade de Essência) e do bônus
     * por nível da classe. Corta o atual se ficar acima do novo máximo; com `somarGanho` (subiu de nível),
     * o atual soma o mesmo que o máximo aumentou.
     */
    fun recalculateVitals(player: Player, effective: Attributes, somarGanho: Boolean = false): Player {
        val kit = player.char.skillClass.takeIf { it.isNotBlank() }?.let { classKitRepository.findByClassName(it) }
        val pvPorNivel = kit?.pvPorNivel ?: 0
        val fluxoPorNivel = kit?.fluxoPorNivel ?: 0
        val nivel = player.char.level
        return EscudoRules.ajustarTeto(player.copy(
            hp   = VitaisRules.ajustar(player.hp, VitaisRules.pvMax(effective.resistance, nivel, pvPorNivel), somarGanho),
            flow = VitaisRules.ajustar(player.flow, VitaisRules.fluxoMax(effective.flow, nivel, fluxoPorNivel), somarGanho),
            effectiveAttributes = effective,
            essencia = EssenciaRules.estado(player, effective.wisdom),
            vitaisClasse = VitaisClasse(
                pvPorNivel, fluxoPorNivel,
                VitaisRules.bonusClasse(pvPorNivel, nivel), VitaisRules.bonusClasse(fluxoPorNivel, nivel)
            )
        ))
    }
}

fun Equipment.slotToItem(slot: String): Item? = when (slot) {
    "mainHand" -> mainHand?.let { Item(id = it.id, name = it.name, desc = it.desc ?: "", icon = it.icon, type = "weapon", equipSlot = it.equipSlot ?: slot, weaponType = it.weaponType, damageBase = it.damageBase, damageAttribute = it.damageAttribute, equilibrio = it.equilibrio, properties = it.properties, attributeBonus = it.attributeBonus, rarity = it.rarity, twoHanded = it.twoHanded, margemCritico = it.margemCritico, catalogId = it.catalogId, tiposDano = it.tiposDano) }
    "offHand"  -> offHand?.let  { Item(id = it.id, name = it.name, desc = it.desc ?: "", icon = it.icon, type = "weapon", equipSlot = it.equipSlot ?: slot, weaponType = it.weaponType, damageBase = it.damageBase, damageAttribute = it.damageAttribute, equilibrio = it.equilibrio, properties = it.properties, attributeBonus = it.attributeBonus, rarity = it.rarity, twoHanded = it.twoHanded, margemCritico = it.margemCritico, catalogId = it.catalogId, tiposDano = it.tiposDano) }
    "armor"    -> armor?.let    { Item(id = it.id, name = it.name, desc = it.desc ?: "", icon = it.icon, type = "armor",  equipSlot = slot, damageReduction = it.damageReduction, armorWeight = it.armorWeight, attributeBonus = it.attributeBonus, rarity = it.rarity, negaCritico = it.negaCritico, catalogId = it.catalogId, tracosDano = it.tracosDano) }
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
