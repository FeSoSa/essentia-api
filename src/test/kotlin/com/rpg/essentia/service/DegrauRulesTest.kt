package com.rpg.essentia.service

import com.rpg.essentia.model.Degrau
import com.rpg.essentia.model.DegrauAplicado
import com.rpg.essentia.model.TracoDano
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fraquezas e resistências: degrau por tipo de dano sobre o dano final. */
class DegrauRulesTest {

    private fun t(tipo: String, d: Degrau) = TracoDano(tipo, d)

    @Test
    fun `traço de raça sobrepõe a armadura`() {
        val r = DegrauRules.resolver(listOf("fogo"), listOf(t("fogo", Degrau.FRACO)), listOf(t("fogo", Degrau.RESISTENTE)))
        assertEquals(Degrau.FRACO, r.degrau)
        assertEquals("raca", r.origem)
    }

    @Test
    fun `imunidade por traço zera o dano`() {
        val r = DegrauRules.resolver(listOf("veneno"), listOf(t("veneno", Degrau.IMUNE)), emptyList())
        assertEquals(Degrau.IMUNE, r.degrau)
        assertEquals(0, DegrauRules.aplicar(87, r.degrau))
    }

    @Test
    fun `imunidade por armadura quando a raça não tem o tipo`() {
        val r = DegrauRules.resolver(listOf("fogo"), listOf(t("corte", Degrau.FRACO)), listOf(t("fogo", Degrau.IMUNE)))
        assertEquals(Degrau.IMUNE, r.degrau)
        assertEquals("armadura", r.origem)
    }

    @Test
    fun `dois tipos usam o degrau mais favorável ao atacante`() {
        // Corte + Fogo contra alvo Resistente a Corte e Fraco a Fogo = Fraco x1,5
        val r = DegrauRules.resolver(listOf("corte", "fogo"), listOf(t("corte", Degrau.RESISTENTE), t("fogo", Degrau.FRACO)), emptyList())
        assertEquals(Degrau.FRACO, r.degrau)
        assertEquals("fogo", r.tipoDanoId)
        // Sem soma: Resistente + Neutro fica Neutro, não x0,75
        val r2 = DegrauRules.resolver(listOf("corte", "impacto"), listOf(t("corte", Degrau.RESISTENTE)), emptyList())
        assertEquals(Degrau.NEUTRO, r2.degrau)
    }

    @Test
    fun `no máximo 2 tipos de dano`() {
        assertNull(DegrauRules.erroTipos(emptyList()))
        assertNull(DegrauRules.erroTipos(listOf("corte", "fogo")))
        assertNotNull(DegrauRules.erroTipos(listOf("corte", "fogo", "veneno")))
        assertNotNull(DegrauRules.erroTipos(listOf("fogo", "fogo")))
    }

    @Test
    fun `skill com dano da arma herda os tipos dela`() {
        assertEquals(listOf("perfuracao"), DegrauRules.tiposDoAtaque(emptyList(), usaArma = true, listOf("perfuracao"), temArma = true))
        assertEquals(listOf("fogo"), DegrauRules.tiposDoAtaque(listOf("fogo"), usaArma = true, listOf("perfuracao"), temArma = true))
        // Arma antiga sem tipos: golpe sem tipo, sai Neutro
        assertEquals(emptyList<String>(), DegrauRules.tiposDoAtaque(null, usaArma = true, null, temArma = true))
    }

    @Test
    fun `skill de fórmula sem tipos não herda da arma`() {
        assertEquals(emptyList<String>(), DegrauRules.tiposDoAtaque(emptyList(), usaArma = false, listOf("corte"), temArma = true))
        assertEquals(emptyList<String>(), DegrauRules.tiposDoAtaque(null, usaArma = false, null, temArma = false))
        assertEquals(listOf("fogo"), DegrauRules.tiposDoAtaque(listOf("fogo"), usaArma = false, listOf("corte"), temArma = true))
    }

    @Test
    fun `desarmado é Impacto`() {
        assertEquals(listOf(DegrauRules.IMPACTO), DegrauRules.tiposDoAtaque(null, usaArma = true, null, temArma = false))
        val r = DegrauRules.resolver(DegrauRules.tiposDoAtaque(null, true, null, false), listOf(t(DegrauRules.IMPACTO, Degrau.RESISTENTE)), emptyList())
        assertEquals(Degrau.RESISTENTE, r.degrau)
    }

    @Test
    fun `alvo sem armadura e sem traços fica Neutro`() {
        val r = DegrauRules.resolver(listOf("fogo"), emptyList(), emptyList())
        assertEquals(Degrau.NEUTRO, r.degrau)
        assertNull(r.origem)
        assertEquals(DegrauAplicado(), DegrauRules.resolver(emptyList(), listOf(t("fogo", Degrau.FRACO)), emptyList()))
    }

    @Test
    fun `arredonda para baixo só no resultado`() {
        assertEquals(10, DegrauRules.aplicar(7, Degrau.FRACO))      // 10,5
        assertEquals(3, DegrauRules.aplicar(7, Degrau.RESISTENTE))  // 3,5
        assertEquals(7, DegrauRules.aplicar(7, Degrau.NEUTRO))
        assertEquals(0, DegrauRules.aplicar(7, Degrau.IMUNE))
        assertEquals(0, DegrauRules.aplicar(1, Degrau.RESISTENTE))
    }

    @Test
    fun `texto do degrau`() {
        assertEquals("Fogo: Fraco x1,5", DegrauAplicado(Degrau.FRACO, "fogo", "Fogo").texto)
        assertEquals("Corte: Resistente x0,5", DegrauAplicado(Degrau.RESISTENTE, "corte", "Corte").texto)
        assertEquals("Veneno: Imune x0", DegrauAplicado(Degrau.IMUNE, "veneno", "Veneno").texto)
    }
}
