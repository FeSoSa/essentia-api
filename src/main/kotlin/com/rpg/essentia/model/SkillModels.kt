package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

data class DamageFormula(
    val formula: String,       // display string, e.g. "17 + d20×FOR/4"
    val baseFixed: Int,
    val atributo: String?,     // ex: "FOR", "AGI", "FOR/AGI"
    val equilibrio: Int? = null // só skills sem tier (inimigo/mestre); nas demais vem do tier
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
    val type: String,              // "class" | "weapon" | "essencia" | "mestre" | "inimigo"
    val skillClass: String?,       // null = Geral (qualquer classe)
    val weaponType: String?,       // legado — usar weaponTypes quando possível
    val weaponTypes: List<String>? = null, // multi-tipo de arma
    val essenciaId: String?,
    val costs: List<Cost>,
    val damage: DamageFormula?,
    val cooldownTurns: Int,
    val ultimate: Boolean,
    val actionType: String = "main",                     // "main" | "bonus" | "both"
    val passive: Boolean = false,                      // skill passiva — não é usada ativamente
    val tier: Int? = null,                             // 1 a 4: equilíbrio, custo base e requisito por origem (null = inimigo/mestre)
    val grupoMaestria: String? = null,                 // skill de arma: categoria da maestria (null = primeira de weaponTypes)
    val alvo: String? = null,                          // em quem a skill é usada: "USUARIO" | "INIMIGOS" | "ALIADOS" (área e alvos em multiTarget)
    val efeitos: List<Efeito> = emptyList(),           // lista única de efeitos (modificadores e condições)
    val pressaoDice: Boolean = false,                  // técnica rola +1d6 por ponto de Pressão e consome tudo
    val critThreshold: Int? = null,                    // mínimo no d20 para crítico (null = 20)
    val multiTarget: MultiTarget? = null,              // habilidade atinge múltiplos inimigos
    val damageSource: String = "formula",              // "formula" (usa damage) | "weapon" (usa arma equipada em weaponSlot)
    val weaponSlot: String? = null,                    // "mainHand" | "offHand" — obrigatório quando damageSource == "weapon"
    val weaponDamageModifiers: List<AutoEffect> = emptyList() // AutoEffect.type: "flat" | "percent" | "extra_dice"
) {
    /** Equilíbrio da fórmula: vem do tier; sem tier (inimigo/mestre) usa o valor salvo na fórmula. */
    fun equilibrio(cfg: CombateConfig): Double? =
        tier?.let { cfg.tier(it).equilibrio } ?: damage?.equilibrio?.toDouble()

    /**
     * Tem tier? Skills de arma, de Essência e de classe com classe definida. Ficam fora: Geral (classe sem classe),
     * mestre e inimigo, que mantêm o equilíbrio próprio.
     */
    fun usaTier(): Boolean = type == "weapon" || type == "essencia" || (type == "class" && !skillClass.isNullOrBlank())

    /** Categorias de arma da skill: weaponTypes (multi) ou weaponType (legado), em minúsculas. */
    fun categoriasArma(): List<String> =
        (weaponTypes ?: listOfNotNull(weaponType)).map { it.lowercase() }.filter { it.isNotBlank() }
}
