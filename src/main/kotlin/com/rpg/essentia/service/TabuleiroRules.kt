package com.rpg.essentia.service

import com.rpg.essentia.model.PecaTabuleiro
import com.rpg.essentia.model.Tabuleiro

/**
 * Regra de movimento do tabuleiro, igual à do painel (MesaNova/movimento.ts) e do app
 * (lib/movimento.ts): 8 direções, terreno difícil custa 2, parede bloqueia, passa por quem
 * é do mesmo lado e não por oponente, diagonal não corta quina, termina só em casa livre.
 */
object TabuleiroRules {

    private val VIZINHOS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

    private fun ladoDaMesa(tipo: String) = if (tipo == "jogador" || tipo == "aliado") "grupo" else "oponentes"

    private fun casas(x: Int, y: Int, lado: Int) =
        (0 until lado).flatMap { dx -> (0 until lado).map { dy -> "${x + dx},${y + dy}" } }

    /**
     * Custo do caminho mais barato da posição atual da peça até (destX, destY), ou null se
     * não dá para chegar gastando até `maximo`. `pecas` é o tabuleiro de antes do movimento.
     */
    fun custoAte(t: Tabuleiro, pecas: List<PecaTabuleiro>, peca: PecaTabuleiro, destX: Int, destY: Int, maximo: Int): Int? {
        val lado = peca.lado
        val ocup = HashMap<String, PecaTabuleiro>()
        pecas.forEach { p -> casas(p.x, p.y, p.lado).forEach { ocup[it] = p } }
        val meuLado = ladoDaMesa(peca.tipo)
        fun dentro(x: Int, y: Int) = x >= 0 && y >= 0 && x + lado <= t.colunas && y + lado <= t.linhas
        fun passavel(x: Int, y: Int) = dentro(x, y) && casas(x, y, lado).all { k ->
            val o = ocup[k]
            t.terreno[k] != "parede" && (o == null || o.id == peca.id || ladoDaMesa(o.tipo) == meuLado)
        }
        fun livreAoFim(x: Int, y: Int) = casas(x, y, lado).all { k ->
            val o = ocup[k]
            t.terreno[k] != "parede" && (o == null || o.id == peca.id)
        }
        if (!dentro(destX, destY) || !livreAoFim(destX, destY)) return null
        if (destX == peca.x && destY == peca.y) return 0

        val custo = HashMap<String, Int>().apply { put("${peca.x},${peca.y}", 0) }
        val fila = ArrayList<Triple<Int, Int, Int>>().apply { add(Triple(peca.x, peca.y, 0)) }
        while (fila.isNotEmpty()) {
            val i = fila.indices.minBy { fila[it].third }
            val (x, y, g) = fila.removeAt(i)
            if (g > (custo["$x,$y"] ?: Int.MAX_VALUE)) continue
            if (x == destX && y == destY) return g
            for ((dx, dy) in VIZINHOS) {
                val nx = x + dx
                val ny = y + dy
                if (!passavel(nx, ny)) continue
                if (dx != 0 && dy != 0 && (!passavel(x + dx, y) || !passavel(x, y + dy))) continue
                val ng = g + if (casas(nx, ny, lado).any { t.terreno[it] == "dificil" }) 2 else 1
                val k = "$nx,$ny"
                if (ng <= maximo && ng < (custo[k] ?: Int.MAX_VALUE)) {
                    custo[k] = ng
                    fila.add(Triple(nx, ny, ng))
                }
            }
        }
        return null
    }
}
