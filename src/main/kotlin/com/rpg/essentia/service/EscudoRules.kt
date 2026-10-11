package com.rpg.essentia.service

import com.rpg.essentia.model.Player

/**
 * Escudo: PV temporário por cima da vida (não muda o PV máximo).
 *   Teto = 25% do PV máximo (arredondado para baixo); o que passar é perdido.
 *   Todas as fontes somam no mesmo valor. Todo dano consome primeiro o Escudo, depois a vida.
 *   Cura nunca gera Escudo. Zera no fim do dia (o mestre aciona).
 */
object EscudoRules {
    const val TETO_PCT = 25

    fun teto(pvMax: Int): Int = (pvMax.coerceAtLeast(0) * TETO_PCT) / 100

    /** Soma Escudo respeitando o teto. */
    fun ganhar(player: Player, valor: Int): Player =
        player.copy(escudo = (player.escudo + valor.coerceAtLeast(0)).coerceAtMost(teto(player.hp.max)))

    /** Dano: o Escudo absorve primeiro, o restante sai da vida (nunca abaixo de 0). */
    fun receberDano(player: Player, dano: Int): Player {
        if (dano <= 0) return player
        val absorvido = minOf(player.escudo, dano)
        val naVida = dano - absorvido
        return player.copy(
            escudo = player.escudo - absorvido,
            hp = player.hp.copy(current = (player.hp.current - naVida).coerceAtLeast(0))
        )
    }

    /** Cura: só a vida, até o máximo; o excedente é perdido e nunca vira Escudo. */
    fun curar(player: Player, cura: Int): Player =
        if (cura <= 0) player else player.copy(hp = player.hp.copy(current = (player.hp.current + cura).coerceAtMost(player.hp.max)))

    /** Se o PV máximo caiu, o Escudo é cortado no novo teto. */
    fun ajustarTeto(player: Player): Player = player.copy(escudo = player.escudo.coerceIn(0, teto(player.hp.max)))
}
