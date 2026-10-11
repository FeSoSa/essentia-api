package com.rpg.essentia.config

import com.rpg.essentia.repository.ClassKitRepository
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.service.AttributeService
import com.rpg.essentia.service.GameStateService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.io.File

/**
 * Bônus de PV e Fluxo por nível da classe.
 *   essentia.migration.vitais=dry-run  só gera o relatório (essentia.migration.vitais.saida, padrão vitais-diff.md)
 *   essentia.migration.vitais=apply    preenche as classes pela tabela e soma o ganho no PV/Fluxo atual dos
 *                                      jogadores acima do nível 1; uma vez só (registro em game_state.migracoes)
 */
/** Roda depois da revisão (Order 1), para somar o ganho da classe sobre os vitais já recalculados. */
@Component
@Order(2)
class VitaisClasseMigrationRunner(
    private val classKitRepository: ClassKitRepository,
    private val playerRepository: PlayerRepository,
    private val essenciaRepository: EssenciaRepository,
    private val attributeService: AttributeService,
    private val gameStateService: GameStateService,
    @Value("\${essentia.migration.vitais:off}") private val modo: String,
    @Value("\${essentia.migration.vitais.saida:vitais-diff.md}") private val saida: String
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val NOME = "vitais-por-classe"

        /** pvPorNivel e fluxoPorNivel por classe; subclasses usam os da classe principal. */
        val TABELA: Map<String, Pair<Int, Int>> = mapOf(
            "Brutamonte" to (19 to 5),
            "Guerreiro" to (17 to 7),
            "Lutador Intenso" to (14 to 10), "Lutador Forjado" to (14 to 10),     // Lutador
            "Intenso" to (14 to 10), "Forjado" to (14 to 10),                     // nomes do seed
            "Assasino" to (12 to 12), "Assassino" to (12 to 12), "Atirador" to (12 to 12), // Caçador
            "Artífice" to (10 to 14),
            "Arcanista" to (6 to 18)
        )
    }

    override fun run(args: ApplicationArguments) {
        if (modo != "dry-run" && modo != "apply") return
        if (NOME in gameStateService.getOrCreate().migracoes) {
            log.warn("Migração $NOME já aplicada; nada a fazer")
            return
        }
        // Classes: valores da tabela (as que já têm valor ficam como estão)
        val kits = classKitRepository.findAll().map { k ->
            val v = TABELA[k.skillClass]
            if (v != null && k.pvPorNivel == 0 && k.fluxoPorNivel == 0) k.copy(pvPorNivel = v.first, fluxoPorNivel = v.second) else k
        }
        if (modo == "apply") kits.forEach { classKitRepository.save(it) }

        // Jogadores: recalcula com o bônus e soma o ganho no atual (como numa subida de nível)
        val essencias = essenciaRepository.findAll()
        val linhas = playerRepository.findAll().map { p ->
            val kit = kits.firstOrNull { it.skillClass == p.char.skillClass }
            val efetivo = attributeService.computeEffectiveAttributes(p, essencias)
            // No dry-run as classes ainda não foram salvas: simula com os valores da tabela
            val novo = if (modo == "apply") attributeService.recalculateVitals(p, efetivo, somarGanho = true)
                else simular(p, kit?.pvPorNivel ?: 0, kit?.fluxoPorNivel ?: 0, efetivo.resistance, efetivo.flow)
            if (modo == "apply") playerRepository.save(novo)
            "| ${p.char.name} | ${p.char.skillClass} | ${p.char.level} | ${p.hp.current}/${p.hp.max} → ${novo.hp.current}/${novo.hp.max} | " +
                "${p.flow.current}/${p.flow.max} → ${novo.flow.current}/${novo.flow.max} |"
        }

        File(saida).writeText(buildString {
            appendLine("# Bônus de PV e Fluxo por nível da classe")
            appendLine()
            appendLine("PV máximo = 20 + RES × 20 + pvPorNivel × (nível - 1). Fluxo máximo = 20 + FLX × 15 + fluxoPorNivel × (nível - 1).")
            appendLine("No nível 1 nada muda. Acima dele, o atual soma o mesmo que o máximo ganhou.")
            appendLine()
            appendLine("| Classe | PV por nível | Fluxo por nível |")
            appendLine("|---|---|---|")
            kits.forEach { appendLine("| ${it.skillClass} | ${it.pvPorNivel} | ${it.fluxoPorNivel} |") }
            appendLine()
            appendLine("| Jogador | Classe | Nível | PV (atual/máx) | Fluxo (atual/máx) |")
            appendLine("|---|---|---|---|---|")
            linhas.forEach { appendLine(it) }
        })
        log.warn("Relatório de $NOME em ${File(saida).absolutePath}")
        if (modo == "apply")
            gameStateService.save(gameStateService.getOrCreate().let { it.copy(migracoes = it.migracoes + NOME) })
    }

    private fun simular(p: com.rpg.essentia.model.Player, pv: Int, fl: Int, res: Int, flx: Int) = p.copy(
        hp = com.rpg.essentia.service.VitaisRules.ajustar(p.hp, com.rpg.essentia.service.VitaisRules.pvMax(res, p.char.level, pv), true),
        flow = com.rpg.essentia.service.VitaisRules.ajustar(p.flow, com.rpg.essentia.service.VitaisRules.fluxoMax(flx, p.char.level, fl), true)
    )
}
