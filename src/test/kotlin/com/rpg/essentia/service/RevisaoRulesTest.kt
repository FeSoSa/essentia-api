package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EffectPresetRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Revisão de skills, armas e Essências: tier, fórmula decimal, proficiência, efeitos e Capacidade. */
class RevisaoRulesTest {

    private val cfg = CombateConfig(critMultBase = 1.5, critMultDado = 2.0)

    private fun skill(tier: Int? = 2, type: String = "class", weaponTypes: List<String>? = null, efeitos: List<Efeito> = emptyList()) = Skill(
        name = "Teste", desc = "", type = type, skillClass = null, weaponType = null, weaponTypes = weaponTypes,
        essenciaId = null, costs = listOf(Cost("flow", 15, null)),
        damage = DamageFormula("", 6, "AGI"), cooldownTurns = 0, ultimate = false, tier = tier, efeitos = efeitos
    )

    private fun arma(tipo: String) = WeaponEquip(id = "a", name = "Arma", weaponType = tipo, damageBase = 5, damageAttribute = "FOR")

    // ── Fórmula ──────────────────────────────────────────────────────────

    @Test
    fun `tier define o equilibrio`() {
        assertEquals(listOf(4.0, 3.5, 3.0, 2.5), (1..4).map { skill(tier = it).equilibrio(cfg) })
        assertEquals(listOf(8, 15, 25, 40), (1..4).map { cfg.tier(it).custoBase })
    }

    @Test
    fun `exemplo do documento tier 2 d20 13 mod 3 base 6 da 17`() {
        // 6 + 39/3,5 = 17,14 -> 17
        assertEquals(17, CombatRules.dano(6, 13, 3, skill(tier = 2).equilibrio(cfg)!!, false, cfg))
    }

    @Test
    fun `floor so no resultado final`() {
        // 7 + 10×3/3,5 = 15,57 -> 15 (sem arredondar a parte do dado antes)
        assertEquals(15, CombatRules.dano(7, 10, 3, 3.5, false, cfg))
        // divisão exata não perde 1 por erro de ponto flutuante: 21/3,5 = 6
        assertEquals(6, CombatRules.dano(0, 7, 3, 3.5, false, cfg))
        // 5 × 3 / 2,5 = 6 exato
        assertEquals(6, CombatRules.dano(0, 5, 3, 2.5, false, cfg))
        // mod negativo: 10 - 2,5 = 7,5 -> 7
        assertEquals(7, CombatRules.dano(10, 5, -1, 2.0, false, cfg))
    }

    @Test
    fun `critico dobra o d20 e usa um floor unico`() {
        // 7 × 1,5 + 10 × 2 × 3 / 3,5 = 10,5 + 17,14 = 27,64 -> 27
        assertEquals(27, CombatRules.dano(7, 10, 3, 3.5, true, cfg))
        // exemplo do documento no crítico: 6 × 1,5 + 13 × 2 × 3 / 3,5 = 9 + 22,28 = 31,28 -> 31
        assertEquals(31, CombatRules.dano(6, 13, 3, 3.5, true, cfg))
        // sem multiplicador de base: só o d20 dobra
        assertEquals(6 + 22, CombatRules.dano(6, 13, 3, 3.5, true, CombateConfig(critMultBase = 1.0, critMultDado = 2.0)))
    }

    @Test
    fun `skill sem tier usa o equilibrio salvo`() {
        val inimigo = skill(tier = null, type = "inimigo").copy(damage = DamageFormula("", 6, "FOR", 7))
        assertEquals(7.0, inimigo.equilibrio(cfg))
    }

    @Test
    fun `custo abaixo do base do tier e recusado`() {
        assertNull(TierRules.erroCusto(skill(tier = 2), cfg))                                     // 15 = base do tier 2
        assertTrue(TierRules.erroCusto(skill(tier = 3), cfg)!!.contains("25"))                     // 15 < 25
        assertNull(TierRules.erroCusto(skill(tier = 3).copy(costs = emptyList()), cfg))            // sem custo de Fluxo
    }

    @Test
    fun `texto da formula usa virgula no equilibrio decimal`() {
        assertEquals("6 + d20×AGI/3,5", TierRules.formula(6, "AGI", 3.5))
        assertEquals("12 + d20×INT/4", TierRules.formula(12, "INT", 4.0))
    }

    // ── Ataque básico e proficiência ─────────────────────────────────────

    @Test
    fun `ataque basico com proficiencia usa 5 e sem proficiencia usa 6`() {
        val espada = arma("espadas")
        assertEquals(5.0, ProficienciaRules.equilibrioAtaqueBasico(espada, listOf("espadas"), cfg))
        assertEquals(6.0, ProficienciaRules.equilibrioAtaqueBasico(espada, listOf("adagas"), cfg))
        assertEquals(6.0, ProficienciaRules.equilibrioAtaqueBasico(null, listOf("espadas"), cfg))  // desarmado
        // dano da arma sem proficiência: 5 + 12 × 2 / 6 = 9
        assertEquals(9, CombatRules.dano(5, 12, 2, ProficienciaRules.equilibrioAtaqueBasico(espada, emptyList(), cfg), false, cfg))
        // com proficiência: 5 + 24 / 5 = 9,8 -> 9; d20 15: 5 + 30/5 = 11
        assertEquals(11, CombatRules.dano(5, 15, 2, ProficienciaRules.equilibrioAtaqueBasico(espada, listOf("espadas"), cfg), false, cfg))
    }

    @Test
    fun `sem proficiencia bloqueia skill da categoria`() {
        val eq = Equipment(mainHand = arma("espadas"))
        val skillEspada = skill(type = "weapon", weaponTypes = listOf("espadas", "rapieiras"))
        assertEquals("espadas", ProficienciaRules.categoriaSemProficiencia(skillEspada, eq, emptyList()))
        assertNull(ProficienciaRules.categoriaSemProficiencia(skillEspada, eq, listOf("espadas")))
        // proficiência em outra categoria da skill que não está equipada não libera
        assertEquals("espadas", ProficienciaRules.categoriaSemProficiencia(skillEspada, eq, listOf("rapieiras")))
        // skill de classe não depende de proficiência
        assertNull(ProficienciaRules.categoriaSemProficiencia(skill(type = "class"), eq, emptyList()))
    }

    @Test
    fun `propriedade especial so ativa com proficiencia`() {
        val besta = arma("bestas")
        assertFalse(ProficienciaRules.propriedadeAtiva(besta, listOf("arcos")))
        assertTrue(ProficienciaRules.propriedadeAtiva(besta, listOf("Bestas")))
    }

    // ── Capacidade de Essência ───────────────────────────────────────────

    @Test
    fun `capacidade e 4 mais mod de SAB`() {
        assertEquals(4, EssenciaRules.estado(listOf(1), 10).capacidade)   // SAB 10 -> mod 0
        assertEquals(3, EssenciaRules.estado(listOf(1), 7).capacidade)    // SAB 7 -> mod -1
        assertEquals(6, EssenciaRules.estado(listOf(1), 18).capacidade)   // SAB 18 -> mod +2
    }

    @Test
    fun `bonus de atributo da Essencia sem tabela usa a regra padrao de porcentagem`() {
        val fogo = Essencia(name = "Fogo", type = "Elemental", desc = "", attributeBonus = mapOf("strength" to 6, "resistance" to 4), skillIds = emptyList())
        assertEquals(mapOf("strength" to 1, "resistance" to 1), EssenciaRules.bonusNoNivel(fogo, 1))
        assertEquals(mapOf("strength" to 3, "resistance" to 2), EssenciaRules.bonusNoNivel(fogo, 2))
        assertEquals(mapOf("strength" to 4, "resistance" to 3), EssenciaRules.bonusNoNivel(fogo, 3))
        assertEquals(fogo.attributeBonus, EssenciaRules.bonusNoNivel(fogo, 4))
        // valor que zera sai do mapa; negativo arredonda na direção do zero
        assertEquals(mapOf("wisdom" to 1), EssenciaRules.bonusPorPct(mapOf("wisdom" to 5, "presence" to 2), 1))
        assertEquals(mapOf("flow" to -1), EssenciaRules.bonusPorPct(mapOf("flow" to -6), 1))
    }

    @Test
    fun `bonus de atributo da Essencia com tabela soma os niveis ate o do jogador`() {
        val tabela = listOf(mapOf("strength" to 2), emptyMap(), mapOf("strength" to 1, "resistance" to 3), mapOf("resistance" to 1))
        val e = Essencia(name = "X", type = "Elemental", desc = "", attributeBonus = emptyMap(), skillIds = emptyList(), bonusPorNivel = tabela)
        assertEquals(mapOf("strength" to 2), EssenciaRules.bonusNoNivel(e, 1))
        assertEquals(mapOf("strength" to 2), EssenciaRules.bonusNoNivel(e, 2))
        assertEquals(mapOf("strength" to 3, "resistance" to 3), EssenciaRules.bonusNoNivel(e, 3))
        assertEquals(mapOf("strength" to 3, "resistance" to 4), EssenciaRules.bonusNoNivel(e, 4))
    }

    @Test
    fun `tabela padrao reproduz a porcentagem e soma o bonus total`() {
        val fogo = mapOf("strength" to 6, "resistance" to 4)
        val tabela = EssenciaRules.tabelaPadrao(fogo)
        assertEquals(listOf(mapOf("strength" to 1, "resistance" to 1), mapOf("strength" to 2, "resistance" to 1),
            mapOf("strength" to 1, "resistance" to 1), mapOf("strength" to 2, "resistance" to 1)), tabela)
        assertEquals(fogo, EssenciaRules.somar(tabela))
    }

    @Test
    fun `penalidade por degrau de excedente`() {
        // SAB 10: capacidade 4
        val zero = EssenciaRules.estado(listOf(2, 2), 10)
        assertEquals(listOf(4, 0, 0, 0), listOf(zero.uso, zero.excedente, zero.penalidadeRes, zero.penalidadeFlx))
        assertFalse(zero.testeSabDescanso)

        val um = EssenciaRules.estado(listOf(3, 2), 10)
        assertEquals(listOf(5, 1, -3, 0), listOf(um.uso, um.excedente, um.penalidadeRes, um.penalidadeFlx))
        assertFalse(um.testeSabDescanso)

        val dois = EssenciaRules.estado(listOf(4, 2), 10)
        assertEquals(listOf(6, 2, -6, -3), listOf(dois.uso, dois.excedente, dois.penalidadeRes, dois.penalidadeFlx))
        assertFalse(dois.testeSabDescanso)

        val tres = EssenciaRules.estado(listOf(4, 3), 10)
        assertEquals(listOf(7, 3, -9, -6), listOf(tres.uso, tres.excedente, tres.penalidadeRes, tres.penalidadeFlx))
        assertTrue(tres.testeSabDescanso)

        val cinco = EssenciaRules.estado(listOf(4, 4, 1), 10)
        assertEquals(listOf(-9, -6), listOf(cinco.penalidadeRes, cinco.penalidadeFlx))
        assertTrue(cinco.testeSabDescanso)
    }

    @Test
    fun `penalidade reduz atributo efetivo, PV e Fluxo maximos e corta o atual`() {
        // O repositório de presets não é usado no cálculo de atributos/vitais
        val presets = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(EffectPresetRepository::class.java)
        ) { _, _, _ -> null } as EffectPresetRepository
        val kits = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(com.rpg.essentia.repository.ClassKitRepository::class.java)
        ) { _, _, _ -> null } as com.rpg.essentia.repository.ClassKitRepository
        val service = AttributeService(presets, kits)
        val player = Player(
            id = "p", code = "c",
            attributes = Attributes(resistance = 12, flow = 10, wisdom = 10),
            hp = Vital(260, 260), flow = Vital(170, 170),
            essenciasObtidas = listOf(EssenciaObtida("a", nivel = 4), EssenciaObtida("b", nivel = 2))  // uso 6, excedente 2
        )
        val efetivo = service.computeEffectiveAttributes(player, emptyList())
        assertEquals(12 - 6, efetivo.resistance)
        assertEquals(10 - 3, efetivo.flow)
        val atualizado = service.recalculateVitals(player, efetivo)
        assertEquals(20 + 6 * 20, atualizado.hp.max)
        assertEquals(atualizado.hp.max, atualizado.hp.current)
        assertEquals(20 + 7 * FLOW_PER_FLOW_ATTR, atualizado.flow.max)
        assertEquals(atualizado.flow.max, atualizado.flow.current)
        assertEquals(2, atualizado.essencia!!.excedente)
        assertEquals(12, atualizado.attributes.resistance)   // pontos base não mudam

        // excedente acaba: penalidade sai sozinha
        val semExcesso = player.copy(essenciasObtidas = listOf(EssenciaObtida("a", nivel = 2), EssenciaObtida("b", nivel = 2)))
        assertEquals(12, service.computeEffectiveAttributes(semExcesso, emptyList()).resistance)
    }

    // ── Efeitos ──────────────────────────────────────────────────────────

    @Test
    fun `efeitos viram status no usuario, no alvo e passivos`() {
        val s = skill(efeitos = listOf(
            Efeito(alvo = "SELF", tipo = "MODIFICADOR", stat = "AGI", valor = 2, duracao = 2),
            Efeito(alvo = "SELF", tipo = "MODIFICADOR", stat = "ACERTO", valor = 1, duracao = 2),
            Efeito(alvo = "ALVO", tipo = "CONDICAO", condicao = "SANGRAMENTO", duracao = 3),
            Efeito(alvo = "SELF", tipo = "MODIFICADOR", stat = "DEF", valor = 1, duracao = "PASSIVO")
        )).copy(id = "s1")
        val presets = listOf(EffectPreset(name = "Sangramento", durationTurns = 2,
            effects = listOf(AutoEffect(trigger = "on_turn_start", type = "damage_hp", value = 6))))

        val usuario = EfeitoRules.noUsuario(s, presets)
        assertEquals(1, usuario.size)
        assertEquals(mapOf("agility" to 2), usuario[0].attributeBonus)
        assertEquals(1, usuario[0].hitBonus)
        assertEquals(2, usuario[0].durationTurns)

        val alvo = EfeitoRules.noAlvo(s, presets)
        assertEquals("Sangramento", alvo.single().name)
        assertEquals(3, alvo.single().durationTurns)                 // duração do efeito vence a do preset
        assertEquals("damage_hp", alvo.single().effects.single().type)

        val passivos = EfeitoRules.passivos(s, presets)
        assertEquals(mapOf("defense" to 1), passivos.single().attributeBonus)
        assertEquals(-1, passivos.single().durationTurns)
        assertEquals("s1", passivos.single().sourceSkillId)
    }
}
