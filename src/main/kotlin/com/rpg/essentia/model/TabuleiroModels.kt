package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Tabuleiro da Mesa, como o mestre monta no painel. Documento único: o painel manda o
 * retrato inteiro a cada mudança e o app só lê, a não ser o jogador movendo a própria peça.
 */
@Document(collection = "tabuleiro")
data class Tabuleiro(
    @Id val id: String = ID,
    val colunas: Int = 16,
    val linhas: Int = 10,
    val terreno: Map<String, String> = emptyMap(),   // "x,y" -> "parede" | "dificil"
    val marcas: Map<String, String> = emptyMap(),    // "x,y" -> cor: desenho do mestre, só visual (área, rota, aviso)
    val pecas: List<PecaTabuleiro> = emptyList(),
    val regraMovimento: Boolean = false,
    val movimento: MovimentoTurno? = null,           // quanto a peça da vez já andou nesta vez (a API mantém)
    val visivel: Boolean = false                     // o mestre libera para os jogadores verem no app
) {
    companion object { const val ID = "mesa" }
}

data class PecaTabuleiro(
    val id: String = "",
    val tipo: String = "",          // jogador | inimigo | boss | aliado
    val nome: String = "",
    val src: String? = null,
    val emoji: String? = null,
    val x: Int = 0,
    val y: Int = 0,
    val lado: Int = 1,
    val hp: Int = 0,
    val hpMax: Int = 0,
    val deslocamento: Int = 6
)

/** Casas que a peça da vez já gastou; turno = totalTurns da vez, para não valer na seguinte. */
data class MovimentoTurno(val id: String = "", val turno: Int = -1, val gasto: Int = 0)

data class MoverPecaRequest(val playerId: String = "", val x: Int = 0, val y: Int = 0)

data class PecaMovida(val id: String, val x: Int, val y: Int)
