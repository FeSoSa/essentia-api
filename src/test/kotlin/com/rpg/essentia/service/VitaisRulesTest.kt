package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.ClassKitRepository
import com.rpg.essentia.repository.EffectPresetRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/** Bônus de PV e Fluxo por nível da classe (PV × 20 por RES, Fluxo × 15 por FLX, o multiplicador atual). */
class VitaisRulesTest {

    private val artifice = 10 to 14
    private val arcanista = 6 to 18
    private val lutador = 14 to 10

    @Test
    fun `nivel 1 nao tem bonus da classe`() {
        assertEquals(0, VitaisRules.bonusClasse(19, 1))
        assertEquals(20 + 8 * 20, VitaisRules.pvMax(8, 1, artifice.first))
        assertEquals(20 + 16 * 15, VitaisRules.fluxoMax(16, 1, artifice.second))
    }

    @Test
    fun `exemplos do documento com o Fluxo no multiplicador atual`() {
        // Gnomo Artífice (RES 8, FLX 16), nível 10: PV 20 + 160 + 90 = 270; Fluxo 20 + 240 + 126 = 386
        assertEquals(270, VitaisRules.pvMax(8, 10, artifice.first))
        assertEquals(386, VitaisRules.fluxoMax(16, 10, artifice.second))
        // Elfa Arcanista (RES 8, FLX 10), nível 20: PV 20 + 160 + 114 = 294; Fluxo 20 + 150 + 342 = 512
        assertEquals(294, VitaisRules.pvMax(8, 20, arcanista.first))
        assertEquals(512, VitaisRules.fluxoMax(10, 20, arcanista.second))
        // Nakudama Lutador (RES 9, FLX 12), nível 10: PV 20 + 180 + 126 = 326; Fluxo 20 + 180 + 90 = 290
        assertEquals(326, VitaisRules.pvMax(9, 10, lutador.first))
        assertEquals(290, VitaisRules.fluxoMax(12, 10, lutador.second))
        // Lutador nível 20: PV 20 + 180 + 266 = 466
        assertEquals(466, VitaisRules.pvMax(9, 20, lutador.first))
    }

    @Test
    fun `subir de nivel soma no atual o que o maximo ganhou, sem curar tudo`() {
        val kit = ClassKit(skillClass = "Artífice", pvPorNivel = 10, fluxoPorNivel = 14)
        val service = AttributeService(stub(), stubKits(kit))
        val attrs = Attributes(resistance = 8, flow = 16)
        // nível 1, ferido: 100/180 de PV e 200/260 de Fluxo
        val antes = Player(
            id = "p", code = "c", char = CharInfo(skillClass = "Artífice", level = 1), attributes = attrs,
            hp = Vital(100, 180), flow = Vital(200, 260)
        )
        val depois = service.recalculateVitals(antes.copy(char = antes.char.copy(level = 2)), attrs, somarGanho = true)
        assertEquals(Vital(110, 190), depois.hp)
        assertEquals(Vital(214, 274), depois.flow)
        assertEquals(VitaisClasse(10, 14, 10, 14), depois.vitaisClasse)
        // recálculo sem subida (equipar, efeito): só corta, não soma
        val semGanho = service.recalculateVitals(depois, attrs)
        assertEquals(Vital(110, 190), semGanho.hp)
    }

    private fun stub(): EffectPresetRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EffectPresetRepository::class.java)) { _, _, _ -> null } as EffectPresetRepository

    private fun stubKits(kit: ClassKit): ClassKitRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ClassKitRepository::class.java)) { _, m, args ->
            if (m.name == "findByClassName" && args?.firstOrNull() == kit.skillClass) kit else null
        } as ClassKitRepository
}
