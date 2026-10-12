package com.rpg.essentia.campanha

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Dono dos arquivos da pasta da campanha. Mantém um cache do que leu (com hash de
 * cada arquivo) e grava de forma atômica. Estrutura:
 *
 *   campanha/
 *     _LEIA-ME.md, _referencias.md, _estado.json   (arquivos com _ não são pontos)
 *     cap01-verdalia/_capitulo.md
 *     cap01-verdalia/01-trilha-de-ossos.md
 *     solto.md                                      (ponto sem capítulo)
 */
@Service
class CampanhaFileService(@Value("\${essentia.campanha.dir:../campanha}") dir: String) {

    private val log = LoggerFactory.getLogger(javaClass)
    val raiz: Path = Paths.get(dir).toAbsolutePath().normalize()
    private val lock = Any()

    private var capitulos: Map<String, CampanhaCapitulo> = emptyMap()
    private var pontos: Map<String, CampanhaPonto> = emptyMap()
    private var erros: List<String> = emptyList()

    init {
        Files.createDirectories(raiz)
        val leia = raiz.resolve("_LEIA-ME.md")
        if (!Files.exists(leia)) gravaAtomico(leia, LEIA_ME)
        rescan()
        log.info("Campanha: {} capítulos e {} pontos em {}", capitulos.size, pontos.size, raiz)
    }

    fun capitulos(): List<CampanhaCapitulo> = synchronized(lock) { capitulos.values.sortedWith(compareBy({ it.ordem }, { it.id })) }
    fun pontos(): List<CampanhaPonto> = synchronized(lock) { pontos.values.sortedWith(compareBy({ it.capitulo }, { it.ordem }, { it.id })) }
    fun erros(): List<String> = synchronized(lock) { erros }

    /** Relê a pasta inteira. Devolve true se algo mudou em relação ao cache. */
    fun rescan(): Boolean = synchronized(lock) {
        val novosCaps = LinkedHashMap<String, CampanhaCapitulo>()
        val novosPontos = LinkedHashMap<String, CampanhaPonto>()
        val novosErros = mutableListOf<String>()

        fun lerPonto(arq: Path, capitulo: String) {
            try {
                val texto = Files.readString(arq)
                val doc = Frontmatter.parse(texto)
                var p = Frontmatter.camposParaPonto(doc.campos, doc.corpo, idDoArquivo(arq), capitulo, ordemDoArquivo(arq))
                    .copy(arquivo = relativo(arq), hash = hash(texto))
                if (novosPontos.containsKey(p.id)) {
                    novosErros += "${relativo(arq)}: id \"${p.id}\" repetido, usando \"${p.id}-2\""
                    p = p.copy(id = p.id + "-2")
                }
                novosPontos[p.id] = p
            } catch (e: Exception) {
                novosErros += "${relativo(arq)}: ${e.message}"
            }
        }

        Files.list(raiz).use { s -> s.sorted().toList() }.forEach { item ->
            val nome = item.fileName.toString()
            if (nome.startsWith(".") || nome.startsWith("_")) return@forEach
            if (Files.isDirectory(item)) {
                val capArq = item.resolve("_capitulo.md")
                var cap = CampanhaCapitulo(id = nome, titulo = nome, pasta = nome)
                if (Files.exists(capArq)) {
                    try {
                        val texto = Files.readString(capArq)
                        val doc = Frontmatter.parse(texto)
                        cap = cap.copy(
                            id = (doc.campos["id"] as? String)?.takeIf { it.isNotBlank() } ?: nome,
                            titulo = doc.campos["titulo"]?.toString() ?: nome,
                            ordem = (doc.campos["ordem"] as? Number)?.toInt() ?: 0,
                            resumo = doc.corpo,
                            hash = hash(texto)
                        )
                    } catch (e: Exception) {
                        novosErros += "${relativo(capArq)}: ${e.message}"
                    }
                }
                novosCaps[cap.id] = cap
                Files.list(item).use { s -> s.sorted().toList() }.forEach { f ->
                    val n = f.fileName.toString()
                    if (Files.isRegularFile(f) && n.endsWith(".md") && !n.startsWith("_") && !n.startsWith(".")) lerPonto(f, cap.id)
                }
            } else if (nome.endsWith(".md")) {
                lerPonto(item, "")
            }
        }

        val mudou = novosCaps != capitulos || novosPontos != pontos || novosErros != erros
        capitulos = novosCaps
        pontos = novosPontos
        erros = novosErros
        mudou
    }

    /* ── Pontos ─────────────────────────────────────────────────── */

    fun criarPonto(entrada: CampanhaPonto): CampanhaPonto = synchronized(lock) {
        val pasta = pastaDoCapitulo(entrada.capitulo)
        val base = slug(entrada.id.ifBlank { entrada.titulo }).ifBlank { "ponto" }
        var id = base
        var n = 2
        while (pontos.containsKey(id)) id = "$base-${n++}"
        val ordem = if (entrada.ordem > 0) entrada.ordem
            else (pontos.values.filter { it.capitulo == entrada.capitulo }.maxOfOrNull { it.ordem } ?: 0) + 1
        val p = entrada.copy(id = id, ordem = ordem)
        val arq = arquivoLivre(pasta, ordem, id)
        gravaPonto(arq, p)
        rescan()
        pontos.getValue(id)
    }

    /** Grava o ponto inteiro. Se `hash` vier e o arquivo tiver mudado por fora, recusa com 409. */
    fun atualizarPonto(id: String, entrada: CampanhaPonto): CampanhaPonto = synchronized(lock) {
        val atual = pontos[id] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Ponto não encontrado")
        val arqAtual = raiz.resolve(atual.arquivo)
        val hashDisco = if (Files.exists(arqAtual)) hash(Files.readString(arqAtual)) else null
        if (entrada.hash != null && hashDisco != null && entrada.hash != hashDisco)
            throw ResponseStatusException(HttpStatus.CONFLICT, "O arquivo ${atual.arquivo} mudou fora do painel")
        val p = entrada.copy(id = id)
        val pasta = pastaDoCapitulo(p.capitulo)
        val destino = if (p.capitulo != atual.capitulo || p.ordem != atual.ordem) arquivoLivre(pasta, p.ordem, id, arqAtual) else arqAtual
        gravaPonto(destino, p)
        if (destino != arqAtual) Files.deleteIfExists(arqAtual)
        rescan()
        pontos.getValue(id)
    }

    /** Muda só mapa e posição, relendo o arquivo do disco para não perder edição feita por fora. */
    fun moverPonto(id: String, req: PosicaoRequest): CampanhaPonto = synchronized(lock) {
        val atual = pontos[id] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Ponto não encontrado")
        listOfNotNull(req.x, req.y).forEach {
            if (it < 0.0 || it > 1.0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "x/y precisam estar entre 0 e 1")
        }
        val arq = raiz.resolve(atual.arquivo)
        val doc = Frontmatter.parse(Files.readString(arq))
        val campos = LinkedHashMap(doc.campos)
        if (req.mapa == null) { campos.remove("mapa"); campos.remove("x"); campos.remove("y") }
        else {
            campos["mapa"] = Frontmatter.refParaMapa(req.mapa)
            req.x?.let { campos["x"] = Math.round(it * 10000) / 10000.0 }
            req.y?.let { campos["y"] = Math.round(it * 10000) / 10000.0 }
        }
        gravaAtomico(arq, Frontmatter.write(campos, doc.corpo))
        rescan()
        pontos.getValue(id)
    }

    fun removerPonto(id: String) = synchronized(lock) {
        val atual = pontos[id] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Ponto não encontrado")
        Files.deleteIfExists(raiz.resolve(atual.arquivo))
        rescan()
    }

    /* ── Capítulos ──────────────────────────────────────────────── */

    fun criarCapitulo(req: CapituloRequest): CampanhaCapitulo = synchronized(lock) {
        val ordem = req.ordem ?: ((capitulos.values.maxOfOrNull { it.ordem } ?: 0) + 1)
        var pasta = "cap%02d-%s".format(ordem, slug(req.titulo).ifBlank { "capitulo" })
        var n = 2
        while (Files.exists(raiz.resolve(pasta))) pasta = "cap%02d-%s-%d".format(ordem, slug(req.titulo), n++)
        val dir = Files.createDirectories(raiz.resolve(pasta))
        gravaAtomico(dir.resolve("_capitulo.md"), Frontmatter.write(mapOf("id" to pasta, "titulo" to req.titulo, "ordem" to ordem), req.resumo))
        rescan()
        capitulos.getValue(pasta)
    }

    fun atualizarCapitulo(id: String, req: CapituloRequest): CampanhaCapitulo = synchronized(lock) {
        val cap = capitulos[id] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Capítulo não encontrado")
        val arq = raiz.resolve(cap.pasta).resolve("_capitulo.md")
        gravaAtomico(arq, Frontmatter.write(mapOf("id" to cap.id, "titulo" to req.titulo, "ordem" to (req.ordem ?: cap.ordem)), req.resumo))
        rescan()
        capitulos.getValue(id)
    }

    fun removerCapitulo(id: String) = synchronized(lock) {
        val cap = capitulos[id] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Capítulo não encontrado")
        if (pontos.values.any { it.capitulo == id })
            throw ResponseStatusException(HttpStatus.CONFLICT, "O capítulo ainda tem pontos; mova ou apague antes")
        val dir = raiz.resolve(cap.pasta)
        Files.deleteIfExists(dir.resolve("_capitulo.md"))
        Files.list(dir).use { s -> s.toList() }.forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(dir)
        rescan()
    }

    /* ── Arquivos gerados (_referencias.md, _estado.json) ───────── */

    fun lerArquivo(nome: String): String? = raiz.resolve(nome).takeIf { Files.exists(it) }?.let { Files.readString(it) }

    /** Grava só se o conteúdo mudou, para não disparar o watcher à toa. */
    fun gravarSeMudou(nome: String, conteudo: String) {
        val arq = raiz.resolve(nome)
        if (Files.exists(arq) && Files.readString(arq) == conteudo) return
        gravaAtomico(arq, conteudo)
    }

    /* ── Auxiliares ─────────────────────────────────────────────── */

    private fun gravaPonto(arq: Path, p: CampanhaPonto) =
        gravaAtomico(arq, Frontmatter.write(Frontmatter.pontoParaCampos(p), p.corpo))

    private fun gravaAtomico(arq: Path, conteudo: String) {
        Files.createDirectories(arq.parent)
        val tmp = arq.resolveSibling(".${arq.fileName}.tmp")
        Files.writeString(tmp, conteudo)
        try {
            Files.move(tmp, arq, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, arq, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun pastaDoCapitulo(capitulo: String): Path {
        if (capitulo.isBlank()) return raiz
        val cap = capitulos[capitulo] ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Capítulo \"$capitulo\" não existe")
        return raiz.resolve(cap.pasta)
    }

    private fun arquivoLivre(pasta: Path, ordem: Int, id: String, atual: Path? = null): Path {
        var arq = pasta.resolve("%02d-%s.md".format(ordem, id))
        var n = 2
        while (Files.exists(arq) && arq != atual) arq = pasta.resolve("%02d-%s-%d.md".format(ordem, id, n++))
        return arq
    }

    private fun idDoArquivo(arq: Path) = arq.fileName.toString().removeSuffix(".md").replace(Regex("^\\d+-"), "")
    /** "03-bau-do-cla.md" vale ordem 3 quando o cabeçalho não diz. */
    private fun ordemDoArquivo(arq: Path) = Regex("^(\\d+)-").find(arq.fileName.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    private fun relativo(arq: Path) = raiz.relativize(arq).toString().replace('\\', '/')

    companion object {
        fun hash(texto: String): String =
            MessageDigest.getInstance("SHA-256").digest(texto.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        fun slug(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)

        val LEIA_ME = """
            # Pasta da campanha

            Cada ponto da campanha é um arquivo `.md`. O cabeçalho entre `---` tem os campos; o resto é o texto livre.
            Subpastas são capítulos (`_capitulo.md` guarda título, ordem e resumo). Arquivos que começam com `_` não são pontos.

            ```yaml
            ---
            id: teste-do-medo
            tipo: encontro          # narrativo | encontro | item | npc | segredo
            titulo: O teste do medo
            capitulo: cap01-verdalia
            ordem: 2
            mapa: { id: essentia-root, nome: Essentia }   # ou o id/nome de uma região; sem mapa = só na lista
            x: 0.42                 # posição de 0 a 1 na imagem daquele mapa
            y: 0.64
            revelado: false
            ligado_a: [trilha-de-ossos]
            inimigos:               # nome basta; o painel completa o id ao salvar
              - { nome: Guerreiro Ferok, qtd: 3 }
            chefes:
              - { nome: Chefe Ferok, qtd: 1 }
            itens:
              - { nome: Amuleto de Osso, qtd: 1 }
            ---
            ```

            Campos extras por tipo: `gatilho` (narrativo), `dificuldade` e `terreno` (encontro), `condicao` (item),
            `npc`, `atitude` e `revela` (npc). Qualquer outro campo é guardado como está.

            - `_referencias.md` lista os nomes e ids que existem no Bestiário, no catálogo de itens e nas regiões. É gerado pela API.
            - `_estado.json` guarda o que aconteceu na sessão (status, itens achados, quem foi para a Mesa). Só a API escreve nele.
            - `_grafo.json` guarda onde cada nó foi posicionado na visão em grafo do painel. Só a API escreve nele.
            - Comentários `#` dentro do cabeçalho somem quando o painel salva o ponto.
        """.trimIndent() + "\n"
    }
}
