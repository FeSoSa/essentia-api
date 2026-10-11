package com.rpg.essentia.config

import com.rpg.essentia.model.CombateConfig
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/**
 * Gera o relatório da revisão a partir de um backup exportado em JSON (uma lista por coleção),
 * sem tocar no banco. Só roda com REVISAO_JSON_DIR definido; saída em REVISAO_SAIDA.
 */
@EnabledIfEnvironmentVariable(named = "REVISAO_JSON_DIR", matches = ".+")
class RevisaoRelatorioTest {

    private fun ler(dir: File, nome: String): List<Document> {
        val f = File(dir, "$nome.json")
        if (!f.exists()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return Document.parse("{\"l\": ${f.readText()}}")["l"] as List<Document>
    }

    @Test
    fun `gera relatorio do backup`() {
        val dir = File(System.getenv("REVISAO_JSON_DIR"))
        val dados = RevisaoDados(
            skills = ler(dir, "skills"), presets = ler(dir, "presets"), itens = ler(dir, "item_catalog"),
            kits = ler(dir, "class_kits"), players = ler(dir, "players"), essencias = ler(dir, "essencias"),
            playerSkills = ler(dir, "player_skills"),
            inimigos = ler(dir, "enemy_templates"), bosses = ler(dir, "boss_templates"),
            kitsOriginais = ler(dir, "class_kits"),
            buff = RevisaoDados.buffPadrao()
        )
        val plano = RevisaoPlano.montar(dados, CombateConfig())
        val saida = File(System.getenv("REVISAO_SAIDA") ?: "revisao-diff.md")
        saida.writeText(RevisaoPlano.relatorio(plano, dados))
        println("Relatório: ${saida.absolutePath}")
    }
}
