package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

data class CharInfo(
    val name: String = "",
    val classe: String = "",
    val skillClass: String = "",
    val race: String = "",
    val level: Int = 1,
    val slotsClass: Int = 2,
    val slotsFree: Int = 6,
    val slotsTotal: Int = 8,
    val portraitUrl: String? = null,
    val unarmedAttack: UnarmedAttack? = null
)

data class Vital(val current: Int = 0, val max: Int = 0)

data class Ether(
    val current: Int = 0,
    val max: Int = 0,
    val unlocked: Boolean = false
)

data class Exp(
    val available: Int = 0,
    val total: Int = 0
)

// JSON keys: strength, agility, intelligence, resistance, flow, wisdom, presence, defense
// attributeBonus maps from equipment/essencias must use these same keys
data class Attributes(
    val strength: Int = 0,
    val agility: Int = 0,
    val intelligence: Int = 0,
    val resistance: Int = 0,
    val flow: Int = 0,
    val wisdom: Int = 0,
    val presence: Int = 0,
    val defense: Int = 0
)

data class EssenciaObtida(
    val essenciaId: String = "",
    val attributeBonusActive: Boolean = false,
    val unlockedSkillIds: List<String> = emptyList()
)

data class Slot(
    val id: String = "",
    val type: String = "",             // "class" | "free" | "human_bonus"
    val skillId: String? = null,
    val cooldownRemaining: Int = 0
)

data class WeaponEquip(
    val id: String = "",
    val name: String = "",
    val weaponType: String = "",
    val damageBase: Int = 0,
    val damageAttribute: String = "",
    val equilibrio: Int? = null,
    val attributeBonus: Map<String, Int>? = null,
    val rarity: String? = null,
    val twoHanded: Boolean = false
)

data class ArmorEquip(
    val id: String = "",
    val name: String = "",
    val damageReduction: Int = 0,
    val attributeBonus: Map<String, Int>? = null,
    val armorWeight: String? = null,
    val rarity: String? = null
)

data class AccessoryEquip(
    val id: String = "",
    val name: String = "",
    val attributeBonus: Map<String, Int>? = null,
    val rarity: String? = null
)

data class Equipment(
    val mainHand: WeaponEquip? = null,
    val offHand: WeaponEquip? = null,
    val armor: ArmorEquip? = null,
    val amulet: AccessoryEquip? = null,
    val ring: AccessoryEquip? = null,
    val utility: AccessoryEquip? = null
)

data class AutoEffect(
    val trigger: String = "",
    val type: String = "",
    val value: Int? = null,
    val percentual: Int? = null,
    val attribute: String? = null,
    val dice: Dice? = null
)

data class StatusEffect(
    val id: String = "",
    val name: String = "",
    val desc: String = "",
    val icon: String? = null,
    val color: String? = null,
    val durationTurns: Int = 1,        // -1 = permanent until master removes
    val effects: List<AutoEffect> = emptyList(),
    val attributeBonus: Map<String, Int>? = null  // bônus de atributos enquanto ativo
)

data class Item(
    val id: String = "",
    val name: String = "",
    val desc: String = "",
    val qty: Int = 1,
    val type: String = "",             // "consumable" | "equipment" | "currency" | "weapon" | "armor" | "accessory"
    val icon: String? = null,
    // Weapon fields (type == "weapon")
    val weaponType: String? = null,
    val damageBase: Int? = null,
    val damageAttribute: String? = null,
    val equilibrio: Int? = null,
    val properties: String? = null,
    // Armor field (type == "armor")
    val damageReduction: Int? = null,
    val armorWeight: String? = null,   // "leve" | "média" | "pesada"
    // Shared equip field (type == "weapon" | "armor" | "accessory")
    val attributeBonus: Map<String, Int>? = null,
    val equipSlot: String? = null,     // "mainHand"|"offHand"|"armor"|"amulet"|"ring"|"utility"
    val rarity: String? = null,
    val twoHanded: Boolean? = null,
    val requirements: ItemRequirements? = null
)

data class PendingRequest(
    val id: String = "",
    val type: String = "",             // "use_item"
    val itemId: String = "",
    val timestamp: String = ""
)

@Document(collection = "players")
data class Player(
    @Id val id: String,
    val code: String,
    val char: CharInfo = CharInfo(),
    val hp: Vital = Vital(),
    val flow: Vital = Vital(),
    val ether: Ether = Ether(),
    val pressao: Vital? = null,
    val exp: Exp = Exp(),
    val attributes: Attributes = Attributes(),
    val effectiveAttributes: Attributes? = null,
    val essenciasObtidas: List<EssenciaObtida> = emptyList(),
    val slots: List<Slot> = emptyList(),
    val equipment: Equipment = Equipment(),
    val statusEffects: List<StatusEffect> = emptyList(),
    val items: List<Item> = emptyList(),
    val pendingRequests: List<PendingRequest> = emptyList(),
    val gold: Int = 0,
    val desviosRestantes: Int = 3,
    val sobrecargaDesbloqueada: Boolean = false,
    val sobrecargaAtiva: Boolean = false,
    val sobrecargaNivel: Int? = null,
    val customBars: List<CustomBar> = emptyList()
)

data class CustomBar(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val color: String = "#a855f7",
    val current: Int = 0,
    val max: Int = 10
)
