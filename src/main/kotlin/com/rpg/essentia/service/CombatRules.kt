package com.rpg.essentia.service

import com.rpg.essentia.model.CombateConfig
import kotlin.math.floor
import kotlin.random.Random

/** Regras de combate compartilhadas (espelhadas em essentia-player/lib/rules.ts). */
object CombatRules {
    const val MARGEM_CRITICO_PADRAO = 20
    const val CD_DESVIO = 12

    fun getModifier(value: Int): Int = when {
        value <= 7  -> -1; value <= 11 -> 0;  value <= 15 -> 1;  value <= 19 -> 2
        value <= 23 -> 3;  value <= 27 -> 4;  value <= 31 -> 5;  value <= 35 -> 6
        value <= 40 -> 7;  value <= 46 -> 8;  value <= 53 -> 9;  value <= 61 -> 10
        else        -> 11
    }

    private val ABBREV_TO_KEY = mapOf(
        "FOR" to "strength", "AGI" to "agility", "INT" to "intelligence",
        "RES" to "resistance", "FLX" to "flow", "SAB" to "wisdom",
        "PRE" to "presence", "DEF" to "defense"
    )

    /** Mod do melhor atributo entre os listados ("FOR/AGI"). Aceita abreviação ou chave interna. */
    fun resolveAtributo(atributo: String?, attrMap: Map<String, Int>): Int {
        if (atributo.isNullOrBlank()) return 0
        return atributo.split("/")
            .mapNotNull { raw -> attrMap[ABBREV_TO_KEY[raw.trim().uppercase()] ?: raw.trim()] }
            .maxOrNull()
            ?.let { getModifier(it) } ?: 0
    }

    /**
     * Fórmula padrão: floor(base + d20 × mod / equilíbrio), com equilíbrio decimal.
     * No crítico: floor(base × critMultBase + d20 × critMultDado × mod / equilíbrio).
     * O floor acontece só no resultado final.
     */
    fun dano(base: Int, d20: Int, mod: Int, equilibrio: Double, critico: Boolean, cfg: CombateConfig, bonusDano: Double = 0.0): Int =
        danoFinal(danoBruto(base, d20, mod, equilibrio, critico, cfg), bonusDano)

    /** Parte da fórmula antes do floor: base + d20 × mod / equilíbrio (com os multiplicadores de crítico). */
    fun danoBruto(base: Int, d20: Int, mod: Int, equilibrio: Double, critico: Boolean, cfg: CombateConfig): Double {
        val eq = equilibrio.coerceAtLeast(0.5)
        val multBase = if (critico) cfg.critMultBase else 1.0
        val multDado = if (critico) cfg.critMultDado else 1.0
        return base * multBase + d20 * multDado * mod / eq
    }

    /**
     * floor(bruto × (1 + bônus1) × (1 + bônus2)...): o floor acontece só aqui, no final.
     * Ex: maestria +20% vira bonus 0.2. Margem mínima contra erro de ponto flutuante (6,9999999 que deveria ser 7).
     */
    fun danoFinal(bruto: Double, vararg bonus: Double): Int =
        floor(bonus.fold(bruto) { acc, b -> acc * (1.0 + b) } + 1e-9).toInt()

    /** custo_final = floor(custo × (1 - redução)), redução somada (maestria + efeitos ativos), nunca abaixo de 0. */
    fun custoFinal(custo: Int, reducao: Double): Int =
        floor(custo * (1.0 - reducao.coerceAtMost(1.0)) + 1e-9).toInt().coerceAtLeast(0)

    /** Equilíbrio inteiro (inimigos e skills sem tier). */
    fun dano(base: Int, d20: Int, mod: Int, equilibrio: Int, critico: Boolean, cfg: CombateConfig): Int =
        dano(base, d20, mod, equilibrio.coerceAtLeast(1).toDouble(), critico, cfg)

    fun rolarD20(random: Random = Random.Default): Int = random.nextInt(1, 21)
}
