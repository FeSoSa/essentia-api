package com.rpg.essentia.config

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.service.AttributeService
import com.rpg.essentia.service.GameStateService
import com.rpg.essentia.service.toMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.io.File

/**
 * A Terra absorve a Madeira: a Madeira sai, as 4 skills dela viram skills T1 da Terra, e quem tinha
 * Madeira recebe Terra no mesmo nível (maestria de Madeira vira de Terra). Mesmo formato de [EssenciasFusaoMigrationRunner].
 *   essentia.migration.madeira=dry-run  só gera o relatório (essentia.migration.madeira.saida, padrão madeira-diff.md)
 *   essentia.migration.madeira=apply    aplica uma vez (registro em game_state.migracoes); faça mongodump antes
 */
@Component
@Order(4)
class TerraMadeiraMigrationRunner(
    private val gameStateService: GameStateService,
    private val playerRepository: PlayerRepository,
    private val playerSkillRepository: PlayerSkillRepository,
    private val skillRepository: SkillRepository,
    private val essenciaRepository: EssenciaRepository,
    private val attributeService: AttributeService,
    @Value("\${essentia.migration.madeira:off}") private val modo: String,
    @Value("\${essentia.migration.madeira.saida:madeira-diff.md}") private val saida: String
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val NOME = "terra-absorve-madeira"
        const val SAI = "Madeira"
        const val FICA = "Terra"
        const val TIER = 1

        /**
         * Calibração para o T1, com o T1 da Terra como régua (Fissura: 13 FLX, recarga 4, 35 + d20×INT/4;
         * Fortaleza de Pedra: recarga 3). As que eram T1 só ganham recarga; as que eram T2/T3 descem de força.
         */
        fun calibrar(s: Skill): Skill = when (s.name) {
            "Garranchos" -> s.copy(cooldownTurns = 3)
            "Barreira Vegeta" -> s.copy(cooldownTurns = 3)
            "Raízes Profundas" -> s.copy(desc = "Enquanto em contato com terra ou vegetação, regenera 5 PV no início do turno.")
            "Raiz Repentina" -> s.copy(
                costs = listOf(Cost(type = "flow", value = 13, percentual = null)),
                cooldownTurns = 2,
                damage = s.damage?.copy(formula = "35 + d20×INT/4", baseFixed = 35),
                desc = "Uma raiz brota sob um alvo. Alvo testa AGI (CD 12) ou Enraizado por 1 turno."
            )
            else -> s
        }
    }

    override fun run(args: ApplicationArguments) {
        if (modo != "dry-run" && modo != "apply") return
        if (NOME in gameStateService.getOrCreate().migracoes) {
            log.warn("Migração $NOME já aplicada; nada a fazer")
            return
        }
        val aplicar = modo == "apply"

        val essencias = essenciaRepository.findAll()
        val madeira = essencias.firstOrNull { it.name == SAI }
        val terra = essencias.firstOrNull { it.name == FICA }
        if (madeira == null || terra == null) {
            log.error("Migração $NOME: Essência $SAI ou $FICA não encontrada; nada feito")
            return
        }
        val nomeEss = essencias.associate { it.id to it.name }

        // ── Skills: as da Madeira passam para a Terra, todas no T1 ──
        val skills = skillRepository.findAll()
        val daMadeira = skills.filter { it.essenciaId == madeira.id }
        val movidas = daMadeira.map { calibrar(it.copy(essenciaId = terra.id, tier = TIER)) }
        // Lista legada de skills da Terra: só é mantida quando já está em uso
        val terraDepois = if (terra.skillIds.isEmpty()) terra
            else terra.copy(skillIds = (terra.skillIds + movidas.mapNotNull { it.id }).distinct())
        val essenciasDepois = essencias.filter { it.id != madeira.id }.map { if (it.id == terra.id) terraDepois else it }
        val skillsDepois = skills.map { s -> movidas.firstOrNull { it.id == s.id } ?: s }

        val rel = StringBuilder()
        rel.appendLine("# A Terra absorve a Madeira")
        rel.appendLine()
        rel.appendLine(if (aplicar) "Aplicado." else "Nada foi aplicado ainda (dry-run).")
        rel.appendLine()
        rel.appendLine("## Essência que sai")
        rel.appendLine()
        rel.appendLine("**$SAI** (Derivada da $FICA, liberada com $FICA nível ${madeira.nivelMaeNecessario ?: 1}) sai do jogo. Quem tinha passa a ter **$FICA** no mesmo nível.")
        rel.appendLine()
        rel.appendLine("## Skills que vão para a $FICA")
        rel.appendLine()
        fun custo(s: Skill) = s.costs.joinToString { "${it.value} ${if (it.type == "flow") "FLX" else it.type}" }.ifEmpty { "passiva" }
        fun resumo(s: Skill) = listOfNotNull(
            custo(s), "recarga ${s.cooldownTurns}", s.damage?.formula, s.desc
        ).joinToString("; ")
        rel.appendLine("Todas viram T$TIER da $FICA. Calibração pelo T1 da própria $FICA (Fissura: 13 FLX, recarga 4, 35 + d20×INT/4; Fortaleza de Pedra: recarga 3).")
        rel.appendLine()
        rel.appendLine("| Skill | Antes | Depois |")
        rel.appendLine("|---|---|---|")
        daMadeira.zip(movidas).forEach { (a, d) ->
            rel.appendLine("| ${a.name} | T${a.tier}: ${resumo(a)} | T${d.tier}: ${if (resumo(a) == resumo(d)) "igual" else resumo(d)} |")
        }
        rel.appendLine()
        val t1Antes = skills.filter { it.essenciaId == terra.id && it.tier == TIER }.map { it.name }
        rel.appendLine("T1 da $FICA: ${t1Antes.joinToString()} → ${(t1Antes + movidas.map { it.name }).joinToString()}")
        rel.appendLine()

        // ── Jogadores ─────────────────────────────────────────────
        rel.appendLine("## Jogadores")
        rel.appendLine()
        val players = playerRepository.findAll()
        val playersDepois = players.map { p ->
            val afetado = p.essenciasObtidas.any { it.essenciaId == madeira.id } ||
                p.maestrias.any { it.tipoGrupo == "ESSENCIA" && it.grupo == madeira.id }
            if (!afetado) return@map p

            // Essências obtidas: Madeira vira Terra; se já tinha Terra, junta (maior nível, skills somadas)
            val obtidas = mutableListOf<EssenciaObtida>()
            p.essenciasObtidas.forEach { o ->
                val id = if (o.essenciaId == madeira.id) terra.id else o.essenciaId
                val atual = obtidas.indexOfFirst { it.essenciaId == id }
                if (atual < 0) obtidas += o.copy(essenciaId = id)
                else obtidas[atual] = obtidas[atual].let {
                    it.copy(nivel = maxOf(it.nivel, o.nivel), attributeBonusActive = it.attributeBonusActive || o.attributeBonusActive,
                        unlockedSkillIds = (it.unlockedSkillIds + o.unlockedSkillIds).distinct())
                }
            }
            // Maestria: o grupo da Madeira vira o da Terra (fica o maior nível e os usos)
            val maestrias = mutableListOf<MaestriaGrupo>()
            p.maestrias.forEach { m ->
                val grupo = if (m.tipoGrupo == "ESSENCIA" && m.grupo == madeira.id) terra.id else m.grupo
                val atual = maestrias.indexOfFirst { it.grupo == grupo && it.tipoGrupo == m.tipoGrupo }
                if (atual < 0) maestrias += m.copy(grupo = grupo)
                else maestrias[atual] = maestrias[atual].let { it.copy(nivel = maxOf(it.nivel, m.nivel), usos = maxOf(it.usos, m.usos)) }
            }

            val trocado = p.copy(essenciasObtidas = obtidas, maestrias = maestrias)
            val sync = attributeService.syncPassiveSelfEffects(trocado, skillsDepois)
            val depois = attributeService.recalculateVitals(sync, attributeService.computeEffectiveAttributes(sync, essenciasDepois))

            val antesAttr = p.effectiveAttributes?.toMap() ?: emptyMap()
            val depoisAttr = depois.effectiveAttributes?.toMap() ?: emptyMap()
            val difAttr = depoisAttr.filter { (k, v) -> v != (antesAttr[k] ?: 0) }.map { (k, v) -> "${abrev(k)} ${antesAttr[k] ?: 0}→$v" }
            val minhas = playerSkillRepository.findByPlayerId(p.id).filter { ps -> daMadeira.any { it.id == ps.skillId } }

            rel.appendLine("### ${p.char.name}")
            rel.appendLine()
            rel.appendLine("- Essências: " + p.essenciasObtidas.joinToString { "${nomeEss[it.essenciaId]} ${it.nivel}" } +
                " → " + depois.essenciasObtidas.joinToString { "${nomeEss[it.essenciaId]} ${it.nivel}" })
            val mAntes = p.maestrias.filter { it.tipoGrupo == "ESSENCIA" }.joinToString { "${nomeEss[it.grupo]} nv${it.nivel} (${it.usos} usos)" }
            val mDepois = depois.maestrias.filter { it.tipoGrupo == "ESSENCIA" }.joinToString { "${nomeEss[it.grupo]} nv${it.nivel} (${it.usos} usos)" }
            if (mAntes != mDepois) rel.appendLine("- Maestria: $mAntes → $mDepois")
            if (minhas.isNotEmpty()) rel.appendLine("- Skills: " + minhas.joinToString { ps ->
                "${daMadeira.first { it.id == ps.skillId }.name}${if (ps.equipped) " (equipada, continua no slot)" else ""}" } + ", agora da $FICA")
            rel.appendLine("- Atributos: " + (if (difAttr.isEmpty()) "sem mudança" else difAttr.joinToString()))
            rel.appendLine("- PV: ${p.hp.current}/${p.hp.max} → ${depois.hp.current}/${depois.hp.max}; Fluxo: ${p.flow.current}/${p.flow.max} → ${depois.flow.current}/${depois.flow.max}")
            rel.appendLine("- Capacidade de Essência: uso ${p.essencia?.uso}/${p.essencia?.capacidade} → ${depois.essencia?.uso}/${depois.essencia?.capacidade}")
            rel.appendLine()
            depois
        }
        if (playersDepois.zip(players).none { (d, a) -> d !== a }) rel.appendLine("Ninguém tinha $SAI.\n")

        File(saida).writeText(rel.toString())
        log.warn("Relatório da migração $NOME gravado em ${File(saida).absolutePath}")
        if (!aplicar) return

        movidas.forEach { skillRepository.save(it) }
        if (terraDepois != terra) essenciaRepository.save(terraDepois)
        // Derivadas que dependiam da Madeira (nenhuma hoje) passam a depender da Terra
        essencias.filter { it.parentId == madeira.id }.forEach { essenciaRepository.save(it.copy(parentId = terra.id)) }
        essenciaRepository.deleteById(madeira.id)
        playersDepois.zip(players).forEach { (d, a) -> if (d !== a) playerRepository.save(d) }
        gameStateService.save(gameStateService.getOrCreate().let { it.copy(migracoes = (it.migracoes + NOME).distinct()) })
        log.warn("Migração $NOME aplicada: ${movidas.size} skills para a $FICA, ${playersDepois.zip(players).count { (d, a) -> d !== a }} jogadores")
    }

    private fun abrev(k: String) = when (k) {
        "strength" -> "FOR"; "agility" -> "AGI"; "intelligence" -> "INT"; "resistance" -> "RES"
        "flow" -> "FLX"; "wisdom" -> "SAB"; "presence" -> "PRE"; "defense" -> "DEF"; else -> k
    }
}
