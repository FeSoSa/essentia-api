package com.rpg.essentia.service

import com.rpg.essentia.model.Vital

/**
 * PV e Fluxo máximos com o bônus por nível da classe (espelhado em essentia-player/components/screens/atributos-screen.tsx).
 *   PV máximo    = 20 + RES × 20 + pvPorNivel × (nível - 1)
 *   Fluxo máximo = 20 + FLX × 15 + fluxoPorNivel × (nível - 1)
 * No nível 1 o bônus da classe é zero.
 */
object VitaisRules {
    fun bonusClasse(porNivel: Int, nivel: Int): Int = porNivel * (nivel - 1).coerceAtLeast(0)

    fun pvMax(resistencia: Int, nivel: Int, pvPorNivel: Int): Int =
        HP_BASE + resistencia * HP_PER_RESISTANCE + bonusClasse(pvPorNivel, nivel)

    fun fluxoMax(fluxo: Int, nivel: Int, fluxoPorNivel: Int): Int =
        FLOW_BASE + fluxo * FLOW_PER_FLOW_ATTR + bonusClasse(fluxoPorNivel, nivel)

    /**
     * Novo máximo. Com `somarGanho` (subiu de nível, ou o bônus da classe entrou), o atual soma o mesmo
     * que o máximo aumentou (não cura tudo). Sem isso, o atual só é cortado se ficar acima do máximo.
     */
    fun ajustar(vital: Vital, novoMax: Int, somarGanho: Boolean): Vital {
        val ganho = if (somarGanho) (novoMax - vital.max).coerceAtLeast(0) else 0
        return vital.copy(max = novoMax, current = (vital.current + ganho).coerceAtMost(novoMax))
    }
}
