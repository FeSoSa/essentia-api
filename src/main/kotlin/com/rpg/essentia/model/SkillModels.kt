package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

data class SkillRequirements(
    val level: Int? = null,
    val attributes: Map<String, Int>? = null,  // ex: { "agility": 14, "strength": 12 }
    val weaponRequired: String? = null          // "curta" | "media" | "pesada" | "ranged" | "unarmed"
)

data class DamageFormula(
    val formula: String,       // display string, e.g. "17 + d20×FOR/4"
    val baseFixed: Int,
    val atributo: String?,     // ex: "FOR", "AGI", "FOR/AGI"
    val equilibrio: Int?
)

data class Cost(
    val type: String,              // "flow" | "hp" | "ether" | "charge" | "percentual_flow" | "percentual_hp"
    val value: Int?,
    val percentual: Int?
)

@Document(collection = "skills")
data class Skill(
    @Id val id: String? = null,
    val name: String,
    val desc: String,
    val type: String,              // "class" | "weapon" | "essencia" | "mestre"
    val skillClass: String?,       // null = Geral (qualquer classe)
    val weaponType: String?,
    val essenciaId: String?,
    val costs: List<Cost>,
    val damage: DamageFormula?,
    val cooldownTurns: Int,
    val ultimate: Boolean,
    val toggle: Boolean = false,
    val requirements: SkillRequirements? = null,
    val passive: Boolean = false,                      // skill passiva — não é usada ativamente
    val passiveAttributes: Map<String, Int>? = null,  // bônus passivo enquanto equipada
    val buffAttributes: Map<String, Int>? = null,     // bônus de atributo ao usar
    val buffDurationTurns: Int? = null,               // duração do buff (-1 = permanente)
    val pressaoDice: Boolean = false                  // técnica rola +1d6 por ponto de Pressão e consome tudo
)
