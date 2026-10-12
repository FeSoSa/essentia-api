package com.rpg.essentia.service

import com.rpg.essentia.model.*

/** Proficiência em categorias de arma (espelhado em essentia-player/lib/rules.ts). */
object ProficienciaRules {
    fun tem(proficiencias: List<String>, categoria: String?): Boolean =
        !categoria.isNullOrBlank() && proficiencias.any { it.equals(categoria, ignoreCase = true) }

    /** Ataque básico: com proficiência na arma 5,0; desarmado ou sem proficiência 6,0. */
    fun equilibrioAtaqueBasico(arma: WeaponEquip?, proficiencias: List<String>, cfg: CombateConfig): Double =
        if (arma != null && tem(proficiencias, arma.weaponType)) cfg.equilibrioComProficiencia
        else cfg.equilibrioSemProficiencia

    /** A propriedade especial da arma só ativa com proficiência na categoria dela. */
    fun propriedadeAtiva(arma: WeaponEquip, proficiencias: List<String>): Boolean =
        tem(proficiencias, arma.weaponType)

    /**
     * Skill de arma: exige proficiência em ao menos uma categoria da skill que esteja equipada.
     * Retorna null se pode usar, ou o nome da categoria que falta.
     */
    fun categoriaSemProficiencia(skill: Skill, equipment: Equipment, proficiencias: List<String>): String? {
        if (skill.type != "weapon") return null
        val categorias = skill.categoriasArma()
        if (categorias.isEmpty()) return null
        val equipadas = listOfNotNull(equipment.mainHand?.weaponType, equipment.offHand?.weaponType)
            .map { it.lowercase() }
            .filter { it in categorias }
        if (equipadas.any { tem(proficiencias, it) }) return null
        return equipadas.firstOrNull() ?: categorias.first()
    }
}

/** Capacidade de Essência e penalidade por excedente (espelhado em essentia-player/lib/rules.ts). */
object EssenciaRules {
    const val CAPACIDADE_BASE = 4
    const val NIVEL_MIN = 1
    const val NIVEL_MAX = 4

    /** sabEfetivo: valor de SAB (não o mod); a tabela de modificadores é a de CombatRules. */
    fun estado(niveis: List<Int>, sabEfetivo: Int): EstadoEssencia {
        val capacidade = CAPACIDADE_BASE + CombatRules.getModifier(sabEfetivo)
        val uso = niveis.sumOf { it.coerceIn(NIVEL_MIN, NIVEL_MAX) }
        val excedente = (uso - capacidade).coerceAtLeast(0)
        val (res, flx) = when {
            excedente >= 3 -> -9 to -6
            excedente == 2 -> -6 to -3
            excedente == 1 -> -3 to 0
            else           -> 0 to 0
        }
        return EstadoEssencia(
            capacidade = capacidade,
            uso = uso,
            excedente = excedente,
            penalidadeRes = res,
            penalidadeFlx = flx,
            testeSabDescanso = excedente >= 3
        )
    }

    fun estado(player: Player, sabEfetivo: Int): EstadoEssencia =
        estado(player.essenciasObtidas.map { it.nivel }, sabEfetivo)

    /** % do bônus total em cada nível, para Essência sem tabela própria (e para gerar a tabela inicial). */
    val PCT_PADRAO = listOf(25, 50, 75, 100)

    /**
     * Bônus de atributo da Essência no nível do jogador: a soma da tabela (bonusPorNivel) do nível 1 até o nível dele.
     * Sem tabela, vale a regra padrão: floor(bônus total × % do nível / 100).
     */
    fun bonusNoNivel(essencia: Essencia, nivel: Int): Map<String, Int> {
        val n = nivel.coerceIn(NIVEL_MIN, NIVEL_MAX)
        return if (essencia.bonusPorNivel.isNotEmpty()) somar(essencia.bonusPorNivel.take(n))
        else bonusPorPct(essencia.attributeBonus, n)
    }

    /** Regra padrão: floor(bônus × % do nível / 100), sem mudar o sinal. Ex: +6 FOR vira 1, 3, 4 e 6. */
    fun bonusPorPct(bonus: Map<String, Int>, nivel: Int): Map<String, Int> {
        if (nivel < NIVEL_MIN) return emptyMap()
        val pct = PCT_PADRAO[nivel.coerceAtMost(NIVEL_MAX) - 1]
        return bonus.mapValues { (_, v) -> Integer.signum(v) * (kotlin.math.abs(v) * pct / 100) }.filterValues { it != 0 }
    }

    /** Tabela por nível que reproduz a regra padrão (quanto cada nível soma). */
    fun tabelaPadrao(bonus: Map<String, Int>): List<Map<String, Int>> =
        (NIVEL_MIN..NIVEL_MAX).map { n ->
            val agora = bonusPorPct(bonus, n)
            val antes = bonusPorPct(bonus, n - 1)
            (agora.keys + antes.keys).associateWith { (agora[it] ?: 0) - (antes[it] ?: 0) }.filterValues { it != 0 }
        }

    /** Tabela com 4 níveis e sem zeros. */
    fun normalizarTabela(tabela: List<Map<String, Int>>): List<Map<String, Int>> =
        (0 until NIVEL_MAX).map { i -> (tabela.getOrNull(i) ?: emptyMap()).filterValues { it != 0 } }

    fun somar(mapas: List<Map<String, Int>>): Map<String, Int> =
        mapas.fold(mutableMapOf<String, Int>()) { acc, m -> m.forEach { (k, v) -> acc.merge(k, v, Int::plus) }; acc }
            .filterValues { it != 0 }
}

/** Tier de skill: custo mínimo por tier (o equilíbrio vem de Skill.equilibrio). */
object TierRules {
    /** Custo de Fluxo da skill que precisa respeitar o custo base do tier (null = sem custo de Fluxo). */
    fun custoFluxo(skill: Skill): Int? = skill.costs.firstOrNull { it.type == "flow" }?.value

    /** Mensagem de erro se o custo de Fluxo estiver abaixo do custo base do tier; null se ok. */
    fun erroCusto(skill: Skill, cfg: CombateConfig): String? {
        val tier = skill.tier ?: return null
        val custo = custoFluxo(skill) ?: return null
        val base = cfg.tier(tier).custoBase
        return if (custo < base) "Custo $custo abaixo do custo base do Tier $tier ($base)" else null
    }

    /** Texto da fórmula, ex: "6 + d20×AGI/3,5". */
    fun formula(base: Int, atributo: String?, equilibrio: Double?): String = buildString {
        if (base != 0 || atributo.isNullOrBlank()) append(base)
        if (!atributo.isNullOrBlank() && equilibrio != null) {
            if (isNotEmpty()) append(" + ")
            append("d20×$atributo/${formatarEquilibrio(equilibrio)}")
        }
    }

    fun formatarEquilibrio(eq: Double): String =
        if (eq % 1.0 == 0.0) eq.toInt().toString() else eq.toString().replace('.', ',')
}
