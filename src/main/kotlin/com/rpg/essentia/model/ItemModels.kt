package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

data class ItemRequirements(
    val level: Int? = null,
    val attributes: Map<String, Int>? = null  // ex: { "strength": 14, "agility": 12 }
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
    val damageReduction: Int? = null,
    val attributeBonus: Map<String, Int>? = null,
    val equipSlot: String? = null,
    val armorWeight: String? = null,  // "leve" | "média" | "pesada"
    val rarity: String? = null,       // "branco" | "verde" | "azul" | "roxo" | "amarelo" | "rosa"
    val twoHanded: Boolean? = null,
    val requirements: ItemRequirements? = null
)
