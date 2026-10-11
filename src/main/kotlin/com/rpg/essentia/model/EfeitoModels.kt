package com.rpg.essentia.model

/**
 * Efeito de skill (lista única que substitui buff de atributo, atributo passivo,
 * bônus de combate, efeito no usuário e efeito ao acertar).
 *
 *   alvo:    SELF | ALVO | ALIADOS | AREA
 *   tipo:    MODIFICADOR (stat + valor) | CONDICAO (nome de uma condição cadastrada em effect_presets)
 *            | ESCUDO (valor: PV temporário por cima da vida, sem duração; zera no fim do dia)
 *            | CURA (valor de PV: sem duração é instantânea; com duração cura esse valor a cada turno; nunca gera Escudo)
 *   stat:    FOR | AGI | INT | RES | FLX | SAB | PRE | DEF | ACERTO | DANO
 *   duracao: número de rodadas (Int) ou "PASSIVO" (vale enquanto a skill estiver equipada)
 */
data class Efeito(
    val alvo: String = ALVO_SELF,
    val tipo: String = TIPO_MODIFICADOR,
    val stat: String? = null,
    val valor: Int? = null,
    val condicao: String? = null,
    val duracao: Any? = null
) {
    val passivo: Boolean get() = (duracao as? String)?.uppercase() == DURACAO_PASSIVO

    /** Rodadas de duração; null quando PASSIVO ou não informado. */
    val rodadas: Int? get() = when (val d = duracao) {
        is Number -> d.toInt()
        is String -> d.toIntOrNull()
        else -> null
    }

    companion object {
        const val ALVO_SELF = "SELF"
        const val ALVO_ALVO = "ALVO"
        const val ALVO_ALIADOS = "ALIADOS"
        const val ALVO_AREA = "AREA"
        const val TIPO_MODIFICADOR = "MODIFICADOR"
        const val TIPO_CONDICAO = "CONDICAO"
        const val TIPO_ESCUDO = "ESCUDO"
        const val TIPO_CURA = "CURA"
        const val DURACAO_PASSIVO = "PASSIVO"
        const val STAT_ACERTO = "ACERTO"
        const val STAT_DANO = "DANO"

        /** Stat de atributo -> chave usada em Attributes/attributeBonus. */
        val STAT_PARA_ATRIBUTO = mapOf(
            "FOR" to "strength", "AGI" to "agility", "INT" to "intelligence", "RES" to "resistance",
            "FLX" to "flow", "SAB" to "wisdom", "PRE" to "presence", "DEF" to "defense"
        )
        val ATRIBUTO_PARA_STAT = STAT_PARA_ATRIBUTO.entries.associate { (k, v) -> v to k }
    }
}

/** Uma linha da tabela de tiers (editável no painel do mestre). */
data class TierRegra(
    val tier: Int,
    val equilibrio: Double,
    val custoBase: Int
)

val TIERS_PADRAO = listOf(
    TierRegra(1, 4.0, 8),
    TierRegra(2, 3.5, 15),
    TierRegra(3, 3.0, 25),
    TierRegra(4, 2.5, 40)
)
