package com.rpg.essentia.campanha

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer

/**
 * Lê e escreve markdown com cabeçalho YAML entre linhas `---`.
 * O corpo volta exatamente como estava; só o cabeçalho é regravado.
 */
object Frontmatter {

    data class Doc(val campos: Map<String, Any?>, val corpo: String)

    private val BASE = listOf("id", "tipo", "titulo", "capitulo", "ordem", "mapa", "x", "y", "revelado", "ligado_a")

    private fun loader() = Yaml(SafeConstructor(LoaderOptions()))

    private fun dumper(): Yaml {
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 0
            width = 200
            isAllowUnicode = true
        }
        return Yaml(Representer(opts), opts)
    }

    fun parse(texto: String): Doc {
        val t = texto.removePrefix("﻿")
        val primeira = t.indexOf('\n')
        if (primeira < 0 || t.substring(0, primeira).trimEnd() != "---") return Doc(emptyMap(), t)
        var i = primeira + 1
        while (i <= t.length) {
            val fim = t.indexOf('\n', i).let { if (it < 0) t.length else it }
            if (t.substring(i, fim).trimEnd() == "---") {
                val yaml = t.substring(primeira + 1, i)
                val corpo = if (fim < t.length) t.substring(fim + 1) else ""
                @Suppress("UNCHECKED_CAST")
                val campos = (loader().load<Any?>(yaml) as? Map<String, Any?>) ?: emptyMap()
                return Doc(campos, corpo)
            }
            if (fim >= t.length) break
            i = fim + 1
        }
        throw IllegalArgumentException("Cabeçalho aberto com --- mas nunca fechado")
    }

    fun write(campos: Map<String, Any?>, corpo: String): String {
        val limpo = LinkedHashMap<String, Any?>()
        BASE.forEach { k -> campos[k]?.let { limpo[k] = it } }
        campos.forEach { (k, v) -> if (k !in BASE && v != null) limpo[k] = v }
        val yaml = if (limpo.isEmpty()) "" else dumper().dump(limpo)
        return "---\n$yaml---\n$corpo"
    }

    /* ── Conversão ponto <-> campos ─────────────────────────────── */

    fun pontoParaCampos(p: CampanhaPonto): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = p.id
        m["tipo"] = p.tipo
        m["titulo"] = p.titulo
        if (p.capitulo.isNotBlank()) m["capitulo"] = p.capitulo
        m["ordem"] = p.ordem
        p.mapa?.let { m["mapa"] = refParaMapa(it) }
        p.x?.let { m["x"] = arredonda(it) }
        p.y?.let { m["y"] = arredonda(it) }
        m["revelado"] = p.revelado
        if (p.ligadoA.isNotEmpty()) m["ligado_a"] = p.ligadoA
        p.extras.forEach { (k, v) -> if (k !in BASE) m[k] = normaliza(v) }
        return m
    }

    fun camposParaPonto(c: Map<String, Any?>, corpo: String, idPadrao: String, capitulo: String, ordemPadrao: Int = 0): CampanhaPonto =
        CampanhaPonto(
            id = (c["id"] as? String)?.takeIf { it.isNotBlank() } ?: idPadrao,
            tipo = (c["tipo"] as? String)?.lowercase() ?: "narrativo",
            titulo = c["titulo"]?.toString() ?: idPadrao,
            capitulo = capitulo,
            ordem = (c["ordem"] as? Number)?.toInt() ?: ordemPadrao,
            mapa = refDe(c["mapa"]),
            x = (c["x"] as? Number)?.toDouble(),
            y = (c["y"] as? Number)?.toDouble(),
            revelado = c["revelado"] as? Boolean ?: false,
            ligadoA = (c["ligado_a"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            extras = c.filterKeys { it !in BASE },
            corpo = corpo
        )

    /** Aceita `mapa: Tyriade`, `mapa: <id>` ou `mapa: { id, nome }`. */
    fun refDe(v: Any?): CampanhaRef? = when (v) {
        null -> null
        is Map<*, *> -> CampanhaRef(
            id = v["id"]?.toString()?.takeIf { it.isNotBlank() },
            nome = v["nome"]?.toString()?.takeIf { it.isNotBlank() },
            qtd = (v["qtd"] as? Number)?.toInt()
        ).takeIf { it.id != null || it.nome != null }
        else -> v.toString().takeIf { it.isNotBlank() }?.let { CampanhaRef(nome = it) }
    }

    fun refParaMapa(r: CampanhaRef): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        r.id?.let { put("id", it) }
        r.nome?.let { put("nome", it) }
        r.qtd?.let { put("qtd", it) }
    }

    private fun arredonda(d: Double) = Math.round(d * 10000) / 10000.0

    /** O JSON do painel chega com Int/Double/Map/List; o YAML só precisa de tipos simples. */
    private fun normaliza(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().apply { v.forEach { (k, x) -> if (x != null) put(k.toString(), normaliza(x)) } }
        is List<*> -> v.map { normaliza(it) }
        else -> v
    }
}
