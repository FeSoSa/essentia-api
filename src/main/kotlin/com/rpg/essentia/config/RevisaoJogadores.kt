package com.rpg.essentia.config

import com.rpg.essentia.service.CombatRules
import org.bson.Document

/**
 * Migração dos jogadores para a maestria por grupo e os requisitos por origem/tier (funções puras sobre documentos).
 *   Maestria: por grupo, a skill de maior nível antiga (até 4) com os usos dela; escolhas de Aumento/Otimização descartadas.
 *   Arma só tem maestria com proficiência; o nível sobe até o maior tier das skills de arma que o jogador já tem.
 *   Nível de cada Essência: o maior tier das skills dela que o jogador já tem (mínimo 1). Nível de classe: 1.
 */
object RevisaoJogadores {

    /** Dados de uma skill já com o tier proposto. */
    data class InfoSkill(
        val nome: String,
        val tipo: String,
        val tier: Int?,
        val categorias: List<String>,
        val essenciaId: String?
    )

    data class LinhaGrupo(val grupo: String, val tipo: String, val nivel: Int, val usos: Int, val origem: String)

    data class Relatorio(
        val jogador: String,
        val grupos: List<LinhaGrupo>,
        val divergencias: List<String>,       // skills do mesmo grupo com níveis antigos diferentes
        val niveisEssencia: Map<String, Int>,  // essenciaId -> nível
        val capacidade: Int,
        val uso: Int,
        val classeBloqueadas: List<String>,   // skills de classe acima do nível de classe 1
        val semProficiencia: List<String>     // skills de arma de categoria sem proficiência
    )

    private fun nivelAntigo(m: Document?): Int = (RevisaoMigracao.num(m?.get("level")) ?: 1).coerceIn(1, 4)
    private fun usosAntigos(m: Document?): Int =
        RevisaoMigracao.num(m?.get("usos")) ?: RevisaoMigracao.num(m?.get("totalUses")) ?: RevisaoMigracao.num(m?.get("total_uses")) ?: 0

    /** Ajusta o documento do jogador no lugar e devolve o relatório. */
    fun ajustar(
        p: Document,
        playerSkills: List<Document>,
        skills: Map<String, InfoSkill>,
        essencias: List<Document>,
        nomeEssencia: (String) -> String
    ): Relatorio {
        val pid = p["_id"].toString()
        val nome = (p["char"] as? Document)?.getString("name") ?: "?"
        @Suppress("UNCHECKED_CAST")
        val proficiencias = ((p["proficiencias"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()).map { it.lowercase() }
        val obtidas = RevisaoMigracao.listaDocs(p["essenciasObtidas"])
        val obtidasIds = obtidas.mapNotNull { it.getString("essenciaId") }.toSet()
        val parentDe = essencias.associate { it["_id"].toString() to it.getString("parentId") }
        val nivelObtida = obtidas.associate { it.getString("essenciaId") to (RevisaoMigracao.num(it["nivel"]) ?: 1) }
        // Derivada sem nível configurado: a migração libera pela mãe a partir do nível 1 (como é hoje)
        val nivelMae = essencias.associate { it["_id"].toString() to (RevisaoMigracao.num(it["nivelMaeNecessario"]) ?: 1) }

        fun essenciaEfetiva(id: String?): String? = when {
            id == null -> null
            id in obtidasIds -> id
            parentDe[id] in obtidasIds && (nivelObtida[parentDe[id]] ?: 1) >= (nivelMae[id] ?: 1) -> parentDe[id]
            else -> null
        }

        // Skills do jogador com a maestria antiga
        val minhas = playerSkills.filter { (it["playerId"] ?: it["player_id"])?.toString() == pid }
            .mapNotNull { ps -> skills[(ps["skillId"] ?: ps["skill_id"]).toString()]?.let { it to (ps["maestria"] as? Document) } }

        // Maestria antiga por grupo: maior nível (até 4), com os usos dessa skill
        val porGrupo = mutableMapOf<Pair<String, String>, MutableList<Pair<InfoSkill, Document?>>>()
        for ((s, m) in minhas) {
            val g = when (s.tipo) {
                "weapon" -> s.categorias.firstOrNull()?.let { it to RevisaoMigracao.TIPO_ARMA }
                "essencia" -> essenciaEfetiva(s.essenciaId)?.let { it to RevisaoMigracao.TIPO_ESSENCIA }
                else -> null
            } ?: continue
            porGrupo.getOrPut(g) { mutableListOf() } += s to m
        }
        val divergencias = porGrupo.filter { (_, l) -> l.map { nivelAntigo(it.second) }.toSet().size > 1 }
            .map { (g, l) ->
                val nomeG = if (g.second == RevisaoMigracao.TIPO_ESSENCIA) nomeEssencia(g.first) else g.first
                "$nomeG: " + l.joinToString(", ") { (s, m) -> "${s.nome} nv${nivelAntigo(m)} (${usosAntigos(m)} usos)" }
            }
        fun melhor(g: Pair<String, String>): Pair<Int, Int>? = porGrupo[g]
            ?.maxWithOrNull(compareBy({ nivelAntigo(it.second) }, { usosAntigos(it.second) }))
            ?.let { nivelAntigo(it.second) to usosAntigos(it.second) }

        val linhas = mutableListOf<LinhaGrupo>()
        // Arma: só categorias com proficiência
        for (cat in proficiencias.distinct()) {
            val antigo = melhor(cat to RevisaoMigracao.TIPO_ARMA)
            val maiorTier = minhas.filter { (s, _) -> s.tipo == "weapon" && s.categorias.firstOrNull() == cat }
                .mapNotNull { it.first.tier }.maxOrNull() ?: 1
            val nivel = maxOf(antigo?.first ?: 1, maiorTier).coerceIn(1, 4)
            val origem = when {
                nivel > (antigo?.first ?: 1) -> "subiu para cobrir tier $maiorTier"
                antigo != null -> "dados antigos"
                else -> "proficiência da classe"
            }
            linhas += LinhaGrupo(cat, RevisaoMigracao.TIPO_ARMA, nivel, antigo?.second ?: 0, origem)
        }
        // Essência: cada Essência obtida
        for (e in obtidasIds) {
            val antigo = melhor(e to RevisaoMigracao.TIPO_ESSENCIA)
            linhas += LinhaGrupo(e, RevisaoMigracao.TIPO_ESSENCIA, antigo?.first ?: 1, antigo?.second ?: 0,
                if (antigo != null) "dados antigos" else "Essência obtida")
        }
        p["maestrias"] = linhas.map {
            Document("grupo", it.grupo).append("tipoGrupo", it.tipo).append("nivel", it.nivel).append("usos", it.usos)
                .append("ultimoCombateUso", null).append("usosNoCombate", 0)
        }

        // Nível de cada Essência: o maior tier das skills dela que o jogador já tem
        val niveisEssencia = obtidasIds.associateWith { e ->
            minhas.filter { (s, _) -> s.tipo == "essencia" && essenciaEfetiva(s.essenciaId) == e }
                .mapNotNull { it.first.tier }.maxOrNull()?.coerceIn(1, 4) ?: 1
        }
        obtidas.forEach { o -> o["nivel"] = niveisEssencia[o.getString("essenciaId")] ?: 1 }
        p["nivelClasse"] = 1

        val sab = RevisaoMigracao.num((p["attributes"] as? Document)?.get("wisdom")) ?: 0
        return Relatorio(
            jogador = nome,
            grupos = linhas.map { if (it.tipo == RevisaoMigracao.TIPO_ESSENCIA) it.copy(grupo = nomeEssencia(it.grupo)) else it },
            divergencias = divergencias,
            niveisEssencia = niveisEssencia.mapKeys { nomeEssencia(it.key) },
            capacidade = 4 + CombatRules.getModifier(sab),
            uso = niveisEssencia.values.sum(),
            classeBloqueadas = minhas.filter { (s, _) -> s.tipo == "class" && (s.tier ?: 1) > 1 }.map { "${it.first.nome} (tier ${it.first.tier})" },
            semProficiencia = minhas.filter { (s, _) -> s.tipo == "weapon" && s.categorias.none { it in proficiencias } }.map { it.first.nome }
        )
    }
}
