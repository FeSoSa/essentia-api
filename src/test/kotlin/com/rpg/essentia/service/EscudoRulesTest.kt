package com.rpg.essentia.service

import com.rpg.essentia.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Escudo: PV temporário por cima da vida, teto de 25% do PV máximo. */
class EscudoRulesTest {

    private fun player(atual: Int, max: Int, escudo: Int = 0) = Player(id = "p", code = "c", hp = Vital(atual, max), escudo = escudo)

    @Test
    fun `exemplo do documento passo a passo`() {
        // PV máximo 326, atual 300, Escudo 0. Teto = floor(326 × 0,25) = 81
        var p = player(300, 326)
        assertEquals(81, EscudoRules.teto(326))

        // 1. "+40 PV" de skill vira Escudo; a vida não muda
        p = EscudoRules.ganhar(p, 40)
        assertEquals(40, p.escudo); assertEquals(300, p.hp.current); assertEquals(326, p.hp.max)

        // 2. 50 de dano: Escudo absorve 40, vida perde 10
        p = EscudoRules.receberDano(p, 50)
        assertEquals(0, p.escudo); assertEquals(290, p.hp.current)

        // 3. Cura de +60: vida vai a 326, excedente de 24 perdido, Escudo continua 0
        p = EscudoRules.curar(p, 60)
        assertEquals(326, p.hp.current); assertEquals(0, p.escudo)

        // 4. "+40 PV" duas vezes: 80; de novo: 81 (teto)
        p = EscudoRules.ganhar(EscudoRules.ganhar(p, 40), 40)
        assertEquals(80, p.escudo)
        p = EscudoRules.ganhar(p, 40)
        assertEquals(81, p.escudo)

        // 5. Poção com PV cheio: nada muda (cura nunca gera Escudo)
        val depoisPocao = EscudoRules.curar(p, 30)
        assertEquals(326, depoisPocao.hp.current); assertEquals(81, depoisPocao.escudo)
    }

    @Test
    fun `dano menor que o Escudo nao toca na vida e vida nao fica negativa`() {
        val p = EscudoRules.receberDano(player(100, 200, escudo = 30), 20)
        assertEquals(10, p.escudo); assertEquals(100, p.hp.current)
        val morto = EscudoRules.receberDano(player(10, 200, escudo = 5), 50)
        assertEquals(0, morto.escudo); assertEquals(0, morto.hp.current)
    }

    @Test
    fun `skill Geral nao usa tier, de classe com classe usa`() {
        fun s(tipo: String, classe: String?) = Skill(name = "x", desc = "", type = tipo, skillClass = classe, weaponType = null,
            essenciaId = null, costs = emptyList(), damage = null, cooldownTurns = 0, ultimate = false)
        assertEquals(false, s("class", null).usaTier())
        assertEquals(true, s("class", "Guerreiro").usaTier())
        assertEquals(true, s("weapon", null).usaTier())
        assertEquals(false, s("mestre", null).usaTier())
    }

    @Test
    fun `PV maximo menor corta o Escudo no novo teto`() {
        val p = EscudoRules.ajustarTeto(player(100, 200, escudo = 50).copy(hp = Vital(100, 120)))
        assertEquals(30, p.escudo)
    }

    @Test
    fun `efeito ESCUDO no usuario soma os valores da skill`() {
        val s = Skill(
            name = "Égide", desc = "", type = "class", skillClass = null, weaponType = null, essenciaId = null,
            costs = emptyList(), damage = null, cooldownTurns = 0, ultimate = false,
            efeitos = listOf(
                Efeito(alvo = "SELF", tipo = "ESCUDO", valor = 25),
                Efeito(alvo = "ALIADOS", tipo = "ESCUDO", valor = 40),
                Efeito(alvo = "SELF", tipo = "MODIFICADOR", stat = "DEF", valor = 1, duracao = 2)
            )
        )
        assertEquals(25, EfeitoRules.escudoNoUsuario(s))
    }

    @Test
    fun `efeitos em aliados separam cura na hora, Escudo e status`() {
        val s = Skill(
            name = "Resplandecência", desc = "", type = "essencia", skillClass = null, weaponType = null, essenciaId = null,
            costs = emptyList(), damage = null, cooldownTurns = 0, ultimate = false, alvo = "ALIADOS",
            efeitos = listOf(
                Efeito(alvo = "ALIADOS", tipo = "CURA", valor = 20),
                Efeito(alvo = "ALIADOS", tipo = "CURA", valor = 4, duracao = 2),
                Efeito(alvo = "ALIADOS", tipo = "ESCUDO", valor = 50),
                Efeito(alvo = "ALIADOS", tipo = "MODIFICADOR", stat = "DEF", valor = 1, duracao = 2),
                Efeito(alvo = "SELF", tipo = "CURA", valor = 99)          // não vai para os aliados
            )
        )
        val (cura, escudo, status) = EfeitoRules.paraAliados(s, emptyList())
        assertEquals(20, cura)
        assertEquals(50, escudo)
        assertEquals(2, status.size)   // cura por turno + modificador de DEF
    }

    @Test
    fun `efeito CURA instantanea vai na vida e por turno vira regeneracao, nunca Escudo`() {
        val s = Skill(
            name = "Prece", desc = "", type = "class", skillClass = null, weaponType = null, essenciaId = null,
            costs = emptyList(), damage = null, cooldownTurns = 0, ultimate = false,
            efeitos = listOf(
                Efeito(alvo = "SELF", tipo = "CURA", valor = 30),
                Efeito(alvo = "SELF", tipo = "CURA", valor = 5, duracao = 3)
            )
        )
        assertEquals(30, EfeitoRules.curaNoUsuario(s))
        val regen = EfeitoRules.noUsuario(s, emptyList()).single()
        assertEquals(3, regen.durationTurns)
        assertEquals("heal_hp", regen.effects.single().type)
        assertEquals(5, regen.effects.single().value)
        // cura com PV quase cheio: o que passa do máximo é perdido e o Escudo não muda
        val p = EscudoRules.curar(player(310, 326, escudo = 10), EfeitoRules.curaNoUsuario(s))
        assertEquals(326, p.hp.current); assertEquals(10, p.escudo)
    }
}
