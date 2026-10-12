package com.rpg.essentia.config

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EffectPresetRepository
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.service.AttributeService
import com.rpg.essentia.service.EssenciaRules
import com.rpg.essentia.service.GameStateService
import com.rpg.essentia.service.toMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.io.File
import java.util.UUID

/**
 * Brasa, Brisa, Raio e Cristal: quem tinha recebe a Elemental-mãe no mesmo nível; as skills do Cristal que ficam vão para o Gelo. Junto, o bônus de atributo
 * das Essências passa a ser proporcional ao nível (CombateConfig.essenciaBonusPct), então todos os jogadores
 * são recalculados.
 *   essentia.migration.essencias=dry-run  só gera o relatório (essentia.migration.essencias.saida, padrão essencias-diff.md)
 *   essentia.migration.essencias=apply    aplica uma vez (registro em game_state.migracoes); faça mongodump antes
 */
@Component
@Order(3)
class EssenciasFusaoMigrationRunner(
    private val gameStateService: GameStateService,
    private val playerRepository: PlayerRepository,
    private val playerSkillRepository: PlayerSkillRepository,
    private val skillRepository: SkillRepository,
    private val essenciaRepository: EssenciaRepository,
    private val effectPresetRepository: EffectPresetRepository,
    private val attributeService: AttributeService,
    @Value("\${essentia.migration.essencias:off}") private val modo: String,
    @Value("\${essentia.migration.essencias.saida:essencias-diff.md}") private val saida: String
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val NOME = "essencias-fusao"

        /** Essência que sai -> Essência que a substitui. */
        val SUBSTITUI = mapOf("Brasa" to "Fogo", "Brisa" to "Ar", "Raio" to "Tempestade", "Cristal" to "Terra")

        /** Skills das Essências que saem e continuam no jogo, na Essência nova. */
        val MOVE = mapOf(
            "Lâmina Incandescente" to "Fogo",
            "Lâmina Eletrificada" to "Tempestade",
            "Choque" to "Tempestade",
            "Barreira Cristalina" to "Gelo",
            "Estilhaçar" to "Gelo"
        )

        /** Skill que sai -> skill equivalente que entra no lugar (slot e desbloqueio). As outras só saem. */
        val TROCA = mapOf(
            "Brasa Viva" to "Chama Devoradora",
            "Passo do Vento" to "Dança do Vento",
            "Farpa de Cristal" to "Fissura"
        )

        const val BARREIRA_NOVA = "Barreira de Gelo"
        const val ESTILHACADO = "Estilhaçado"
    }

    override fun run(args: ApplicationArguments) {
        if (modo != "dry-run" && modo != "apply") return
        if (NOME in gameStateService.getOrCreate().migracoes) {
            log.warn("Migração $NOME já aplicada; nada a fazer")
            return
        }
        val aplicar = modo == "apply"
        val rel = StringBuilder()

        val essencias = essenciaRepository.findAll()
        val porNome = essencias.associateBy { it.name }
        val faltando = (SUBSTITUI.keys + SUBSTITUI.values).filter { it !in porNome }
        if (faltando.isNotEmpty()) {
            log.error("Migração $NOME: Essências não encontradas: $faltando; nada feito")
            return
        }
        // id da Essência que sai -> id da que entra
        val mapaEss = SUBSTITUI.entries.associate { (de, para) -> porNome.getValue(de).id to porNome.getValue(para).id }
        val nomeEss = essencias.associate { it.id to it.name }

        val skills = skillRepository.findAll()
        val skillPorNome = skills.filter { it.type == "essencia" }.associateBy { it.name }
        val daRemovida = skills.filter { it.type == "essencia" && it.essenciaId in mapaEss.keys }
        val movidas = daRemovida.filter { it.name in MOVE }
        val apagadas = daRemovida.filter { it.name !in MOVE }
        val apagadasIds = apagadas.mapNotNull { it.id }.toSet()
        // id da skill apagada -> id da equivalente (ou ausente: o slot fica vazio)
        val trocaIds = apagadas.mapNotNull { s -> TROCA[s.name]?.let { skillPorNome[it]?.id }?.let { s.id!! to it } }.toMap()
        val nomeSkill = skills.associate { it.id to it.name }

        // ── Condições ─────────────────────────────────────────────
        val presets = effectPresetRepository.findAll()
        val barreiraPreset = presets.firstOrNull { it.name == "Barreira Cristalina" }?.copy(
            name = BARREIRA_NOVA, icon = "Snowflake",
            desc = "Absorve até 16 de dano do próximo ataque recebido e devolve metade (mínimo 8) ao atacante em estilhaços de gelo."
        )
        val estilhacado = presets.firstOrNull { it.name == ESTILHACADO } ?: EffectPreset(
            name = ESTILHACADO, icon = "Snowflake", color = "#06b6d4",
            desc = "-1 DEF por 2 turnos.", durationTurns = 2,
            effects = listOf(AutoEffect(trigger = "on_turn_start", type = "modify_attribute", value = -1, attribute = "defense"))
        )

        // ── Skills que mudam de Essência ──────────────────────────
        val skillsNovas = movidas.map { s ->
            val destino = porNome.getValue(MOVE.getValue(s.name)).id
            when (s.name) {
                "Barreira Cristalina" -> s.copy(
                    name = BARREIRA_NOVA, essenciaId = destino,
                    desc = "Uma camada de gelo absorve até 16 de dano do próximo ataque recebido. Metade do dano absorvido (mínimo 8) é devolvida ao atacante em estilhaços.",
                    efeitos = s.efeitos.map { if (it.condicao == "Barreira Cristalina") it.copy(condicao = BARREIRA_NOVA) else it }
                )
                "Estilhaçar" -> s.copy(
                    essenciaId = destino,
                    desc = "O gelo sob o alvo explode em estilhaços. Alvo testa RES (CD 12) ou fica Estilhaçado: -1 DEF por 2 turnos.",
                    efeitos = s.efeitos.filterNot { it.condicao == ESTILHACADO } +
                        Efeito(alvo = Efeito.ALVO_ALVO, tipo = Efeito.TIPO_CONDICAO, condicao = ESTILHACADO, duracao = 2)
                )
                else -> s.copy(essenciaId = destino)
            }
        }

        rel.appendLine("# Essências: Brasa, Brisa, Raio e Cristal saem; bônus de Essência proporcional ao nível")
        rel.appendLine()
        rel.appendLine(if (aplicar) "Aplicado." else "Nada foi aplicado ainda (dry-run).")
        rel.appendLine()
        rel.appendLine("## Essências que saem")
        rel.appendLine()
        rel.appendLine("| Sai | Quem tinha passa a ter |")
        rel.appendLine("|---|---|")
        SUBSTITUI.forEach { (de, para) -> rel.appendLine("| $de | $para (mesmo nível) |") }
        rel.appendLine()
        rel.appendLine("## Skills que mudam de Essência")
        rel.appendLine()
        rel.appendLine("| Skill | De | Para | Mudança |")
        rel.appendLine("|---|---|---|---|")
        movidas.zip(skillsNovas).forEach { (a, d) ->
            val muda = buildList {
                if (a.name != d.name) add("nome: ${d.name}")
                if (a.desc != d.desc) add("texto: ${d.desc}")
                if (a.efeitos != d.efeitos) add("efeitos: " + d.efeitos.joinToString { "${it.condicao ?: it.tipo} (${it.duracao})" })
            }.joinToString("; ").ifEmpty { "só a Essência" }
            rel.appendLine("| ${a.name} (T${a.tier}) | ${nomeEss[a.essenciaId]} | ${nomeEss[d.essenciaId]} | $muda |")
        }
        rel.appendLine()
        rel.appendLine("## Skills que saem do jogo")
        rel.appendLine()
        rel.appendLine("| Skill | Essência | Quem tinha recebe |")
        rel.appendLine("|---|---|---|")
        apagadas.forEach { s -> rel.appendLine("| ${s.name} (T${s.tier}) | ${nomeEss[s.essenciaId]} | ${TROCA[s.name] ?: "nada (slot fica vazio)"} |") }
        rel.appendLine()
        rel.appendLine("## Condições")
        rel.appendLine()
        if (barreiraPreset != null) rel.appendLine("- **Barreira Cristalina** passa a se chamar **$BARREIRA_NOVA** (mesmo efeito).")
        if (presets.none { it.name == ESTILHACADO }) rel.appendLine("- Nova condição **$ESTILHACADO**: -1 DEF por 2 turnos (usada por Estilhaçar).")
        rel.appendLine()

        // ── Jogadores ─────────────────────────────────────────────
        // Essências que ficam: tabela de bônus por nível gerada pela regra padrão (o mestre ajusta no editor)
        // e a lista legada de skills sem as apagadas e com as que chegam
        val essenciasDepois = essencias.filter { it.id !in mapaEss.keys }.map { e ->
            val chegam = skillsNovas.filter { it.essenciaId == e.id && e.skillIds.isNotEmpty() }.mapNotNull { it.id }
            e.copy(
                skillIds = (e.skillIds.filter { it !in apagadasIds } + chegam).distinct(),
                bonusPorNivel = e.bonusPorNivel.ifEmpty { if (e.attributeBonus.isEmpty()) emptyList() else EssenciaRules.tabelaPadrao(e.attributeBonus) }
            )
        }
        rel.appendLine("## Bônus de atributo por nível (tabela inicial de cada Essência)")
        rel.appendLine()
        rel.appendLine("Quanto cada nível soma. O total no nível 4 é o bônus de hoje.")
        rel.appendLine()
        rel.appendLine("| Essência | Nível 1 | Nível 2 | Nível 3 | Nível 4 |")
        rel.appendLine("|---|---|---|---|---|")
        essenciasDepois.filter { it.bonusPorNivel.isNotEmpty() }.forEach { e ->
            rel.appendLine("| ${e.name} | " + e.bonusPorNivel.joinToString(" | ") { m ->
                if (m.isEmpty()) "-" else m.entries.joinToString(", ") { "${abrev(it.key)} +${it.value}" }
            } + " |")
        }
        rel.appendLine()

        val skillsDepois = skills.filter { it.id !in apagadasIds }.map { s -> skillsNovas.firstOrNull { it.id == s.id } ?: s }
        val players = playerRepository.findAll()
        // Por jogador: a coleção tem registros antigos sem playerId que não carregam no modelo
        val playerSkills = players.flatMap { playerSkillRepository.findByPlayerId(it.id) }
        val psAlterados = mutableListOf<PlayerSkill>()
        val psApagados = mutableListOf<PlayerSkill>()

        rel.appendLine("## Jogadores")
        rel.appendLine()
        rel.appendLine("Bônus de atributo da Essência agora soma por nível. Cada Essência ganha uma tabela inicial de 25/50/75/100% do bônus total (arredondado para baixo), editável no painel.")
        rel.appendLine("Isso vale para todo mundo com Elemental ou Mítica, não só para quem trocou de Essência.")
        rel.appendLine()

        val playersDepois = players.map { p ->
            val afetado = p.essenciasObtidas.any { it.essenciaId in mapaEss.keys }

            // Essências obtidas: troca o id, e junta se já tinha a nova
            val obtidas = mutableListOf<EssenciaObtida>()
            p.essenciasObtidas.forEach { o ->
                val id = mapaEss[o.essenciaId] ?: o.essenciaId
                val unlocked = o.unlockedSkillIds.mapNotNull { if (it in apagadasIds) trocaIds[it] else it }
                val atual = obtidas.indexOfFirst { it.essenciaId == id }
                if (atual < 0) obtidas += o.copy(essenciaId = id, unlockedSkillIds = unlocked.distinct())
                else obtidas[atual] = obtidas[atual].let {
                    it.copy(nivel = maxOf(it.nivel, o.nivel), attributeBonusActive = it.attributeBonusActive || o.attributeBonusActive,
                        unlockedSkillIds = (it.unlockedSkillIds + unlocked).distinct())
                }
            }

            // Maestria: o grupo da Essência que sai vira o da nova (fica o maior nível e os usos)
            val maestrias = mutableListOf<MaestriaGrupo>()
            p.maestrias.forEach { m ->
                val grupo = if (m.tipoGrupo == "ESSENCIA") mapaEss[m.grupo] ?: m.grupo else m.grupo
                val atual = maestrias.indexOfFirst { it.grupo == grupo && it.tipoGrupo == m.tipoGrupo }
                if (atual < 0) maestrias += m.copy(grupo = grupo)
                else maestrias[atual] = maestrias[atual].let { it.copy(nivel = maxOf(it.nivel, m.nivel), usos = maxOf(it.usos, m.usos)) }
            }

            // Skills desbloqueadas (player_skills) e slots
            val meus = playerSkills.filter { it.playerId == p.id }
            val linhasSkills = mutableListOf<String>()
            meus.filter { it.skillId in apagadasIds }.forEach { ps ->
                val novaId = trocaIds[ps.skillId]
                val jaTem = novaId?.let { n -> meus.firstOrNull { it.skillId == n } }
                when {
                    novaId == null -> { psApagados += ps; linhasSkills += "${nomeSkill[ps.skillId]} sai" }
                    jaTem != null -> {
                        psApagados += ps
                        if (ps.equipped && !jaTem.equipped) psAlterados += jaTem.copy(equipped = true, slotId = ps.slotId)
                        linhasSkills += "${nomeSkill[ps.skillId]} sai (já tinha ${nomeSkill[novaId]})"
                    }
                    else -> { psAlterados += ps.copy(skillId = novaId); linhasSkills += "${nomeSkill[ps.skillId]} → ${nomeSkill[novaId]}" }
                }
            }
            val slots = p.slots.map { s ->
                if (s.skillId != null && s.skillId in apagadasIds) s.copy(skillId = trocaIds[s.skillId], cooldownRemaining = 0) else s
            }

            val trocado = p.copy(essenciasObtidas = obtidas, maestrias = maestrias, slots = slots)
            val sync = attributeService.syncPassiveSelfEffects(trocado, skillsDepois)
            val depois = attributeService.recalculateVitals(sync, attributeService.computeEffectiveAttributes(sync, essenciasDepois))

            val antesAttr = p.effectiveAttributes?.toMap() ?: emptyMap()
            val depoisAttr = depois.effectiveAttributes?.toMap() ?: emptyMap()
            val difAttr = depoisAttr.filter { (k, v) -> v != (antesAttr[k] ?: 0) }
                .map { (k, v) -> "${abrev(k)} ${antesAttr[k] ?: 0}→$v" }
            val mudou = afetado || difAttr.isNotEmpty() || p.hp.max != depois.hp.max || p.flow.max != depois.flow.max
            if (mudou) {
                rel.appendLine("### ${p.char.name}")
                rel.appendLine()
                rel.appendLine("- Essências: " + p.essenciasObtidas.joinToString { "${nomeEss[it.essenciaId]} ${it.nivel}" } +
                    " → " + depois.essenciasObtidas.joinToString { "${nomeEss[it.essenciaId]} ${it.nivel}" })
                val bonus = depois.essenciasObtidas.filter { it.attributeBonusActive }.mapNotNull { o ->
                    val e = essenciasDepois.firstOrNull { it.id == o.essenciaId } ?: return@mapNotNull null
                    val agora = EssenciaRules.bonusNoNivel(e, o.nivel)
                    if (e.attributeBonus.isEmpty()) null
                    else "${e.name} ${o.nivel}: " + (agora.entries.joinToString { "${abrev(it.key)} +${it.value}" }.ifEmpty { "nada" }) +
                        " (nível 4: " + e.attributeBonus.entries.joinToString { "${abrev(it.key)} +${it.value}" } + ")"
                }
                if (bonus.isNotEmpty()) rel.appendLine("- Bônus de Essência: " + bonus.joinToString("; "))
                if (linhasSkills.isNotEmpty()) rel.appendLine("- Skills: " + linhasSkills.joinToString("; "))
                val mAntes = p.maestrias.filter { it.tipoGrupo == "ESSENCIA" }.joinToString { "${nomeEss[it.grupo]} nv${it.nivel} (${it.usos} usos)" }
                val mDepois = depois.maestrias.filter { it.tipoGrupo == "ESSENCIA" }.joinToString { "${nomeEss[it.grupo]} nv${it.nivel} (${it.usos} usos)" }
                if (mAntes != mDepois) rel.appendLine("- Maestria: $mAntes → $mDepois")
                rel.appendLine("- Atributos: " + (if (difAttr.isEmpty()) "sem mudança" else difAttr.joinToString()))
                rel.appendLine("- PV: ${p.hp.current}/${p.hp.max} → ${depois.hp.current}/${depois.hp.max}; Fluxo: ${p.flow.current}/${p.flow.max} → ${depois.flow.current}/${depois.flow.max}")
                rel.appendLine("- Capacidade de Essência: uso ${p.essencia?.uso}/${p.essencia?.capacidade} → ${depois.essencia?.uso}/${depois.essencia?.capacidade}")
                rel.appendLine()
            }
            depois
        }

        File(saida).writeText(rel.toString())
        log.warn("Relatório da migração $NOME gravado em ${File(saida).absolutePath}")
        if (!aplicar) return

        barreiraPreset?.let { effectPresetRepository.save(it) }
        if (presets.none { it.name == ESTILHACADO }) effectPresetRepository.save(estilhacado)
        skillsNovas.forEach { skillRepository.save(it) }
        apagadas.forEach { s -> s.id?.let { skillRepository.deleteById(it) } }
        essenciasDepois.forEach { e -> if (e != essencias.first { it.id == e.id }) essenciaRepository.save(e) }
        mapaEss.keys.forEach { essenciaRepository.deleteById(it) }
        psApagados.forEach { playerSkillRepository.delete(it) }
        psAlterados.forEach { playerSkillRepository.save(it) }
        playersDepois.forEach { playerRepository.save(it) }
        gameStateService.save(gameStateService.getOrCreate().let { it.copy(migracoes = (it.migracoes + NOME).distinct()) })
        log.warn("Migração $NOME aplicada: ${mapaEss.size} Essências, ${skillsNovas.size} skills movidas, ${apagadas.size} apagadas, ${playersDepois.size} jogadores recalculados")
    }

    private fun abrev(k: String) = when (k) {
        "strength" -> "FOR"; "agility" -> "AGI"; "intelligence" -> "INT"; "resistance" -> "RES"
        "flow" -> "FLX"; "wisdom" -> "SAB"; "presence" -> "PRE"; "defense" -> "DEF"; else -> k
    }
}
