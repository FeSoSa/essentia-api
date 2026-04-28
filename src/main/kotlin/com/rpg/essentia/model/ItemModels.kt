package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

@Document(collection = "item_catalog")
data class ItemCatalog(
    @Id val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val desc: String = "",
    val type: String = "consumable",
    val icon: String? = null,
    val weaponType: String? = null,
    val damageBase: Int? = null,
    val damageDice: Dice? = null,
    val damageAttribute: String? = null,
    val properties: String? = null,
    val damageReduction: Int? = null,
    val attributeBonus: Map<String, Int>? = null,
    val equipSlot: String? = null
)
