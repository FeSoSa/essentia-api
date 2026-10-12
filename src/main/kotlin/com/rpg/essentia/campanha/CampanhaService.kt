package com.rpg.essentia.campanha

import com.rpg.essentia.campanha.vinculos.CampanhaVinculos
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

/** Junta arquivos, estado e vínculos num snapshot e avisa o painel a cada mudança. */
@Service
class CampanhaService(
    private val files: CampanhaFileService,
    private val estado: CampanhaEstadoService,
    private val grafo: CampanhaGrafoService,
    private val vinculos: ObjectProvider<CampanhaVinculos>,
    private val messaging: SimpMessagingTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun snapshot(): CampanhaSnapshot {
        val pontos = files.pontos()
        val v = vinculos.getIfAvailable()
        val resolvidos = try { v?.resolver(pontos) } catch (e: Exception) {
            log.warn("Campanha: falha ao resolver vínculos: {}", e.message); null
        }
        try { v?.gerarReferencias()?.let { files.gravarSeMudou("_referencias.md", it) } } catch (e: Exception) {
            log.warn("Campanha: falha ao gerar _referencias.md: {}", e.message)
        }
        return CampanhaSnapshot(
            pasta = files.raiz.toString(),
            capitulos = files.capitulos(),
            pontos = pontos.map { resolvidos?.get(it.id)?.ponto ?: it },
            estado = estado.todos(),
            grafo = grafo.atual(),
            avisos = resolvidos?.mapValues { it.value.avisos }?.filterValues { it.isNotEmpty() } ?: emptyMap(),
            erros = files.erros()
        )
    }

    /** Chamado pelo watcher quando algo muda na pasta. */
    fun pastaMudou() {
        if (files.rescan()) broadcast()
    }

    fun <T> alterar(acao: () -> T): CampanhaSnapshot {
        acao()
        return broadcast()
    }

    fun removerPonto(id: String) = alterar {
        files.removerPonto(id)
        estado.esquecer(id)
        grafo.esquecer(id)
    }

    private fun broadcast(): CampanhaSnapshot {
        val s = snapshot()
        messaging.convertAndSend("/topic/campanha", s)
        return s
    }
}
