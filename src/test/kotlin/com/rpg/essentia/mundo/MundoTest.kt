package com.rpg.essentia.mundo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path

class MundoTest {

    @TempDir lateinit var dir: Path

    private fun servico() = MundoFileService(dir.toString())

    @Test
    fun `cria pagina na pasta da categoria com o nome legivel`() {
        val s = servico()
        val p = s.criar(MundoPaginaRequest(titulo = "Floresta de Verdália", categoria = "Lugares", corpo = "Mata densa.\n"))
        assertEquals("floresta-de-verdalia", p.id)
        assertEquals("Lugares/floresta-de-verdalia.md", p.arquivo)
        assertEquals("Mata densa.\n", p.corpo)
        assertTrue(Files.readString(dir.resolve(p.arquivo)).startsWith("---\ntitulo: Floresta de Verdália\n---\n"))
        assertEquals(listOf("Lugares"), s.snapshot().categorias)
    }

    @Test
    fun `mudar categoria move o arquivo e o id continua o mesmo`() {
        val s = servico()
        val p = s.criar(MundoPaginaRequest(titulo = "Clã Ferok", corpo = "x"))
        val movida = s.atualizar(p.id, MundoPaginaRequest(titulo = "Clã Ferok (velho)", categoria = "Facções", corpo = "y", hash = p.hash))
        assertEquals(p.id, movida.id)
        assertEquals("Facções/cla-ferok.md", movida.arquivo)
        assertEquals("Clã Ferok (velho)", movida.titulo)
        assertFalse(Files.exists(dir.resolve("cla-ferok.md")))
    }

    @Test
    fun `edicao feita por fora do painel da conflito`() {
        val s = servico()
        val p = s.criar(MundoPaginaRequest(titulo = "Moedas", corpo = "a"))
        Files.writeString(dir.resolve(p.arquivo), "---\ntitulo: Moedas\n---\neditado no VS Code")
        val e = assertThrows<ResponseStatusException> { s.atualizar(p.id, MundoPaginaRequest(titulo = "Moedas", corpo = "b", hash = p.hash)) }
        assertEquals(409, e.statusCode.value())
    }

    @Test
    fun `categoria nao sai da pasta e so apaga vazia`() {
        val s = servico()
        // Barras e pontos somem: "../fora" vira a categoria "fora", dentro da pasta
        assertEquals("fora/x.md", s.criar(MundoPaginaRequest(titulo = "x", categoria = "../fora")).arquivo)
        assertEquals(400, assertThrows<ResponseStatusException> { s.criarCategoria("_oculta") }.statusCode.value())
        s.criar(MundoPaginaRequest(titulo = "Deuses", categoria = "Religião"))
        assertEquals(409, assertThrows<ResponseStatusException> { s.removerCategoria("Religião") }.statusCode.value())
        s.criarCategoria("Vazia")
        s.removerCategoria("Vazia")
        assertEquals(listOf("fora", "Religião"), s.snapshot().categorias)
    }

    @Test
    fun `arquivo sem cabecalho usa o nome como titulo e o LEIA-ME fica de fora`() {
        val s = servico()
        Files.writeString(dir.resolve("anotacoes.md"), "só texto")
        val paginas = s.snapshot().paginas
        assertEquals(listOf("anotacoes"), paginas.map { it.id })
        assertEquals("anotacoes", paginas[0].titulo)
        assertEquals("só texto", paginas[0].corpo)
    }
}
