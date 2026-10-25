package com.rpg.essentia.service

import com.rpg.essentia.model.PecaTabuleiro
import com.rpg.essentia.model.Tabuleiro
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Custo de movimento no tabuleiro: o mesmo que o app e o painel mostram. */
class TabuleiroRulesTest {

    private val eu = PecaTabuleiro(id = "eu", tipo = "jogador", x = 0, y = 1, deslocamento = 3)

    private fun tab(terreno: Map<String, String> = emptyMap(), outras: List<PecaTabuleiro> = emptyList()) =
        Tabuleiro(colunas = 8, linhas = 3, terreno = terreno, pecas = listOf(eu) + outras, regraMovimento = true)

    private fun custo(t: Tabuleiro, peca: PecaTabuleiro, x: Int, y: Int, max: Int) =
        TabuleiroRules.custoAte(t, t.pecas, peca, x, y, max)

    @Test
    fun `linha reta custa uma casa por passo e diagonal também`() {
        assertEquals(3, custo(tab(), eu, 3, 1, 3))
        assertEquals(2, custo(tab(), eu, 2, 0, 3))
        assertNull(custo(tab(), eu, 4, 1, 3))
    }

    @Test
    fun `terreno difícil custa dois`() {
        val t = tab(mapOf("1,0" to "dificil", "1,1" to "dificil", "1,2" to "dificil"))
        assertEquals(2, custo(t, eu, 1, 1, 3))
        assertEquals(3, custo(t, eu, 2, 1, 3))
        assertNull(custo(t, eu, 3, 1, 3))
    }

    @Test
    fun `parede contorna e oponente não deixa passar`() {
        // Diagonal não corta quina de parede: contorna por cima (sobe, dois para o lado, desce)
        val parede = tab(mapOf("1,1" to "parede"))
        assertEquals(4, custo(parede, eu, 2, 1, 6))
        assertNull(custo(parede, eu, 2, 1, 3))
        val muro = tab(mapOf("1,0" to "parede", "1,1" to "parede", "1,2" to "parede"))
        assertNull(custo(muro, eu, 2, 1, 6))
        val inimigo = PecaTabuleiro(id = "i", tipo = "inimigo", x = 1, y = 1)
        assertNull(custo(tab(outras = listOf(inimigo)), eu, 1, 1, 3))
    }

    @Test
    fun `ir e voltar gasta o caminho inteiro`() {
        // Sai de 0,1, vai a 3,1 (3) e volta a 0,1 (mais 3): com 3 de deslocamento a volta não cabe
        val t = tab()
        val ida = custo(t, eu, 3, 1, 3)!!
        val depois = eu.copy(x = 3)
        assertNull(custo(t.copy(pecas = listOf(depois)), depois, 0, 1, 3 - ida))
    }
}
