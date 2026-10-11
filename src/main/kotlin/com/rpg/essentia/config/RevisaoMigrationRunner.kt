package com.rpg.essentia.config

import com.rpg.essentia.model.CombateConfig
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.service.AttributeService
import com.rpg.essentia.service.GameStateService
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.io.File
import java.util.Locale

/**
 * Revisão de skills, armas e Essências (tier, efeitos unificados, proficiência, nível de Essência).
 *   essentia.migration.revisao=dry-run  só gera o relatório (essentia.migration.revisao.saida, padrão revisao-diff.md)
 *   essentia.migration.revisao=apply    aplica uma vez (registro em game_state.migracoes); faça mongodump antes
 */
/** Roda antes da migração de PV/Fluxo por classe (ela recalcula os vitais com os passivos já convertidos). */
@Component
@Order(1)
class RevisaoMigrationRunner(
    private val mongo: MongoTemplate,
    private val gameStateService: GameStateService,
    private val playerRepository: PlayerRepository,
    private val skillRepository: SkillRepository,
    private val essenciaRepository: EssenciaRepository,
    private val attributeService: AttributeService,
    @Value("\${essentia.migration.revisao:off}") private val modo: String,
    @Value("\${essentia.migration.revisao.saida:revisao-diff.md}") private val saida: String
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (modo != "dry-run" && modo != "apply") return
        val state = gameStateService.getOrCreate()
        if (RevisaoMigracao.NOME in state.migracoes) {
            log.warn("Migração ${RevisaoMigracao.NOME} já aplicada; nada a fazer")
            return
        }
        val dados = RevisaoDados(
            skills = colecao("skills"), presets = colecao("effect_presets"), itens = colecao("item_catalog"),
            kits = colecao("class_kits"), players = colecao("players"), essencias = colecao("essencias"),
            playerSkills = colecao("player_skills"),
            inimigos = colecao("enemy_templates"), bosses = colecao("boss_templates"),
            kitsOriginais = colecao("class_kits"),
            buff = RevisaoDados.buffPadrao()
        )
        val plano = RevisaoPlano.montar(dados, state.combateConfig)
        File(saida).writeText(RevisaoPlano.relatorio(plano, dados))
        log.warn("Relatório da revisão gravado em ${File(saida).absolutePath}")
        if (modo == "apply") aplicar(plano)
    }

    private fun colecao(nome: String): List<Document> = mongo.getCollection(nome).find().toList()

    private fun aplicar(plano: RevisaoPlano) {
        plano.presets.novos.forEach { mongo.getCollection("effect_presets").insertOne(it) }
        plano.skills.forEach { r ->
            val set = Document(r.set)
            val unset = Document(r.unset.associateWith { "" })
            val update = Document()
            if (set.isNotEmpty()) update["\$set"] = set
            if (unset.isNotEmpty()) update["\$unset"] = unset
            if (update.isNotEmpty()) mongo.getCollection("skills").updateOne(Document("_id", r.id), update)
        }
        plano.itensAjustados.forEach { mongo.getCollection("item_catalog").replaceOne(Document("_id", it["_id"]), it) }
        plano.kitsAjustados.forEach { mongo.getCollection("class_kits").replaceOne(Document("_id", it["_id"]), it) }
        plano.inimigosAjustados.forEach { mongo.getCollection("enemy_templates").replaceOne(Document("_id", it["_id"]), it) }
        plano.bossesAjustados.forEach { mongo.getCollection("boss_templates").replaceOne(Document("_id", it["_id"]), it) }
        plano.playersAjustados.forEach { mongo.getCollection("players").replaceOne(Document("_id", it["_id"]), it) }
        // Maestria por skill sai (agora é por grupo, no jogador)
        mongo.getCollection("player_skills").updateMany(Document(), Document("\$unset", Document("maestria", "")))
        // Essências Grandes passam a se chamar Elementais
        mongo.getCollection("essencias").updateMany(Document("type", "Grande"), Document("\$set", Document("type", "Elemental")))
        // Derivadas existentes: liberadas pela mãe a partir do nível 1 (mantém o acesso de hoje; o mestre ajusta)
        mongo.getCollection("essencias").updateMany(
            Document("type", "Derivada").append("parentId", Document("\$ne", null)).append("nivelMaeNecessario", Document("\$exists", false)),
            Document("\$set", Document("nivelMaeNecessario", 1))
        )
        mongo.getCollection("game_state").updateOne(
            Document("_id", "main"), Document("\$unset", Document("combateConfig.divisorAtaqueBasico", ""))
        )

        // Recalcula efeitos passivos, atributos efetivos, Capacidade de Essência e vitais
        val skills = skillRepository.findAll()
        val essencias = essenciaRepository.findAll()
        playerRepository.findAll().forEach { p ->
            val sync = attributeService.syncPassiveSelfEffects(p, skills)
            playerRepository.save(attributeService.recalculateVitals(sync, attributeService.computeEffectiveAttributes(sync, essencias)))
        }
        // O buff da Sessão 0 entrou neste rebalanceamento: marca como aplicado para o runner antigo não rodar depois
        gameStateService.save(gameStateService.getOrCreate().let { it.copy(migracoes = (it.migracoes + RevisaoMigracao.NOME + BuffMigrationRunner.NOME).distinct()) })
        log.warn("Migração ${RevisaoMigracao.NOME} aplicada: ${plano.skills.size} skills, ${plano.presets.novos.size} condições novas, " +
            "${plano.itensAjustados.size} itens, ${plano.kitsAjustados.size} classes, ${plano.playersAjustados.size} jogadores")
    }
}

data class RevisaoDados(
    val skills: List<Document>,
    val presets: List<Document>,
    val itens: List<Document>,
    val kits: List<Document>,
    val players: List<Document>,
    val essencias: List<Document>,
    val playerSkills: List<Document> = emptyList(),
    val inimigos: List<Document> = emptyList(),
    val bosses: List<Document> = emptyList(),
    val kitsOriginais: List<Document> = emptyList(),
    val buff: Document? = null
) {
    companion object {
        /** Pacote do buff da Sessão 0 (gerado junto com o buff-diff.md). */
        fun buffPadrao(): Document? = RevisaoDados::class.java.getResourceAsStream("/migration/buff-sessao0.json")
            ?.use { Document.parse(it.readBytes().toString(Charsets.UTF_8)) }
    }
}

/** Tudo o que a migração vai mudar (documentos já ajustados), usado pelo relatório e pela aplicação. */
class RevisaoPlano(
    val skills: List<RevisaoMigracao.ResultadoSkill>,
    val presets: RevisaoMigracao.Presets,
    val armasCatalogo: List<RevisaoMigracao.ResultadoArma>,
    val itensAjustados: List<Document>,
    val kitsAjustados: List<Document>,
    val playersAjustados: List<Document>,
    val armasJogadores: List<Pair<String, RevisaoMigracao.ResultadoArma>>,
    val jogadores: List<RevisaoJogadores.Relatorio> = emptyList(),
    val inimigosAjustados: List<Document> = emptyList(),
    val bossesAjustados: List<Document> = emptyList(),
    val linhasInimigos: List<RevisaoEquilibrio.LinhaInimigo> = emptyList(),
    val linhasBosses: List<RevisaoEquilibrio.LinhaInimigo> = emptyList(),
    val correcoesItens: List<String> = emptyList(),
    val foraDaCurva: List<String> = emptyList(),
    val jogadoresForaDoNivel: List<String> = emptyList()
) {
    companion object {
        fun montar(d: RevisaoDados, cfg: CombateConfig): RevisaoPlano {
            val presets = RevisaoMigracao.Presets(d.presets)
            val skills = d.skills.map { RevisaoMigracao.transformarSkill(it, presets, cfg) }

            val buff = RevisaoEquilibrio.Buff(d.buff ?: Document())
            val correcoes = mutableListOf<String>()
            val armas = mutableListOf<RevisaoMigracao.ResultadoArma>()
            val itens = d.itens.map { Document(it) }.filter { doc ->
                val fix = buff.corrigirItem(doc).also { f -> if (f.isNotEmpty()) correcoes += "${doc.getString("name")}: ${f.joinToString(", ")}" }
                val arma = RevisaoMigracao.ajustarArma(doc)?.also { armas += it }
                fix.isNotEmpty() || arma != null
            }

            val proficienciasPorClasse = mutableMapOf<String, List<String>>()
            val kits = d.kits.map { Document(it) }.onEach { k ->
                val tipos = RevisaoMigracao.expandirCategorias((k["allowedWeaponTypes"] as? List<*>)?.filterIsInstance<String>() ?: emptyList())
                k["allowedWeaponTypes"] = tipos
                k["proficienciasIniciais"] = tipos
                proficienciasPorClasse[k.getString("class") ?: ""] = tipos
            }

            val armasJogadores = mutableListOf<Pair<String, RevisaoMigracao.ResultadoArma>>()
            val infoSkills = skills.associate { r ->
                r.id.toString() to RevisaoJogadores.InfoSkill(r.nome, r.tipo, r.tier, r.categorias, r.essenciaId)
            }
            val nomeEssencia = d.essencias.associate { it["_id"].toString() to (it.getString("name") ?: "?") }
            val jogadores = mutableListOf<RevisaoJogadores.Relatorio>()
            val players = d.players.map { Document(it) }.onEach { p ->
                val nome = (p["char"] as? Document)?.getString("name") ?: "?"
                val classe = (p["char"] as? Document)?.getString("skillClass") ?: ""
                // Sem proficiência (campo ausente ou lista vazia gravada pela API nova): as da classe
                if ((p["proficiencias"] as? List<*>).isNullOrEmpty()) p["proficiencias"] = proficienciasPorClasse[classe] ?: emptyList<String>()
                jogadores += RevisaoJogadores.ajustar(p, d.playerSkills, infoSkills, d.essencias) { nomeEssencia[it] ?: it }
                val eq = p["equipment"] as? Document
                listOfNotNull(eq?.get("mainHand") as? Document, eq?.get("offHand") as? Document, eq?.get("armor") as? Document).forEach { w ->
                    buff.corrigirItem(w)
                    if (w.containsKey("weaponType")) {
                        w["type"] = "weapon"
                        RevisaoMigracao.ajustarArma(w)?.let { armasJogadores += nome to it }
                        w.remove("type")
                    }
                }
                RevisaoMigracao.listaDocs(p["items"]).forEach { item ->
                    buff.corrigirItem(item)
                    RevisaoMigracao.ajustarArma(item)?.let { armasJogadores += nome to it }
                }
            }
            val linhasInimigos = mutableListOf<RevisaoEquilibrio.LinhaInimigo>()
            val inimigos = d.inimigos.map { Document(it) }.onEach { linhasInimigos += RevisaoEquilibrio.ajustarInimigo(it, buff) }
            val linhasBosses = mutableListOf<RevisaoEquilibrio.LinhaInimigo>()
            val bosses = d.bosses.map { Document(it) }.onEach { linhasBosses += RevisaoEquilibrio.ajustarBoss(it, buff) }
            return RevisaoPlano(
                skills, presets, armas, itens, kits, players, armasJogadores, jogadores,
                inimigos, bosses, linhasInimigos, linhasBosses, correcoes, foraDaCurva(skills),
                foraDoNivel(d.players, d.kitsOriginais)
            )
        }

        /** Skills com dano médio novo fora de 0,5× a 2× da mediana do tier no mesmo tipo (para revisar). */
        private fun foraDaCurva(skills: List<RevisaoMigracao.ResultadoSkill>): List<String> =
            skills.filter { it.tier != null && it.medioDepois != null }
                .groupBy { it.tipo to it.tier }
                .flatMap { (_, l) ->
                    val med = l.mapNotNull { it.medioDepois }.sorted().let { it[it.size / 2] }
                    l.filter { it.medioDepois!! > med * 2 || it.medioDepois < med * 0.5 }
                        .map { "${it.nome} (${it.tipo}, Tier ${it.tier}): médio ${"%.0f".format(it.medioDepois)} contra mediana ${"%.0f".format(med)}" }
                }

        private val RARIDADE = listOf("branco", "verde", "azul", "roxo", "amarelo", "rosa")

        /** Raridade esperada da arma pelo nível do personagem. */
        private fun raridadeEsperada(nivel: Int) = when { nivel <= 3 -> 1; nivel <= 6 -> 2; nivel <= 10 -> 3; nivel <= 15 -> 4; else -> 5 }

        /** Jogadores com atributos ou arma fora do esperado para o nível (para o mestre decidir). */
        private fun foraDoNivel(players: List<Document>, kits: List<Document>): List<String> = players.flatMap { p ->
            val c = p["char"] as? Document
            val nome = c?.getString("name") ?: "?"
            val nivel = RevisaoMigracao.num(c?.get("level")) ?: 1
            val avisos = mutableListOf<String>()
            val kit = kits.firstOrNull { it.getString("class") == c?.getString("skillClass") }
            val inicial = (kit?.get("starterAttributes") as? Document)?.values?.sumOf { RevisaoMigracao.num(it) ?: 0 }
            val atual = (p["attributes"] as? Document)?.values?.sumOf { RevisaoMigracao.num(it) ?: 0 } ?: 0
            if (inicial != null && inicial > 0) {
                val esperado = inicial + 3 * (nivel - 1)
                if (kotlin.math.abs(atual - esperado) > 3) avisos += "$nome (nível $nivel): soma de atributos $atual, esperado cerca de $esperado"
            }
            val arma = (p["equipment"] as? Document)?.get("mainHand") as? Document
            val rar = RARIDADE.indexOf(arma?.getString("rarity"))
            if (arma != null && rar > raridadeEsperada(nivel) + 1)
                avisos += "$nome (nível $nivel): arma ${arma.getString("name")} é ${arma.getString("rarity")}, acima do esperado para o nível"
            avisos
        }

        fun relatorio(p: RevisaoPlano, d: RevisaoDados): String = buildString {
            fun f(v: Double?) = v?.let { String.format(Locale.forLanguageTag("pt-BR"), "%.1f", it) } ?: ""
            appendLine("# Revisão de skills, armas e Essências: antes e depois")
            appendLine()
            appendLine("Nada foi aplicado. Dano médio = base + 10,5 × mod / equilíbrio, com o mod típico da faixa de nível")
            appendLine("(nv1 a 5: 2, nv6 a 9: 3, nv10 a 13: 4, nv14 a 17: 5, nv18 a 20: 6).")
            appendLine()
            appendLine("Rebalanceamento: o tier sai do poder pretendido (nível que a skill pedia: 1 a 5 Tier 1, 6 a 9 Tier 2, 10 a 14 Tier 3, 15+ ou ultimate Tier 4);")
            appendLine("o dano médio de cada skill fica o atual + 25% (buff da Sessão 0) com o equilíbrio do tier; o custo de Fluxo sobe até o custo base do tier.")
            appendLine("Armas: dano base compensado para o ataque com proficiência (/5) render o mesmo que no /3 de hoje. Inimigos e bosses: ajustes do buff da Sessão 0 e HP × 1,2.")
            appendLine()

            val comTier = p.skills.filter { it.tier != null }
            appendLine("## Tier por skill (${comTier.size} skills de jogador)")
            appendLine()
            appendLine("| Tier | Skills |")
            appendLine("|---|---|")
            com.rpg.essentia.model.TIERS_PADRAO.map { it.tier }.forEach { t -> appendLine("| $t | ${comTier.count { it.tier == t }} |") }
            appendLine()
            appendLine("| Skill | Tipo | Custo | Tier | Fórmula antes | Fórmula depois | Médio antes | Médio depois | Avisos |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            comTier.sortedWith(compareBy({ it.tier }, { it.nome })).forEach { r ->
                appendLine("| ${r.nome} | ${r.tipo} | ${r.custo ?: "sem"} | ${r.tier} | ${r.formulaAntes ?: ""} | ${r.formulaDepois ?: ""} | ${f(r.medioAntes)} | ${f(r.medioDepois)} | ${r.avisos.joinToString("; ")} |")
            }
            appendLine()

            val comEfeitos = p.skills.filter { it.efeitosAntes.isNotEmpty() }
            appendLine("## Efeitos: antes e depois (${comEfeitos.size} skills)")
            appendLine()
            appendLine("| Skill | Antes | Depois |")
            appendLine("|---|---|---|")
            comEfeitos.sortedBy { it.nome }.forEach { r ->
                appendLine("| ${r.nome} | ${r.efeitosAntes.joinToString("<br>")} | ${r.efeitosDepois.joinToString("<br>")} |")
            }
            appendLine()

            appendLine("## Condições cadastradas novas (${p.presets.novos.size})")
            appendLine()
            appendLine("Efeitos que estavam escritos dentro das skills e não existiam como condição cadastrada.")
            appendLine()
            p.presets.notas.forEach { appendLine("- $it") }
            appendLine()

            appendLine("## Armas do catálogo (${p.armasCatalogo.size})")
            appendLine()
            appendLine("| Arma | Categoria antes | Categoria depois | Dano base | Requisito de atributo removido |")
            appendLine("|---|---|---|---|---|")
            p.armasCatalogo.sortedBy { it.nome }.forEach { a ->
                val base = if (a.baseAntes != null) "${a.baseAntes} → ${a.baseDepois}" else ""
                appendLine("| ${a.nome} | ${a.tipoAntes ?: ""} | ${a.tipoDepois ?: ""} | $base | ${a.requisitosRemovidos?.entries?.joinToString(", ") { "${it.key} ${it.value}" } ?: ""} |")
            }
            appendLine()
            appendLine("Compensação do dano base por raridade: ${listOf("branco", "verde", "azul", "roxo", "amarelo", "rosa").joinToString(", ") { "$it +${RevisaoEquilibrio.compensacaoArma(it)}" }}.")
            if (p.correcoesItens.isNotEmpty()) {
                appendLine()
                appendLine("Correções de itens (do buff da Sessão 0): " + p.correcoesItens.joinToString("; ") + ".")
            }
            appendLine()
            if (p.armasJogadores.isNotEmpty()) {
                appendLine("Cópias com os jogadores (inventário e equipamento): " +
                    p.armasJogadores.joinToString("; ") { (j, a) -> "${a.nome} de $j" } + ".")
                appendLine()
            }

            appendLine("## Inimigos (HP × ${"%.1f".format(RevisaoEquilibrio.HP_INIMIGOS).replace('.', ',')}, ajustes do buff da Sessão 0, dano não muda)")
            appendLine()
            appendLine("| Inimigo | HP | DEF | Acerto | ES máx | XP |")
            appendLine("|---|---|---|---|---|---|")
            p.linhasInimigos.forEach { appendLine("| ${it.nome} | ${it.hpAntes} → ${it.hpDepois} | ${it.defesa} | ${it.acerto} | ${it.es} | ${it.xp} |") }
            appendLine()
            appendLine("| Boss | HP por fase | DEF | Acerto | ES máx | XP |")
            appendLine("|---|---|---|---|---|---|")
            p.linhasBosses.forEach { appendLine("| ${it.nome} | ${it.hpAntes} → ${it.hpDepois} | ${it.defesa} | ${it.acerto} | ${it.es} | ${it.xp} |") }
            appendLine()

            if (p.foraDaCurva.isNotEmpty()) {
                appendLine("## Skills fora da curva depois do rebalanceamento (${p.foraDaCurva.size}), para revisar")
                appendLine()
                appendLine("Dano médio novo abaixo de metade ou acima do dobro da mediana do mesmo tipo e tier. Nada muda sozinho; diga quais ajustar.")
                appendLine()
                p.foraDaCurva.forEach { appendLine("- $it") }
                appendLine()
            }

            appendLine("## Jogadores fora do esperado para o nível")
            appendLine()
            appendLine("Atributos: soma inicial do kit + 3 por nível. Arma: raridade esperada pelo nível (1 a 3 verde, 4 a 6 azul, 7 a 10 roxo, 11 a 15 amarelo, 16+ rosa, com 1 de folga).")
            appendLine("Nada disso muda sozinho: a arma e os atributos vieram da história, então a decisão é sua.")
            appendLine()
            if (p.jogadoresForaDoNivel.isEmpty()) appendLine("Nenhum.") else p.jogadoresForaDoNivel.forEach { appendLine("- $it") }
            appendLine()

            appendLine("## Proficiências iniciais por classe")
            appendLine()
            p.kitsAjustados.forEach { k ->
                appendLine("- **${k.getString("class")}**: ${(k["proficienciasIniciais"] as List<*>).joinToString(", ")}")
            }
            appendLine()

            appendLine("## Jogadores: maestria por grupo")
            appendLine()
            appendLine("Por grupo, vale a skill de maior nível antigo (até 4) com os usos dela; as escolhas de Aumento e Otimização saem.")
            appendLine("A maestria de arma só existe nas categorias com proficiência e sobe até o maior tier das skills de arma que o jogador já tem.")
            appendLine()
            appendLine("| Jogador | Grupo | Tipo | Nível | Usos | Origem do nível |")
            appendLine("|---|---|---|---|---|---|")
            p.jogadores.forEach { j -> j.grupos.forEach { g ->
                appendLine("| ${j.jogador} | ${g.grupo} | ${g.tipo} | ${g.nivel} | ${g.usos} | ${g.origem} |")
            } }
            appendLine()

            val divergentes = p.jogadores.filter { it.divergencias.isNotEmpty() }
            appendLine("## Grupos com skills em níveis diferentes (${divergentes.sumOf { it.divergencias.size }})")
            appendLine()
            divergentes.forEach { j -> j.divergencias.forEach { appendLine("- **${j.jogador}**, $it: fica o maior") } }
            appendLine()

            appendLine("## Nível das Essências e Capacidade")
            appendLine()
            appendLine("Cada Essência começa no maior tier das skills dela que o jogador já tem (mínimo 1), para nada ficar bloqueado. Capacidade = 4 + mod de SAB base.")
            appendLine()
            appendLine("| Jogador | Essências (nível) | Uso | Capacidade | Excedente |")
            appendLine("|---|---|---|---|---|")
            p.jogadores.filter { it.niveisEssencia.isNotEmpty() }.forEach { j ->
                val exc = (j.uso - j.capacidade).coerceAtLeast(0)
                appendLine("| ${j.jogador} | ${j.niveisEssencia.entries.joinToString(", ") { "${it.key} ${it.value}" }} | ${j.uso} | ${j.capacidade} | ${if (exc > 0) "+$exc" else "0"} |")
            }
            appendLine()

            appendLine("## Skills de classe bloqueadas até você subir o nível de classe")
            appendLine()
            appendLine("Todo mundo começa no nível de classe 1.")
            appendLine()
            p.jogadores.filter { it.classeBloqueadas.isNotEmpty() }.forEach { appendLine("- **${it.jogador}**: ${it.classeBloqueadas.joinToString(", ")}") }
            appendLine()
            val semProf = p.jogadores.filter { it.semProficiencia.isNotEmpty() }
            if (semProf.isNotEmpty()) {
                appendLine("## Skills de arma sem proficiência na categoria")
                appendLine()
                semProf.forEach { appendLine("- **${it.jogador}**: ${it.semProficiencia.joinToString(", ")}") }
                appendLine()
            }

            val multi = p.skills.filter { it.tipo == "weapon" && it.categorias.size > 1 }
            appendLine("## Skills de arma com várias categorias (${multi.size})")
            appendLine()
            appendLine("O grupo de maestria fica na primeira categoria. Com outra arma da lista, a skill só funciona com maestria naquela arma também.")
            appendLine()
            multi.sortedBy { it.nome }.forEach { appendLine("- ${it.nome}: ${it.categorias.joinToString(" + ")}, maestria em **${it.categorias.first()}**") }
            appendLine()

            val removidos = p.skills.filter { it.requisitosRemovidos != null }
            appendLine("## Requisitos antigos removidos das skills (${removidos.size})")
            appendLine()
            appendLine("Nível do personagem e atributo deixam de existir; o requisito agora é a origem com o tier.")
            appendLine()
            removidos.filter { it.requisitosRemovidos!!.contains(",") || !it.requisitosRemovidos.startsWith("nível") }
                .sortedBy { it.nome }.forEach { appendLine("- ${it.nome}: ${it.requisitosRemovidos}") }
            appendLine("- e mais ${removidos.count { it.requisitosRemovidos!!.startsWith("nível") && !it.requisitosRemovidos.contains(",") }} skills que só pediam nível do personagem")
        }
    }
}
