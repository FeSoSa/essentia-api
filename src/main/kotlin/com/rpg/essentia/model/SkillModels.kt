package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

data class SkillRequirements(
    val level: Int? = null,
    val attributes: Map<String, Int>? = null,  // ex: { "agility": 14, "strength": 12 }
    val skillIds: List<String>? = null
)

data class DamageFormula(
    val formula: String,           // display string, e.g. "4 + 2d6 + int"
    val baseFixed: Int,
    val baseDice: Dice?,
    val attribute: String?         // "strength" | "agility" | "intelligence" | "resistance" | "flow" | "wisdom" | "presence" | "defense" | null
)

data class Cost(
    val type: String,              // "flow" | "hp" | "ether" | "charge" | "percentual_flow" | "percentual_hp"
    val value: Int?,
    val percentual: Int?
)

@Document(collection = "skills")
data class Skill(
    @Id val id: String,
    val name: String,
    val desc: String,
    val type: String,              // "class" | "weapon" | "essencia"
    val skillClass: String?,
    val weaponType: String?,       // "curta" | "media" | "pesada" | "ranged" | "unarmed"
    val essenciaId: String?,
    val costs: List<Cost>,
    val damage: DamageFormula?,
    val cooldownTurns: Int,
    val ultimate: Boolean,
    val requirements: SkillRequirements? = null  // null = sem requisito, sempre AVAILABLE
)
