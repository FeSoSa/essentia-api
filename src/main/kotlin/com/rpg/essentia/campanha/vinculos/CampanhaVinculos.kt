package com.rpg.essentia.campanha.vinculos

import com.rpg.essentia.campanha.CampanhaPonto

/**
 * Ponte opcional entre a campanha e o sistema que a usa. Sem uma implementação,
 * o módulo funciona sozinho: nomes ficam como estão e não há avisos nem referências.
 */
interface CampanhaVinculos {
    /** Devolve cada ponto com os ids completados (só em memória) e os avisos de cada um. */
    fun resolver(pontos: List<CampanhaPonto>): Map<String, Resolucao>

    /** Conteúdo de `_referencias.md`, ou null para não gerar. */
    fun gerarReferencias(): String?
}

data class Resolucao(val ponto: CampanhaPonto, val avisos: List<String>)
