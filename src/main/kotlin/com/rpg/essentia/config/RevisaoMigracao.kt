package com.rpg.essentia.config

import com.rpg.essentia.model.CombateConfig
import com.rpg.essentia.service.SkillCatalogService
import com.rpg.essentia.service.TierRules
import org.bson.Document
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Transformações da revisão de skills, armas e Essências, sobre documentos crus do Mongo.
 * Funções puras: o RevisaoMigrationRunner usa para o dry-run (relatório) e para aplicar.
 *
 *   Skills:  tier pelo custo de Fluxo, base ajustada para manter o dano médio, equilíbrio sai da fórmula,
 *            campos antigos de efeito viram a lista `efeitos` (condições viram presets cadastrados).
 *   Armas:   "a-distancia" vira arcos/bestas/pistolas pelo nome; requisitos de atributo saem.
 *   Classes: proficienciasIniciais = tipos de arma permitidos.
 *   Players: proficiências da classe, nível 1 nas Essências, armas reclassificadas.
 */
object RevisaoMigracao {
    const val NOME = "revisao-tier-efeitos"
    const val TIPO_ARMA = "ARMA"
    const val TIPO_ESSENCIA = "ESSENCIA"

    private val CAMPOS_ANTIGOS = listOf(
        "passiveAttributes", "buffAttributes", "buffDurationTurns", "hitBonus", "attackBonus",
        "damageBonus", "onHitEffects", "selfEffects"
    )
    private val ATRIBUTO_PARA_STAT = mapOf(
        "strength" to "FOR", "agility" to "AGI", "intelligence" to "INT", "resistance" to "RES",
        "flow" to "FLX", "wisdom" to "SAB", "presence" to "PRE", "defense" to "DEF"
    )

    // ── Tier ────────────────────────────────────────────────────────────

    /** Maior tier cujo custo base cabe no custo de Fluxo atual (sem custo = Tier 1). */
    fun tierPorCusto(custo: Int?, cfg: CombateConfig = CombateConfig()): Int =
        cfg.tiers.sortedBy { it.tier }.lastOrNull { custo != null && custo >= it.custoBase }?.tier ?: 1

    /** Buff de dano das skills de jogador (decidido junto com o rebalanceamento). */
    const val BUFF_DANO = 1.25

    /**
     * Tier pelo poder pretendido: o nível que a skill pedia antes e se é ultimate.
     * Nível 1 a 5: Tier 1; 6 a 9: Tier 2; 10 a 14: Tier 3; 15+ ou ultimate: Tier 4.
     */
    fun tierPorPoder(nivel: Int, ultimate: Boolean): Int = when {
        ultimate || nivel >= 15 -> 4
        nivel >= 10 -> 3
        nivel >= 6 -> 2
        else -> 1
    }

    /** Mod típico por faixa de nível (mesma referência do buff-diff.md). */
    fun modTipico(nivel: Int): Int = when {
        nivel <= 5 -> 2; nivel <= 9 -> 3; nivel <= 13 -> 4; nivel <= 17 -> 5; else -> 6
    }

    private fun danoMedio(base: Int, mod: Int, eq: Double) = base + 10.5 * mod / eq

    // ── Condições (presets) ─────────────────────────────────────────────

    class Presets(existentes: List<Document>) {
        private val porNome = existentes.associateBy { it.getString("name").lowercase() }.toMutableMap()
        val novos = mutableListOf<Document>()
        val notas = mutableListOf<String>()

        /** Nome da condição cadastrada equivalente ao StatusEffect embutido na skill (cria se preciso). */
        fun condicaoPara(status: Document, skillNome: String): String {
            val nome = status.getString("name")
            val assinatura = assinatura(status)
            val existente = porNome[nome.lowercase()]
            if (existente != null && assinatura(existente) == assinatura) return existente.getString("name")
            val nomeFinal = if (existente == null) nome else "$nome ($skillNome)"
            porNome[nomeFinal.lowercase()]?.let { if (assinatura(it) == assinatura) return it.getString("name") }
            val novo = Document("_id", UUID.randomUUID().toString())
                .append("name", nomeFinal)
                .append("icon", status["icon"])
                .append("color", status["color"])
                .append("desc", status.getString("desc") ?: "")
                .append("durationTurns", num(status["durationTurns"]) ?: 1)
                .append("effects", listaDocs(status["effects"]))
                .append("hitBonus", acertoDe(status))
            novos += novo
            porNome[nomeFinal.lowercase()] = novo
            notas += if (existente == null) "Nova condição **$nomeFinal** (vinda de $skillNome)"
                     else "Nova condição **$nomeFinal**: \"$nome\" já existe com mecânica diferente"
            return nomeFinal
        }

        /** Mecânica do efeito, ignorando nome, texto e duração. */
        private fun assinatura(d: Document): String =
            listaDocs(d["effects"]).joinToString("|") { e ->
                listOf("trigger", "type", "value", "percentual", "attribute", "costType").joinToString(",") { "${e[it]}" }
            } + "#" + acertoDe(d)

        /** attackBonus vira acerto (decisão da revisão). */
        private fun acertoDe(d: Document): Int? {
            val total = (num(d["hitBonus"]) ?: 0) + (num(d["attackBonus"]) ?: 0)
            return total.takeIf { it != 0 }
        }
    }

    // ── Skills ──────────────────────────────────────────────────────────

    data class ResultadoSkill(
        val id: Any,
        val nome: String,
        val tipo: String,
        val set: Map<String, Any?>,
        val unset: List<String>,
        val tier: Int?,
        val custo: Int?,
        val formulaAntes: String?,
        val formulaDepois: String?,
        val medioAntes: Double?,
        val medioDepois: Double?,
        val usaArma: Boolean,
        val efeitosAntes: List<String>,
        val efeitosDepois: List<String>,
        val avisos: List<String>,
        val categorias: List<String> = emptyList(),
        val essenciaId: String? = null,
        val requisitosRemovidos: String? = null,
        val nivelAntigo: Int = 1,
        val ultimate: Boolean = false
    )

    fun transformarSkill(s: Document, presets: Presets, cfg: CombateConfig = CombateConfig()): ResultadoSkill {
        val nome = s.getString("name")
        val tipo = s.getString("type") ?: ""
        val set = linkedMapOf<String, Any?>()
        val unset = CAMPOS_ANTIGOS.filter { s.containsKey(it) }.toMutableList()
        val avisos = mutableListOf<String>()

        val custo = listaDocs(s["costs"]).firstOrNull { it.getString("type") == "flow" }?.let { num(it["value"]) }
        val damage = s["damage"] as? Document
        val nivel = (s["requirements"] as? Document)?.let { num(it["level"]) } ?: 1
        val usaArma = s.getString("damageSource") == "weapon"

        var tier: Int? = null
        var formulaAntes: String? = damage?.getString("formula")
        var formulaDepois: String? = null
        var medioAntes: Double? = null
        var medioDepois: Double? = null

        // Tier: arma, Essência e classe com classe definida (Geral, mestre e inimigo ficam com o equilíbrio próprio)
        val usaTier = tipo == "weapon" || tipo == "essencia" || (tipo == "class" && !s.getString("skillClass").isNullOrBlank())
        if (usaTier) {
            tier = tierPorPoder(nivel, s.getBoolean("ultimate") ?: false)
            set["tier"] = tier
            // Custo de Fluxo nunca abaixo do custo base do tier
            val custoBase = cfg.tier(tier).custoBase
            if (custo != null && custo < custoBase) {
                val custos = listaDocs(s["costs"]).map { c ->
                    if (c.getString("type") == "flow") Document(c).append("value", custoBase) else c
                }
                set["costs"] = custos
                avisos += "custo $custo subiu para $custoBase (base do Tier $tier)"
            }
            val eqNovo = cfg.tier(tier).equilibrio
            if (usaArma) {
                formulaAntes = "arma /${cfg.equilibrioSemProficiencia.fmt()} a /${cfg.equilibrioComProficiencia.fmt()} (antes /3)"
                formulaDepois = "arma /${eqNovo.fmt()}"
            } else if (damage != null) {
                val base = num(damage["baseFixed"]) ?: 0
                val atributo = damage.getString("atributo")?.takeIf { it.isNotBlank() }
                val eqAntigo = num(damage["equilibrio"])
                var novaBase = base
                if (atributo != null && eqAntigo != null && eqAntigo > 0) {
                    val mod = modTipico(nivel)
                    // Dano médio alvo = atual × 1,25 (buff), com o equilíbrio do tier: base = alvo - 10,5 × mod / eqNovo
                    medioAntes = danoMedio(base, mod, eqAntigo.toDouble())
                    novaBase = (medioAntes * BUFF_DANO - 10.5 * mod / eqNovo).roundToInt()
                    if (novaBase < 0) {
                        avisos += "base ficaria $novaBase; ajustada para 0 (dano médio sobe mais que 25%)"
                        novaBase = 0
                    }
                    medioDepois = danoMedio(novaBase, mod, eqNovo)
                    if (eqAntigo > 8) avisos += "equilíbrio antigo /$eqAntigo fora do padrão"
                } else if (atributo != null) {
                    avisos += "tinha atributo sem equilíbrio: passa a rolar d20×$atributo/${eqNovo.fmt()}"
                } else if (base > 0) {
                    // Dano fixo, sem d20: só o buff
                    novaBase = (base * BUFF_DANO).roundToInt()
                    medioAntes = base.toDouble(); medioDepois = novaBase.toDouble()
                }
                formulaDepois = TierRules.formula(novaBase, atributo, if (atributo != null) eqNovo else null)
                set["damage.baseFixed"] = novaBase
                set["damage.formula"] = formulaDepois
                if (damage.containsKey("equilibrio")) unset += "damage.equilibrio"
            }
            if (custo == null && listaDocs(s["costs"]).isNotEmpty())
                avisos += "custo não é de Fluxo: tier 1 por padrão"
        }

        // Skill de arma: grupo de maestria é a primeira categoria
        val categorias = ((s["weaponTypes"] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(s.getString("weaponType")))
            .map { it.lowercase() }.flatMap { expandirCategorias(listOf(it)) }.distinct()
        if (tipo == "weapon" && categorias.isNotEmpty()) {
            if (s["grupoMaestria"] == null) set["grupoMaestria"] = categorias.first()
            if (categorias.size > 1) avisos += "várias categorias (${categorias.joinToString("+")}): maestria em ${categorias.first()}"
        }
        // Requisitos antigos (nível do personagem, atributo) e maestria por skill saem
        val req = s["requirements"] as? Document
        val requisitosRemovidos = req?.let { r ->
            listOfNotNull(
                num(r["level"])?.let { "nível $it" },
                (r["attributes"] as? Document)?.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ") { "${it.key} ${it.value}" }
            ).joinToString("; ").ifEmpty { null }
        }
        listOf("requirements", "maestria", "nivelEssencia").filter { s.containsKey(it) }.forEach { unset += it }

        // Efeitos
        val passiva = s.getBoolean("passive") ?: false
        val efeitos = mutableListOf<Document>()
        val antes = mutableListOf<String>()
        val duracaoBuff: Any = num(s["buffDurationTurns"]) ?: 1
        if (duracaoBuff == -1) avisos += "buff com duração -1 (até o mestre remover) mantido como -1"

        (s["buffAttributes"] as? Document)?.forEach { (attr, v) ->
            antes += "buffAttributes $attr ${sinal(num(v))} por $duracaoBuff"
            efeitos += modificador(ATRIBUTO_PARA_STAT[attr] ?: attr.uppercase(), num(v) ?: 0, duracaoBuff)
        }
        val hit = num(s["hitBonus"]); val atk = num(s["attackBonus"]); val dano = num(s["damageBonus"])
        hit?.let { antes += "hitBonus ${sinal(it)} por $duracaoBuff" }
        atk?.let { antes += "attackBonus ${sinal(it)} por $duracaoBuff" }
        // attackBonus vira acerto; se a skill já tinha hitBonus, fica só ele (somar dobraria o efeito)
        if (hit != null && atk != null) avisos += "tinha acerto ${sinal(hit)} e attackBonus ${sinal(atk)}: fica só acerto ${sinal(hit)}"
        (hit ?: atk)?.let { efeitos += modificador("ACERTO", it, duracaoBuff) }
        dano?.let { antes += "damageBonus ${sinal(it)} por $duracaoBuff"; efeitos += modificador("DANO", it, duracaoBuff) }
        (s["passiveAttributes"] as? Document)?.forEach { (attr, v) ->
            antes += "passiveAttributes $attr ${sinal(num(v))}"
            efeitos += modificador(ATRIBUTO_PARA_STAT[attr] ?: attr.uppercase(), num(v) ?: 0, "PASSIVO")
        }
        listaDocs(s["selfEffects"]).forEach { st ->
            val dur: Any = if (passiva) "PASSIVO" else num(st["durationTurns"]) ?: 1
            antes += "selfEffects ${st.getString("name")} ${num(st["durationTurns"])} turnos"
            efeitos += condicao("SELF", presets.condicaoPara(st, nome), dur)
        }
        listaDocs(s["onHitEffects"]).forEach { st ->
            antes += "onHitEffects ${st.getString("name")} ${num(st["durationTurns"])} turnos"
            efeitos += condicao("ALVO", presets.condicaoPara(st, nome), num(st["durationTurns"]) ?: 1)
        }
        // Vida extra em aliados escrita na descrição ("Aliados: +36 PV") vira Escudo (cura não; "+X PV/turno" é cura)
        Regex("""Aliados:\s*\+(\d+)\s*PV(?!\s*/)""", RegexOption.IGNORE_CASE).find(s.getString("desc") ?: "")?.let { m ->
            val v = m.groupValues[1].toInt()
            antes += "texto \"Aliados: +$v PV\""
            efeitos += Document("alvo", "ALIADOS").append("tipo", "ESCUDO").append("valor", v)
        }
        if (unset.any { it in CAMPOS_ANTIGOS } || efeitos.isNotEmpty()) set["efeitos"] = efeitos

        // Em quem a skill é usada: dano ou efeito no alvo = inimigos; efeito em aliados ou "aliado" no texto = aliados; senão o usuário
        if (s["alvo"] == null && tipo in SkillCatalogService.TIPOS_COM_TIER) {
            val alvos = efeitos.map { it.getString("alvo") }
            set["alvo"] = when {
                damage != null || usaArma || alvos.any { it == "ALVO" || it == "AREA" } -> "INIMIGOS"
                alvos.any { it == "ALIADOS" } || Regex("alia(do|da)", RegexOption.IGNORE_CASE).containsMatchIn(s.getString("desc") ?: "") -> "ALIADOS"
                else -> "USUARIO"
            }
        }

        return ResultadoSkill(
            id = s["_id"]!!, nome = nome, tipo = tipo, set = set, unset = unset, tier = tier, custo = custo,
            formulaAntes = formulaAntes, formulaDepois = formulaDepois, medioAntes = medioAntes, medioDepois = medioDepois,
            usaArma = usaArma, efeitosAntes = antes, efeitosDepois = efeitos.map { descrever(it) }, avisos = avisos,
            categorias = categorias, essenciaId = s.getString("essenciaId"), requisitosRemovidos = requisitosRemovidos,
            nivelAntigo = nivel, ultimate = s.getBoolean("ultimate") ?: false
        )
    }

    private fun modificador(stat: String, valor: Int, duracao: Any) =
        Document("alvo", "SELF").append("tipo", "MODIFICADOR").append("stat", stat).append("valor", valor).append("duracao", duracao)

    private fun condicao(alvo: String, nome: String, duracao: Any) =
        Document("alvo", alvo).append("tipo", "CONDICAO").append("condicao", nome).append("duracao", duracao)

    fun descrever(e: Document): String {
        val dur = e["duracao"].let { if (it == "PASSIVO") "PASSIVO" else "$it rodada(s)" }
        if (e.getString("tipo") == "ESCUDO") return "${e["alvo"]} Escudo +${e["valor"]}"
        return if (e.getString("tipo") == "CONDICAO") "${e["alvo"]} condição ${e["condicao"]}, $dur"
               else "${e["alvo"]} ${e["stat"]} ${sinal(num(e["valor"]))}, $dur"
    }

    // ── Armas ───────────────────────────────────────────────────────────

    /** Categoria de uma arma "a-distancia" pelo nome; null se não reconhecer. */
    fun categoriaDistancia(nome: String): String? {
        val n = nome.lowercase(Locale.ROOT)
        return when {
            n.startsWith("arco") || n.contains("windfang") -> "arcos"
            n.startsWith("besta") -> "bestas"
            n.startsWith("pistola") || n.startsWith("revólver") || n.startsWith("revolver") -> "pistolas"
            else -> null
        }
    }

    data class ResultadoArma(
        val nome: String, val tipoAntes: String?, val tipoDepois: String?, val requisitosRemovidos: Map<String, Any?>?,
        val baseAntes: Int? = null, val baseDepois: Int? = null
    )

    /** Ajusta o documento da arma no lugar (catálogo, inventário ou equipamento). Retorna o que mudou ou null. */
    fun ajustarArma(doc: Document): ResultadoArma? {
        val ehArma = doc.getString("type") == "weapon" || doc.containsKey("weaponType") && doc.getString("type") == null
        if (!ehArma) return null
        val nome = doc.getString("name") ?: ""
        val tipo = doc.getString("weaponType")
        var tipoNovo: String? = null
        if (tipo == "a-distancia") {
            tipoNovo = categoriaDistancia(nome)
            if (tipoNovo != null) doc["weaponType"] = tipoNovo
        }
        var removidos: Map<String, Any?>? = null
        (doc["requirements"] as? Document)?.let { req ->
            (req["attributes"] as? Document)?.takeIf { it.isNotEmpty() }?.let { removidos = it.toMap() }
            req.remove("attributes")
            if (req.values.all { it == null }) doc.remove("requirements")
        }
        // Ataque básico mais lento (/5 e /6): o dano base sobe para o ataque com proficiência render o mesmo de hoje
        val base = RevisaoEquilibrio.compensarArma(doc)
        if (tipoNovo == null && removidos == null && tipo != "a-distancia" && base == null) return null
        return ResultadoArma(nome, tipo, tipoNovo ?: tipo, removidos, base?.first, base?.second)
    }

    /** "a-distancia" nas listas de classe vira as três categorias de distância. */
    fun expandirCategorias(tipos: List<String>): List<String> =
        tipos.flatMap { if (it == "a-distancia") listOf("arcos", "bestas", "pistolas") else listOf(it) }.distinct()

    // ── Util ────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    fun listaDocs(v: Any?): List<Document> = (v as? List<*>)?.filterIsInstance<Document>() ?: emptyList()
    fun num(v: Any?): Int? = (v as? Number)?.toInt()
    private fun sinal(v: Int?) = if ((v ?: 0) >= 0) "+${v ?: 0}" else "$v"
    private fun Double.fmt() = TierRules.formatarEquilibrio(this)
}
