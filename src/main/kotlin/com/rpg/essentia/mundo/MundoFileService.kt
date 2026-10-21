package com.rpg.essentia.mundo

import com.rpg.essentia.campanha.CampanhaFileService
import com.rpg.essentia.campanha.Frontmatter
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

/**
 * Dono dos arquivos da pasta do mundo. Sempre lê do disco (são poucos arquivos),
 * então o que for escrito pelo VS Code aparece no painel ao recarregar. Estrutura:
 *
 *   mundo/
 *     _LEIA-ME.md                       (arquivos com _ ficam de fora)
 *     Lugares/floresta-de-verdalia.md
 *     Facções/cla-ferok.md
 *     sobre-o-mundo.md                  (página sem categoria)
 */
@Service
class MundoFileService(@Value("\${essentia.mundo.dir:../mundo}") dir: String) {

    private val log = LoggerFactory.getLogger(javaClass)
    val raiz: Path = Paths.get(dir).toAbsolutePath().normalize()
    private val lock = Any()

    init {
        Files.createDirectories(raiz)
        val leia = raiz.resolve("_LEIA-ME.md")
        if (!Files.exists(leia)) gravaAtomico(leia, LEIA_ME)
        log.info("Mundo: {} páginas em {}", snapshot().paginas.size, raiz)
    }

    fun snapshot(): MundoSnapshot = synchronized(lock) {
        val paginas = LinkedHashMap<String, MundoPagina>()
        val categorias = mutableListOf<String>()
        val erros = mutableListOf<String>()

        fun ler(arq: Path, categoria: String) {
            try {
                val texto = Files.readString(arq)
                val doc = Frontmatter.parse(texto)
                var id = arq.fileName.toString().removeSuffix(".md")
                if (paginas.containsKey(id)) {
                    erros += "${relativo(arq)}: id \"$id\" repetido, usando \"$id-2\""
                    id = "$id-2"
                }
                paginas[id] = MundoPagina(
                    id = id,
                    titulo = doc.campos["titulo"]?.toString()?.takeIf { it.isNotBlank() } ?: id,
                    categoria = categoria,
                    corpo = doc.corpo,
                    arquivo = relativo(arq),
                    atualizadoEm = Files.getLastModifiedTime(arq).toMillis(),
                    hash = CampanhaFileService.hash(texto)
                )
            } catch (e: Exception) {
                erros += "${relativo(arq)}: ${e.message}"
            }
        }

        listar(raiz).forEach { item ->
            if (Files.isDirectory(item)) {
                val cat = item.fileName.toString()
                categorias += cat
                listar(item).filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }.forEach { ler(it, cat) }
            } else if (item.fileName.toString().endsWith(".md")) {
                ler(item, "")
            }
        }
        MundoSnapshot(raiz.toString(), categorias.sortedBy { it.lowercase() }, paginas.values.sortedWith(compareBy({ it.categoria }, { it.titulo.lowercase() })), erros)
    }

    fun criar(req: MundoPaginaRequest): MundoPagina = synchronized(lock) {
        val titulo = req.titulo.trim().ifBlank { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Dê um título à página") }
        val existentes = snapshot().paginas.map { it.id }.toSet()
        val base = CampanhaFileService.slug(titulo).ifBlank { "pagina" }
        var id = base
        var n = 2
        while (id in existentes) id = "$base-${n++}"
        gravaPagina(pastaDa(req.categoria).resolve("$id.md"), titulo, req.corpo)
        pagina(id)
    }

    /** Grava título, categoria e texto. Se `hash` vier e o arquivo tiver mudado por fora, recusa com 409. */
    fun atualizar(id: String, req: MundoPaginaRequest): MundoPagina = synchronized(lock) {
        val atual = pagina(id)
        val arqAtual = raiz.resolve(atual.arquivo)
        if (req.hash != null && req.hash != atual.hash)
            throw ResponseStatusException(HttpStatus.CONFLICT, "O arquivo ${atual.arquivo} mudou fora do painel")
        val titulo = req.titulo.trim().ifBlank { atual.titulo }
        val destino = pastaDa(req.categoria).resolve(arqAtual.fileName)
        if (destino != arqAtual && Files.exists(destino))
            throw ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma página com esse arquivo em \"${req.categoria}\"")
        gravaPagina(destino, titulo, req.corpo)
        if (destino != arqAtual) Files.deleteIfExists(arqAtual)
        pagina(id)
    }

    fun remover(id: String) = synchronized(lock) {
        Files.deleteIfExists(raiz.resolve(pagina(id).arquivo))
    }

    fun criarCategoria(nome: String) = synchronized(lock) {
        Files.createDirectories(pastaDa(nome.ifBlank { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Dê um nome à categoria") }))
    }

    fun removerCategoria(nome: String) = synchronized(lock) {
        val dir = pastaDa(nome)
        if (dir == raiz || !Files.isDirectory(dir)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria não encontrada")
        if (listar(dir).any { !it.fileName.toString().startsWith(".") })
            throw ResponseStatusException(HttpStatus.CONFLICT, "A categoria ainda tem páginas; mova ou apague antes")
        Files.delete(dir)
    }

    /* ── Auxiliares ─────────────────────────────────────────────── */

    private fun pagina(id: String): MundoPagina =
        snapshot().paginas.find { it.id == id } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Página não encontrada")

    /** Pasta da categoria. O nome vira pasta como está (acentos e espaços valem), só sem caracteres proibidos. */
    private fun pastaDa(categoria: String): Path {
        val nome = categoria.trim().replace(Regex("""[\\/:*?"<>|]"""), "").trim('.', ' ')
        if (nome.isBlank()) return raiz
        if (nome.startsWith("_")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria não pode começar com _")
        val dir = raiz.resolve(nome).normalize()
        if (dir.parent != raiz) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria inválida")
        return dir
    }

    private fun gravaPagina(arq: Path, titulo: String, corpo: String) =
        gravaAtomico(arq, Frontmatter.write(mapOf("titulo" to titulo), corpo))

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

    /** Itens da pasta, sem os que começam com _ ou . */
    private fun listar(dir: Path): List<Path> = Files.list(dir).use { s ->
        s.filter { val n = it.fileName.toString(); !n.startsWith("_") && !n.startsWith(".") }.sorted().toList()
    }

    private fun relativo(arq: Path) = raiz.relativize(arq).toString().replace('\\', '/')

    companion object {
        val LEIA_ME = """
            # Pasta do mundo

            Páginas livres de lore: lugares, facções, história, como as coisas funcionam.
            Cada página é um arquivo `.md`; cada subpasta é uma categoria (o nome da pasta aparece no painel).
            Arquivos e pastas que começam com `_` ficam de fora.

            ```markdown
            ---
            titulo: Floresta de Verdália
            ---
            Texto livre em markdown. Para ligar a outra página, escreva [[Clã Ferok]] com o título dela.
            ```

            O nome do arquivo é o id da página e não muda quando o título muda.
        """.trimIndent() + "\n"
    }
}
