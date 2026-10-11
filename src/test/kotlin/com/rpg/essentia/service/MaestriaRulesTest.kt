package com.rpg.essentia.service

import com.rpg.essentia.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Maestria por grupo (arma ou Essência) e requisitos de skill por origem e tier. */
class MaestriaRulesTest {

    private val cfg = MaestriaConfig()
    private val combate = CombateConfig(critMultBase = 1.5, critMultDado = 2.0)
    private val fogo = Essencia(id = "fogo", name = "Fogo", type = "Elemental", desc = "", attributeBonus = emptyMap(), skillIds = emptyList())
    private val brasa = Essencia(id = "brasa", name = "Brasa", type = "Derivada", desc = "", attributeBonus = emptyMap(), skillIds = emptyList(), parentId = "fogo", nivelMaeNecessario = 2)
    private val essencias = listOf(fogo, brasa)

    private fun espada() = WeaponEquip(id = "e", name = "Espada", weaponType = "espadas", damageBase = 6, damageAttribute = "FOR")

    private fun player(
        proficiencias: List<String> = listOf("espadas"),
        maestrias: List<MaestriaGrupo> = listOf(MaestriaGrupo("espadas", "ARMA", 1, 0)),
        nivelClasse: Int = 1,
        essencias: List<EssenciaObtida> = emptyList(),
        arma: WeaponEquip? = espada()
    ) = Player(
        id = "p", code = "c", proficiencias = proficiencias, maestrias = maestrias, nivelClasse = nivelClasse,
        essenciasObtidas = essencias, equipment = Equipment(mainHand = arma)
    )

    private fun skill(type: String, tier: Int, weaponTypes: List<String>? = null, essenciaId: String? = null, grupo: String? = null) = Skill(
        id = "s", name = "Teste", desc = "", type = type, skillClass = null, weaponType = null, weaponTypes = weaponTypes,
        essenciaId = essenciaId, costs = listOf(Cost("flow", 20, null)), damage = DamageFormula("", 6, "FOR"),
        cooldownTurns = 0, ultimate = false, tier = tier, grupoMaestria = grupo
    )

    // ── Uso e nível ──────────────────────────────────────────────────────

    @Test
    fun `maestria de arma nao sobe sem proficiencia`() {
        val p = player(proficiencias = emptyList())
        assertNull(MaestriaRules.registrarUso(p, "espadas", "combate-1"))
        assertEquals(0 to 0, MaestriaRules.bonus(p.copy(maestrias = listOf(MaestriaGrupo("espadas", "ARMA", 3, 12))), "espadas", cfg))
    }

    @Test
    fun `todo uso em combate conta para o grupo`() {
        val p = player()
        val depois1 = MaestriaRules.registrarUso(p, "espadas", "combate-1")!!
        assertEquals(1, depois1.single().usos)
        // outra skill do mesmo grupo na mesma batalha: conta também
        assertEquals(2, MaestriaRules.registrarUso(p.copy(maestrias = depois1), "espadas", "combate-1")!!.single().usos)
        // fora de combate: não conta
        assertNull(MaestriaRules.registrarUso(p, "espadas", null))
    }

    @Test
    fun `maestria de Essencia Elemental nao sobe com uso`() {
        val p = player(essencias = listOf(EssenciaObtida("fogo"), EssenciaObtida("mente")),
            maestrias = listOf(MaestriaGrupo("fogo", "ESSENCIA", 1, 0), MaestriaGrupo("mente", "ESSENCIA", 1, 0)))
        val mente = Essencia(id = "mente", name = "Mente", type = "Mitica", desc = "", attributeBonus = emptyMap(), skillIds = emptyList())
        val todas = essencias + mente
        assertNull(MaestriaRules.registrarUso(p, "fogo", "combate-1", todas))
        assertEquals(1, MaestriaRules.registrarUso(p, "mente", "combate-1", todas)!!.first { it.grupo == "mente" }.usos)
        // nome antigo "Grande" também conta como Elemental
        assertFalse(MaestriaRules.sobePorUso(MaestriaGrupo("fogo", "ESSENCIA"), listOf(fogo.copy(type = "Grande"))))
        assertFalse(MaestriaRules.info(p, p.maestrias.first(), cfg, todas).disponivel)
    }

    @Test
    fun `nivel fica disponivel nos usos da tabela e o mestre confirma, com teto no 4`() {
        assertFalse(MaestriaRules.disponivel(MaestriaGrupo("espadas", "ARMA", 1, 4), cfg))
        assertTrue(MaestriaRules.disponivel(MaestriaGrupo("espadas", "ARMA", 1, 5), cfg))
        assertEquals(2, MaestriaRules.confirmarNivel(MaestriaGrupo("espadas", "ARMA", 1, 5), cfg)!!.nivel)
        assertTrue(MaestriaRules.disponivel(MaestriaGrupo("espadas", "ARMA", 3, 22), cfg))
        val quatro = MaestriaGrupo("espadas", "ARMA", 4, 99)
        assertFalse(MaestriaRules.disponivel(quatro, cfg))
        assertNull(MaestriaRules.confirmarNivel(quatro, cfg))
    }

    @Test
    fun `receber proficiencia ou Essencia entra na maestria nivel 1`() {
        val p = MaestriaRules.garantir(player(proficiencias = listOf("espadas", "arcos"), maestrias = emptyList(),
            essencias = listOf(EssenciaObtida("fogo"))))
        assertEquals(setOf("espadas" to "ARMA", "arcos" to "ARMA", "fogo" to "ESSENCIA"), p.maestrias.map { it.grupo to it.tipoGrupo }.toSet())
        assertTrue(p.maestrias.all { it.nivel == 1 && it.usos == 0 })
    }

    // ── Bônus ────────────────────────────────────────────────────────────

    @Test
    fun `bonus de dano e custo valem para todas as skills do grupo`() {
        val p = player(maestrias = listOf(MaestriaGrupo("espadas", "ARMA", 3, 12)))
        val a = skill("weapon", 1, listOf("espadas"))
        val b = skill("weapon", 2, listOf("espadas", "rapieiras"))
        listOf(a, b).forEach { s ->
            val grupo = MaestriaRules.grupoDaSkill(s, p, essencias)!!.first
            assertEquals(20 to 15, MaestriaRules.bonus(p, grupo, cfg))
        }
        // custo: floor(20 × (1 - 0,15)) = 17
        assertEquals(17, CombatRules.custoFinal(20, 0.15))
        // custo: floor(25 × 0,9) = 22,5 -> 22
        assertEquals(22, CombatRules.custoFinal(25, 0.10))
    }

    @Test
    fun `bonus de dano vale no ataque com a arma da categoria`() {
        val p = player(maestrias = listOf(MaestriaGrupo("espadas", "ARMA", 4, 22)))
        val (danoPct, _) = MaestriaRules.bonus(p, p.equipment.mainHand!!.weaponType, cfg)
        val eq = ProficienciaRules.equilibrioAtaqueBasico(p.equipment.mainHand, p.proficiencias, combate)
        // floor((6 + 12 × 3 / 5) × 1,3) = floor(13,2 × 1,3) = floor(17,16) = 17
        assertEquals(17, CombatRules.dano(6, 12, 3, eq, false, combate, danoPct / 100.0))
    }

    @Test
    fun `calculo final com equilibrio decimal e floor so no fim`() {
        // tier 2 (3,5), d20 13, mod +3, base 6, maestria +10%: floor((6 + 39/3,5) × 1,1) = floor(18,857) = 18
        assertEquals(18, CombatRules.dano(6, 13, 3, 3.5, false, combate, 0.10))
        // floor só no fim: floor((5 + 22/2,5) × 1,1) = floor(15,18) = 15; com floor no meio daria floor(13 × 1,1) = 14
        assertEquals(15, CombatRules.dano(5, 11, 2, 2.5, false, combate, 0.10))
        // crítico: floor((6 × 1,5 + 13 × 2 × 3 / 3,5) × 1,2) = floor(31,286 × 1,2) = floor(37,54) = 37
        assertEquals(37, CombatRules.dano(6, 13, 3, 3.5, true, combate, 0.20))
    }

    // ── Requisitos por origem e tier ─────────────────────────────────────

    @Test
    fun `classe pede nivel de classe maior ou igual ao tier`() {
        val s = skill("class", 3)
        assertNotNull(MaestriaRules.requisito(s, player(nivelClasse = 2), essencias))
        assertNull(MaestriaRules.requisito(s, player(nivelClasse = 3), essencias))
    }

    @Test
    fun `essencia pede nivel da Essencia e Derivada sem posse usa a mae`() {
        val s = skill("essencia", 2, essenciaId = "fogo")
        assertNotNull(MaestriaRules.requisito(s, player(essencias = listOf(EssenciaObtida("fogo", nivel = 1))), essencias))
        assertNull(MaestriaRules.requisito(s, player(essencias = listOf(EssenciaObtida("fogo", nivel = 2))), essencias))
        val deBrasa = skill("essencia", 2, essenciaId = "brasa")
        val soMae = player(essencias = listOf(EssenciaObtida("fogo", nivel = 2)))
        assertNull(MaestriaRules.requisito(deBrasa, soMae, essencias))
        assertEquals("fogo" to "ESSENCIA", MaestriaRules.grupoDaSkill(deBrasa, soMae, essencias))
        val comBrasa = player(essencias = listOf(EssenciaObtida("fogo", nivel = 2), EssenciaObtida("brasa", nivel = 1)))
        assertNotNull(MaestriaRules.requisito(deBrasa, comBrasa, essencias))
    }

    @Test
    fun `Derivada so e liberada pela mae a partir do nivel configurado`() {
        // Brasa pede Fogo nível 2
        assertFalse(MaestriaRules.essenciaAcessivel("brasa", player(essencias = listOf(EssenciaObtida("fogo", nivel = 1))), essencias))
        assertTrue(MaestriaRules.essenciaAcessivel("brasa", player(essencias = listOf(EssenciaObtida("fogo", nivel = 2))), essencias))
        // sem nível configurado: só tendo a própria Derivada
        val semMae = listOf(fogo, brasa.copy(nivelMaeNecessario = null))
        assertFalse(MaestriaRules.essenciaAcessivel("brasa", player(essencias = listOf(EssenciaObtida("fogo", nivel = 4))), semMae))
        assertTrue(MaestriaRules.essenciaAcessivel("brasa", player(essencias = listOf(EssenciaObtida("brasa", nivel = 1))), semMae))
    }

    @Test
    fun `arma pede maestria da categoria e da arma usada quando a skill tem varias`() {
        val s = skill("weapon", 2, listOf("espadas", "rapieiras"))
        assertNotNull(MaestriaRules.requisito(s, player(maestrias = listOf(MaestriaGrupo("espadas", "ARMA", 1, 0))), essencias))
        assertNull(MaestriaRules.requisito(s, player(maestrias = listOf(MaestriaGrupo("espadas", "ARMA", 2, 5))), essencias))
        // com rapieira na mão: precisa também de maestria de rapieiras
        val rapieira = WeaponEquip(id = "r", name = "Rapieira", weaponType = "rapieiras", damageBase = 5, damageAttribute = "AGI")
        val semRapieira = player(proficiencias = listOf("espadas", "rapieiras"), arma = rapieira,
            maestrias = listOf(MaestriaGrupo("espadas", "ARMA", 2, 5)))
        assertNotNull(MaestriaRules.requisito(s, semRapieira, essencias))
        val comRapieira = semRapieira.copy(maestrias = semRapieira.maestrias + MaestriaGrupo("rapieiras", "ARMA", 2, 5))
        assertNull(MaestriaRules.requisito(s, comRapieira, essencias))
        // sem proficiência a maestria não vale
        assertNotNull(MaestriaRules.requisito(s, player(proficiencias = emptyList(), maestrias = listOf(MaestriaGrupo("espadas", "ARMA", 4, 22))), essencias))
    }
}
