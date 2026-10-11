package com.rpg.essentia.service

import com.rpg.essentia.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BuffRulesTest {

    @Test
    fun `dano normal usa floor de d20 x mod sobre equilibrio`() {
        val c = CombateConfig()
        assertEquals(20 + 7, CombatRules.dano(20, 15, 3, 6, false, c)) // 45/6 = 7,5
        assertEquals(10 - 3, CombatRules.dano(10, 15, -1, 6, false, c)) // -2,5 vira -3
    }

    @Test
    fun `critico multiplica base e d20 conforme a config`() {
        val c = CombateConfig(critMultBase = 1.5, critMultDado = 2.0)
        assertEquals(30 + 15, CombatRules.dano(20, 15, 3, 6, true, c))
        val soDado = CombateConfig(critMultBase = 1.0, critMultDado = 2.0)
        assertEquals(20 + 15, CombatRules.dano(20, 15, 3, 6, true, soDado))
    }

    @Test
    fun `atributo multiplo usa o maior mod`() {
        assertEquals(4, CombatRules.resolveAtributo("FOR/AGI", mapOf("strength" to 12, "agility" to 25)))
        assertEquals(0, CombatRules.resolveAtributo(null, emptyMap()))
    }
}
