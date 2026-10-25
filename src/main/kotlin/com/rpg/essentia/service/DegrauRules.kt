package com.rpg.essentia.service

import com.rpg.essentia.model.Degrau
import com.rpg.essentia.model.DegrauAplicado
import com.rpg.essentia.model.TracoDano
import kotlin.math.floor

/**
 * Fraquezas e resistências: degrau por tipo de dano, aplicado por último sobre o dano final.
 * Espelhado no painel (essentia-mestre/src/lib/tiposDano.ts) para os avisos de raridade.
 */
object DegrauRules {
    /** Id fixo do tipo usado no golpe desarmado (criado pelo seed). */
    const val IMPACTO = "impacto"
    const val MAX_TIPOS = 2

    /**
     * Tipos do golpe: os da skill. Sem eles, só o golpe que usa a arma (ataque básico ou skill com dano da arma)
     * herda os da arma, e desarmado é Impacto; skill de fórmula sem tipo fica sem tipo (Neutro).
     */
    fun tiposDoAtaque(tiposSkill: List<String>?, usaArma: Boolean, tiposArma: List<String>?, temArma: Boolean): List<String> =
        tiposSkill?.takeIf { it.isNotEmpty() }
            ?: when {
                !usaArma -> emptyList()
                temArma -> tiposArma.orEmpty()
                else -> listOf(IMPACTO)
            }

    /** Degrau de um tipo contra o alvo: o traço de raça/criatura vence a armadura; sem nenhum, Neutro. */
    fun degrauContra(tipo: String, tracosRaca: List<TracoDano>, tracosArmadura: List<TracoDano>): Pair<Degrau, String?> {
        tracosRaca.firstOrNull { it.tipoDanoId == tipo }?.let { return it.degrau to "raca" }
        tracosArmadura.firstOrNull { it.tipoDanoId == tipo }?.let { return it.degrau to "armadura" }
        return Degrau.NEUTRO to null
    }

    /** Com dois tipos vale o degrau mais favorável ao atacante (maior multiplicador). Sem soma de degraus. */
    fun resolver(tipos: List<String>, tracosRaca: List<TracoDano>, tracosArmadura: List<TracoDano>): DegrauAplicado =
        tipos.distinct()
            .map { t -> degrauContra(t, tracosRaca, tracosArmadura).let { (d, origem) -> DegrauAplicado(d, t, origem = origem) } }
            .maxByOrNull { it.degrau.mult }
            ?: DegrauAplicado()

    /** floor(dano × multiplicador): o único arredondamento do degrau. */
    fun aplicar(dano: Int, degrau: Degrau): Int =
        floor(dano * degrau.mult + 1e-9).toInt().coerceAtLeast(0)

    /** Erro de validação dos tipos de uma skill ou arma (null = ok). */
    fun erroTipos(tipos: List<String>?): String? = when {
        tipos.isNullOrEmpty() -> null
        tipos.size > MAX_TIPOS -> "No máximo $MAX_TIPOS tipos de dano"
        tipos.distinct().size != tipos.size -> "Tipo de dano repetido"
        else -> null
    }
}
