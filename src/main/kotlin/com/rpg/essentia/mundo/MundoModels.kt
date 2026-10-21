package com.rpg.essentia.mundo

/**
 * Módulo Mundo: páginas livres de lore (lugares, facções, história) que não são
 * pontos de campanha. Moram em arquivos markdown na pasta `essentia.mundo.dir`;
 * cada subpasta é uma categoria. Só o mestre vê.
 */

data class MundoPagina(
    val id: String = "",
    val titulo: String = "",
    /** Nome da subpasta; vazio = solta na raiz. */
    val categoria: String = "",
    val corpo: String = "",
    val arquivo: String = "",
    val atualizadoEm: Long = 0,
    /** Hash do arquivo lido; o painel devolve ao salvar para detectar edição feita por fora. */
    val hash: String? = null
)

data class MundoSnapshot(
    val pasta: String,
    val categorias: List<String>,
    val paginas: List<MundoPagina>,
    /** Arquivos que não deu para ler. */
    val erros: List<String>
)

data class MundoPaginaRequest(
    val titulo: String = "",
    val categoria: String = "",
    val corpo: String = "",
    val hash: String? = null
)

data class MundoCategoriaRequest(val nome: String = "")
