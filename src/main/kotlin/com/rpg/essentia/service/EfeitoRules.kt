package com.rpg.essentia.service

import com.rpg.essentia.model.*
import java.util.UUID

/**
 * Converte a lista `efeitos` de uma skill nos StatusEffect aplicados em jogo.
 * StatusEffect continua sendo o formato salvo em jogadores e inimigos.
 */
object EfeitoRules {
    private val ALVOS_INIMIGO = setOf(Efeito.ALVO_ALVO, Efeito.ALVO_AREA)

    /** Efeitos aplicados no alvo ao acertar (ALVO e AREA). */
    fun noAlvo(skill: Skill, presets: List<EffectPreset>): List<StatusEffect> =
        converter(skill, skill.efeitos.filter { it.alvo in ALVOS_INIMIGO }, presets, rodadasPadrao = 1)

    /** Efeitos no próprio usuário ao usar a skill (SELF com duração em rodadas). */
    fun noUsuario(skill: Skill, presets: List<EffectPreset>): List<StatusEffect> =
        converter(skill, skill.efeitos.filter { it.alvo == Efeito.ALVO_SELF && !it.passivo }, presets, rodadasPadrao = 1)

    /** Efeitos passivos enquanto a skill estiver equipada (SELF com duração PASSIVO). */
    fun passivos(skill: Skill, presets: List<EffectPreset>): List<StatusEffect> =
        converter(skill, skill.efeitos.filter { it.alvo == Efeito.ALVO_SELF && it.passivo }, presets, rodadasPadrao = -1)
            .mapIndexed { i, se -> se.copy(id = "${skill.id}-passivo-$i", durationTurns = -1, sourceSkillId = skill.id) }

    private fun converter(skill: Skill, efeitos: List<Efeito>, presets: List<EffectPreset>, rodadasPadrao: Int): List<StatusEffect> {
        val condicoes = efeitos.filter { it.tipo == Efeito.TIPO_CONDICAO && !it.condicao.isNullOrBlank() }
            .map { condicao(it, presets, rodadasPadrao) }
        // Cura com duração: cura o valor a cada turno (regeneração); a instantânea é aplicada direto na vida
        val curas = efeitos.filter { it.tipo == Efeito.TIPO_CURA && (it.valor ?: 0) > 0 && curaPorTurno(it) }
            .map { e ->
                StatusEffect(
                    id = UUID.randomUUID().toString(),
                    name = "${skill.name} (cura)",
                    desc = "Cura ${e.valor} de PV por turno",
                    color = "#4ade80",
                    durationTurns = if (e.passivo) -1 else e.rodadas ?: rodadasPadrao,
                    effects = listOf(AutoEffect(trigger = "on_turn_start", type = "heal_hp", value = e.valor))
                )
            }
        // Modificadores com a mesma duração viram um único StatusEffect com o nome da skill
        val modificadores = efeitos.filter { it.tipo == Efeito.TIPO_MODIFICADOR && !it.stat.isNullOrBlank() && it.valor != null }
            .groupBy { duracaoEmTurnos(it, rodadasPadrao) }
            .map { (turnos, grupo) -> modificador(skill.name, grupo, turnos) }
        return condicoes + curas + modificadores
    }

    /**
     * Efeitos com alvo ALIADOS: (cura na hora, Escudo, status para aplicar). Os status incluem a cura por turno,
     * os modificadores e as condições; cada aliado recebe a sua cópia.
     */
    fun paraAliados(skill: Skill, presets: List<EffectPreset>): Triple<Int, Int, List<StatusEffect>> {
        val emAliados = skill.efeitos.filter { it.alvo == Efeito.ALVO_ALIADOS }
        val cura = emAliados.filter { it.tipo == Efeito.TIPO_CURA && !curaPorTurno(it) }.sumOf { it.valor ?: 0 }
        val escudo = emAliados.filter { it.tipo == Efeito.TIPO_ESCUDO }.sumOf { it.valor ?: 0 }
        return Triple(cura, escudo, converter(skill, emAliados, presets, rodadasPadrao = 1))
    }

    /** Cura com duração (por turno) ou passiva; sem duração é instantânea. */
    private fun curaPorTurno(e: Efeito): Boolean = e.passivo || (e.rodadas ?: 0) > 0

    /** Cura instantânea que a skill dá no próprio usuário ao ser usada (CURA sem duração, alvo SELF). */
    fun curaNoUsuario(skill: Skill): Int =
        skill.efeitos.filter { it.alvo == Efeito.ALVO_SELF && it.tipo == Efeito.TIPO_CURA && !curaPorTurno(it) }.sumOf { it.valor ?: 0 }

    private fun duracaoEmTurnos(e: Efeito, padrao: Int): Int = if (e.passivo) -1 else e.rodadas ?: padrao

    private fun condicao(e: Efeito, presets: List<EffectPreset>, padrao: Int): StatusEffect {
        val preset = presets.firstOrNull { it.name.equals(e.condicao, ignoreCase = true) }
        val turnos = if (e.passivo) -1 else e.rodadas ?: preset?.durationTurns ?: padrao
        return StatusEffect(
            id = UUID.randomUUID().toString(),
            name = preset?.name ?: e.condicao!!,
            desc = preset?.desc ?: "",
            icon = preset?.icon,
            color = preset?.color,
            durationTurns = turnos,
            effects = preset?.effects ?: emptyList(),
            hitBonus = preset?.hitBonus,
            attackBonus = preset?.attackBonus
        )
    }

    private fun modificador(nome: String, grupo: List<Efeito>, turnos: Int): StatusEffect {
        val atributos = grupo.mapNotNull { e -> Efeito.STAT_PARA_ATRIBUTO[e.stat!!.uppercase()]?.let { it to e.valor!! } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val acerto = grupo.filter { it.stat.equals(Efeito.STAT_ACERTO, true) }.sumOf { it.valor!! }
        val dano = grupo.filter { it.stat.equals(Efeito.STAT_DANO, true) }.sumOf { it.valor!! }
        return StatusEffect(
            id = UUID.randomUUID().toString(),
            name = nome,
            desc = descrever(grupo) + if (turnos == -1) "" else " por $turnos turno(s)",
            color = "#4ade80",
            durationTurns = turnos,
            attributeBonus = atributos.ifEmpty { null },
            hitBonus = acerto.takeIf { it != 0 },
            damageBonus = dano.takeIf { it != 0 }
        )
    }

    /** Escudo que a skill dá no próprio usuário ao ser usada (efeitos ESCUDO com alvo SELF). */
    fun escudoNoUsuario(skill: Skill): Int =
        skill.efeitos.filter { it.alvo == Efeito.ALVO_SELF && it.tipo == Efeito.TIPO_ESCUDO }.sumOf { it.valor ?: 0 }

    /** Texto curto, ex: "AGI +2, ACERTO -1". */
    fun descrever(efeitos: List<Efeito>): String = efeitos.joinToString(", ") { e ->
        when (e.tipo) {
            Efeito.TIPO_CONDICAO -> e.condicao ?: "?"
            Efeito.TIPO_ESCUDO -> "Escudo +${e.valor ?: 0}"
            Efeito.TIPO_CURA -> "Cura +${e.valor ?: 0}" + if (curaPorTurno(e)) " por turno" else ""
            else -> "${e.stat} ${if ((e.valor ?: 0) >= 0) "+" else ""}${e.valor ?: 0}"
        }
    }
}
