package com.rpg.essentia.campanha

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Posições dos nós na visão em grafo. É só arrumação de tela, então fica em
 * `_grafo.json` e não nos .md: arrastar um nó não mexe no texto da campanha.
 * Nó sem posição salva é arrumado pela física do painel.
 */
@Service
class CampanhaGrafoService(
    private val files: CampanhaFileService,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    private var grafo: CampanhaGrafo = carregar()

    private fun carregar(): CampanhaGrafo = try {
        files.lerArquivo(ARQUIVO)?.let { mapper.readValue(it, CampanhaGrafo::class.java) } ?: CampanhaGrafo()
    } catch (e: Exception) {
        log.warn("Campanha: não deu para ler {}, começando vazio: {}", ARQUIVO, e.message)
        CampanhaGrafo()
    }

    fun atual(): CampanhaGrafo = synchronized(lock) { grafo }

    /** Junta as posições recebidas às que já existem. Valor null solta o nó (volta para a física). */
    fun posicionar(posicoes: Map<String, PosicaoGrafo?>) = synchronized(lock) {
        val novas = LinkedHashMap(grafo.posicoes)
        posicoes.forEach { (id, p) -> if (p == null) novas.remove(id) else novas[id] = p.copy(x = arredonda(p.x), y = arredonda(p.y)) }
        gravar(grafo.copy(posicoes = novas))
    }

    fun soltarTodos() = synchronized(lock) { gravar(grafo.copy(posicoes = emptyMap())) }

    /** Ponto apagado: some do grafo também. */
    fun esquecer(id: String) = synchronized(lock) {
        if (grafo.posicoes.containsKey(id)) gravar(grafo.copy(posicoes = grafo.posicoes - id))
    }

    private fun gravar(g: CampanhaGrafo) {
        grafo = g
        files.gravarSeMudou(ARQUIVO, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(g) + "\n")
    }

    private fun arredonda(d: Double) = Math.round(d * 10) / 10.0

    companion object { const val ARQUIVO = "_grafo.json" }
}
