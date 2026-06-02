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

data class MultiTarget(
    val maxTargets: Int,
    val damageMode: String,        // "igual" | "distribuido" | "especifico"
    val specificDamage: Int? = null
)

@Document(collection = "skills")
data class Skill(
    @Id val id: String? = null,
    val name: String,
    val desc: String,
    val type: String,              // "class" | "weapon" | "essencia" | "mestre"
    val skillClass: String?,       // null = Geral (qualquer classe)
    val weaponType: String?,       // legado — usar weaponTypes quando possível
    val weaponTypes: List<String>? = null, // multi-tipo de arma
    val essenciaId: String?,
    val costs: List<Cost>,
    val damage: DamageFormula?,
    val cooldownTurns: Int,
    val ultimate: Boolean,
    val toggle: Boolean = false,
    val requirements: SkillRequirements? = null,
    val actionType: String = "main",                     // "main" | "bonus" | "both"
    val passive: Boolean = false,                      // skill passiva — não é usada ativamente
    val passiveAttributes: Map<String, Int>? = null,  // bônus passivo enquanto equipada
    val buffAttributes: Map<String, Int>? = null,     // bônus de atributo ao usar
    val buffDurationTurns: Int? = null,               // duração do buff (-1 = permanente)
    val hitBonus: Int? = null,                        // bônus ao d20 de acerto (buff ao usar)
    val attackBonus: Int? = null,                     // bônus ao d20 de ataque/fórmula (buff ao usar)
    val damageBonus: Int? = null,                     // bônus fixo de dano (buff ao usar)
    val pressaoDice: Boolean = false,                  // técnica rola +1d6 por ponto de Pressão e consome tudo
    val critThreshold: Int? = null,                    // mínimo no d20 para crítico (null = 20)
    val multiTarget: MultiTarget? = null,              // habilidade atinge múltiplos inimigos
    val onHitEffects: List<StatusEffect> = emptyList() // status effects aplicados ao inimigo ao acertar
)
