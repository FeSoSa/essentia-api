package com.rpg.essentia.campanha

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * O que aconteceu na sessão (status, quem foi para a Mesa, quem achou cada item).
 * Fica em `_estado.json`, separado dos .md, para que jogar não mexa no plano.
 */
@Service
class CampanhaEstadoService(
    private val files: CampanhaFileService,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    private var estado: CampanhaEstado = carregar()

    private fun carregar(): CampanhaEstado = try {
        files.lerArquivo(ARQUIVO)?.let { mapper.readValue(it, CampanhaEstado::class.java) } ?: CampanhaEstado()
    } catch (e: Exception) {
        log.warn("Campanha: não deu para ler {}, começando vazio: {}", ARQUIVO, e.message)
        CampanhaEstado()
    }

    fun todos(): Map<String, PontoEstado> = synchronized(lock) { estado.pontos }

    fun de(id: String): PontoEstado = synchronized(lock) { estado.pontos[id] ?: PontoEstado() }

    /** Só um ponto fica ativo por vez; o anterior volta a planejado. */
    fun ativar(id: String) = alterar { pontos ->
        pontos.mapValues { (k, v) -> if (k != id && v.status == "ativo") v.copy(status = "planejado") else v } +
            (id to (pontos[id] ?: PontoEstado()).copy(status = "ativo", ativadoEm = agora()))
    }

    fun concluir(id: String) = alteraPonto(id) { it.copy(status = "concluido", concluidoEm = agora()) }

    fun reabrir(id: String) = alteraPonto(id) { it.copy(status = "planejado", concluidoEm = null) }

    fun registrarMesa(id: String, entradas: List<MesaEntrada>) = alteraPonto(id) { atual ->
        val ts = agora()
        atual.copy(
            status = if (atual.status == "concluido") atual.status else "ativo",
            ativadoEm = atual.ativadoEm ?: ts,
            mesa = atual.mesa + entradas.map { it.copy(em = ts) }
        )
    }

    fun registrarAchado(id: String, achado: Achado) = alteraPonto(id) { it.copy(achados = it.achados + achado.copy(em = agora())) }

    fun esquecer(id: String) = alterar { it - id }

    private fun alteraPonto(id: String, f: (PontoEstado) -> PontoEstado) =
        alterar { pontos -> pontos + (id to f(pontos[id] ?: PontoEstado())) }

    private fun alterar(f: (Map<String, PontoEstado>) -> Map<String, PontoEstado>) = synchronized(lock) {
        estado = CampanhaEstado(f(estado.pontos))
        files.gravarSeMudou(ARQUIVO, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(estado) + "\n")
    }

    private fun agora() = System.currentTimeMillis()

    companion object { const val ARQUIVO = "_estado.json" }
}
