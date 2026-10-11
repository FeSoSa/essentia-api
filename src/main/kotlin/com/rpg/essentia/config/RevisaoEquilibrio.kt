package com.rpg.essentia.config

import org.bson.Document
import kotlin.math.roundToInt

/**
 * Rebalanceamento de armas, itens e inimigos (funções puras sobre documentos).
 *   Armas: o ataque básico passou de /3 para /5 (com proficiência) e /6; o dano base sobe para o ataque
 *          com proficiência render o mesmo de hoje, com o mod típico da raridade.
 *   Itens e inimigos: correções e ajustes do buff da Sessão 0 (buff-sessao0.json), sem a parte de skills
 *          (o +25% das skills já entra no rebalanceamento) e sem a maestria por skill (substituída).
 *   Inimigos e bosses: HP × HP_INIMIGOS para acompanhar o dano maior dos jogadores; o dano deles não muda,
 *          porque com o HP compensado a luta dura o mesmo e o perigo fica igual.
 */
object RevisaoEquilibrio {
    const val DIVISOR_ANTIGO = 3.0
    const val HP_INIMIGOS = 1.2

    /** Mod de atributo típico de quem usa cada raridade. */
    private val MOD_RARIDADE = mapOf("branco" to 2, "verde" to 2, "azul" to 3, "roxo" to 4, "amarelo" to 5, "rosa" to 6)

    /** Quanto somar no dano base para o ataque com proficiência render o mesmo que no divisor antigo. */
    fun compensacaoArma(raridade: String?, comProficiencia: Double = 5.0): Int {
        val mod = MOD_RARIDADE[raridade] ?: 2
        return (10.5 * mod * (1.0 / DIVISOR_ANTIGO - 1.0 / comProficiencia)).roundToInt()
    }

    /** Ajusta o dano base da arma no lugar. Retorna (antes, depois) ou null se a arma não rola d20. */
    fun compensarArma(doc: Document): Pair<Int, Int>? {
        val attr = doc.getString("damageAttribute")
        val base = RevisaoMigracao.num(doc["damageBase"]) ?: return null
        if (attr.isNullOrBlank()) return null
        val nova = base + compensacaoArma(doc.getString("rarity"))
        doc["damageBase"] = nova
        return base to nova
    }

    // ── buff da Sessão 0 ────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    class Buff(dados: Map<String, Any?>) {
        val itens: List<Map<String, Any?>> = (dados["items"] as? List<Map<String, Any?>>).orEmpty().filter { it["opcional"] != true }
        val inimigos: Map<String, Map<String, Any?>> = (dados["enemyTemplates"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"].toString() }
        val bosses: Map<String, Map<String, Any?>> = (dados["bossTemplates"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"].toString() }
        private val itensPorNome = itens.associateBy { it["name"].toString() }

        /** Correções de item (margem de crítico, nega crítico, atributo de dano, raridade) pelo nome. */
        fun corrigirItem(doc: Document): List<String> {
            val fix = itensPorNome[doc.getString("name")] ?: return emptyList()
            return listOf("margemCritico", "negaCritico", "damageAttribute", "rarity").mapNotNull { c ->
                fix[c]?.let { v -> if (doc[c] != v) { doc[c] = v; "$c $v" } else null }
            }
        }
    }

    data class LinhaInimigo(val nome: String, val hpAntes: String, val hpDepois: String, val defesa: String, val acerto: String, val es: String, val xp: String)

    private fun hp(v: Int): Int = ((v * HP_INIMIGOS) / 5.0).roundToInt() * 5

    /** Inimigo: ajustes do buff + HP. Ajusta no lugar. */
    fun ajustarInimigo(doc: Document, buff: Buff): LinhaInimigo {
        val b = buff.inimigos[doc["_id"].toString()]
        val hpAntes = RevisaoMigracao.num(doc["hpMax"]) ?: 0
        doc["hpMax"] = hp(hpAntes)
        val xpAntes = RevisaoMigracao.num(doc["xp"])
        if (b != null) {
            doc["defesa"] = b["defesa"]; doc["modAcerto"] = b["modAcerto"]; doc["esMax"] = b["esMax"]; doc["xp"] = b["xp"]
            ajustarAtaques(doc["attacks"] as? List<*>, b["custos"] as? List<*> ?: emptyList<Any>(), b["equilibrioZeroFix"] as? List<*> ?: emptyList<Any>())
        }
        return LinhaInimigo(
            doc.getString("name") ?: "?", "$hpAntes", "${doc["hpMax"]}",
            "${doc["defesa"] ?: "padrão"}", "${doc["modAcerto"] ?: "padrão"}", "${doc["esMax"] ?: 0}",
            if (b != null) "$xpAntes → ${doc["xp"]}" else "$xpAntes"
        )
    }

    /** Boss: ajustes do buff por fase + HP de cada fase. Ajusta no lugar. */
    @Suppress("UNCHECKED_CAST")
    fun ajustarBoss(doc: Document, buff: Buff): LinhaInimigo {
        val b = buff.bosses[doc["_id"].toString()]
        val fasesBuff = (b?.get("fases") as? List<Map<String, Any?>>).orEmpty()
        val fases = RevisaoMigracao.listaDocs(doc["phases"])
        val antes = fases.map { RevisaoMigracao.num(it["hpMax"]) ?: 0 }
        fases.forEachIndexed { i, f ->
            f["hpMax"] = hp(antes[i])
            fasesBuff.getOrNull(i)?.let { fb ->
                f["defesa"] = fb["defesa"]; f["modAcerto"] = fb["modAcerto"]; f["esMax"] = fb["esMax"]
                ajustarAtaques(f["attacks"] as? List<*>, fb["custos"] as? List<*> ?: emptyList<Any>(), emptyList<Any>())
            }
        }
        val xpAntes = RevisaoMigracao.num(doc["xp"])
        b?.get("xp")?.let { doc["xp"] = it }
        return LinhaInimigo(
            doc.getString("name") ?: "?", antes.joinToString(" / "), fases.joinToString(" / ") { "${it["hpMax"]}" },
            fases.joinToString(" / ") { "${it["defesa"] ?: "padrão"}" }, fases.joinToString(" / ") { "${it["modAcerto"] ?: "padrão"}" },
            fases.joinToString(" / ") { "${it["esMax"] ?: 0}" }, if (b != null) "$xpAntes → ${doc["xp"]}" else "$xpAntes"
        )
    }

    private fun ajustarAtaques(ataques: List<*>?, custos: List<*>, zeroFix: List<*>) {
        ataques?.filterIsInstance<Document>()?.forEachIndexed { i, a ->
            (custos.getOrNull(i) as? Number)?.let { a["custoEs"] = it.toInt() }
            if (zeroFix.any { (it as Number).toInt() == i }) {
                a["equilibrio"] = 7
                (a["damage"] as? String)?.let { d -> a["damage"] = d.replace(Regex("/0\\b"), "/7") }
            }
        }
    }
}
