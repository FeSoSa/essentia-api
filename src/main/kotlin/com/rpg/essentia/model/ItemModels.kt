package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

data class ItemRequirements(
    val level: Int? = null,
    val attributes: Map<String, Int>? = null  // ex: { "strength": 14, "agility": 12 }
)

data class OnUseEffect(
    val name: String = "",
    val desc: String = "",
    val durationTurns: Int = 1,
    val icon: String? = null,
    val color: String? = null,
    val instantHealHp: Int? = null,
    val instantHealFlow: Int? = null,
    val attributeBonus: Map<String, Int>? = null,
    val effects: List<AutoEffect> = emptyList(),
    val hitBonus: Int? = null,
    val attackBonus: Int? = null,
    val damageBonus: Int? = null,
    val onExpire: OnExpireEffect? = null
)

@Document(collection = "item_catalog")
data class ItemCatalog(
    @Id val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val desc: String = "",
    val type: String = "consumable",
    val icon: String? = null,
    val weaponType: String? = null,
    val damageBase: Int? = null,
    val damageAttribute: String? = null,
    val equilibrio: Int? = null,
    val properties: String? = null,
    val critThreshold: Int? = null,   // mínimo no d20 para crítico (null = 20)
    val damageReduction: Int? = null,
    val attributeBonus: Map<String, Int>? = null,
    val equipSlot: String? = null,
    val armorWeight: String? = null,  // "leve" | "média" | "pesada"
    val rarity: String? = null,       // "branco" | "verde" | "azul" | "roxo" | "amarelo" | "rosa"
    val twoHanded: Boolean? = null,
    val requirements: ItemRequirements? = null,
    val onUseEffect: OnUseEffect? = null,
    val goldValue: Int? = null,
    val subtype: String? = null
)
