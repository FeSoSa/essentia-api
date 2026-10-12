package com.rpg.essentia.campanha

/**
 * Módulo Campanha: os pontos e capítulos moram em arquivos markdown na pasta
 * configurada em `essentia.campanha.dir`, não no Mongo. Este pacote não conhece
 * nada do Essentia; as ligações com Bestiário, itens e regiões ficam em `vinculos/`.
 */

/** Referência a algo de fora (mapa, inimigo, item): o id manda, o nome é para quem lê o arquivo. */
data class CampanhaRef(
    val id: String? = null,
    val nome: String? = null,
    val qtd: Int? = null
)

data class CampanhaCapitulo(
    val id: String = "",
    val titulo: String = "",
    val ordem: Int = 0,
    val resumo: String = "",
    val pasta: String = "",
    val hash: String? = null
)

data class CampanhaPonto(
    val id: String = "",
    val tipo: String = "narrativo",
    val titulo: String = "",
    val capitulo: String = "",
    val ordem: Int = 0,
    val mapa: CampanhaRef? = null,
    val x: Double? = null,
    val y: Double? = null,
    val revelado: Boolean = false,
    val ligadoA: List<String> = emptyList(),
    /** Todo campo do cabeçalho que não é base (inimigos, itens, gatilho...). Passa ida e volta sem perda. */
    val extras: Map<String, Any?> = emptyMap(),
    val corpo: String = "",
    val arquivo: String = "",
    /** Hash do arquivo lido; o painel devolve ao salvar para detectar edição feita por fora. */
    val hash: String? = null
)

/* ── Estado da sessão: só a API escreve, em _estado.json ─────────── */

data class MesaEntrada(
    val instanceId: String = "",
    val tipo: String = "inimigo",
    val refId: String? = null,
    val nome: String = "",
    val em: Long = 0
)

data class Achado(
    val itemId: String? = null,
    val nome: String = "",
    val playerId: String = "",
    val playerNome: String? = null,
    val qtd: Int = 1,
    val em: Long = 0
)

data class PontoEstado(
    val status: String = "planejado",
    val ativadoEm: Long? = null,
    val concluidoEm: Long? = null,
    val mesa: List<MesaEntrada> = emptyList(),
    val achados: List<Achado> = emptyList()
)

data class CampanhaEstado(val pontos: Map<String, PontoEstado> = emptyMap())

data class CampanhaSnapshot(
    val pasta: String,
    val capitulos: List<CampanhaCapitulo>,
    val pontos: List<CampanhaPonto>,
    val estado: Map<String, PontoEstado>,
    /** Avisos por ponto: nome que não existe no Bestiário, região apagada etc. */
    val avisos: Map<String, List<String>>,
    /** Arquivos que não deu para ler. */
    val erros: List<String>
)

/* ── Requests ───────────────────────────────────────────────────── */

data class CapituloRequest(val titulo: String = "", val ordem: Int? = null, val resumo: String = "")
data class PosicaoRequest(val mapa: CampanhaRef? = null, val x: Double? = null, val y: Double? = null)
data class MesaRequest(val entradas: List<MesaEntrada> = emptyList())
