package com.rpg.essentia.campanha

import com.rpg.essentia.campanha.vinculos.EssentiaCampanhaVinculos
import com.rpg.essentia.model.BossTemplate
import com.rpg.essentia.model.EnemyTemplate
import com.rpg.essentia.model.Region
import com.rpg.essentia.repository.AllyTemplateRepository
import com.rpg.essentia.repository.BossTemplateRepository
import com.rpg.essentia.repository.EnemyTemplateRepository
import com.rpg.essentia.repository.ItemCatalogRepository
import com.rpg.essentia.repository.RegionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path

class CampanhaTest {

    @TempDir lateinit var dir: Path

    private val arquivo = """
        ---
        id: teste-do-medo
        tipo: encontro
        titulo: O teste do medo
        ordem: 2
        mapa: Tyriade
        x: 0.42
        y: 0.64
        inimigos:
          - { nome: guerreiro ferok, qtd: 3 }
          - { nome: Batedor Ferok, qtd: 1 }
        terreno: Clareira
        ---
        Os Ferok não atacam de cara.

        **Se alguém recuar**, o chefe ri.
    """.trimIndent() + "\n"

    @Test
    fun `frontmatter preserva corpo e campos extras na ida e volta`() {
        val doc = Frontmatter.parse(arquivo)
        val p = Frontmatter.camposParaPonto(doc.campos, doc.corpo, "x", "")
        assertEquals("Clareira", p.extras["terreno"])
        assertEquals(CampanhaRef(nome = "Tyriade"), p.mapa)

        val de_novo = Frontmatter.parse(Frontmatter.write(Frontmatter.pontoParaCampos(p), p.corpo))
        assertEquals(doc.corpo, de_novo.corpo)
        assertEquals(p, Frontmatter.camposParaPonto(de_novo.campos, de_novo.corpo, "x", "")
            .copy(mapa = p.mapa, extras = p.extras))
        assertEquals("Clareira", de_novo.campos["terreno"])
    }

    @Test
    fun `arquivo sem cabecalho vira so corpo`() {
        val doc = Frontmatter.parse("só texto\n")
        assertTrue(doc.campos.isEmpty())
        assertEquals("só texto\n", doc.corpo)
    }

    @Test
    fun `file service le grava e recusa edicao com hash velho`() {
        val files = CampanhaFileService(dir.toString())
        val cap = files.criarCapitulo(CapituloRequest(titulo = "Verdália"))
        assertEquals("cap01-verdalia", cap.id)

        val p = files.criarPonto(CampanhaPonto(titulo = "A trilha de ossos", capitulo = cap.id, corpo = "Texto.\n"))
        assertEquals("a-trilha-de-ossos", p.id)
        assertTrue(Files.exists(dir.resolve("cap01-verdalia/01-a-trilha-de-ossos.md")))

        // Alguém (o Claude) edita o arquivo por fora
        val arq = dir.resolve(p.arquivo)
        Files.writeString(arq, Files.readString(arq).replace("Texto.", "Texto do Claude."))
        assertTrue(files.rescan())

        val erro = assertThrows<ResponseStatusException> { files.atualizarPonto(p.id, p.copy(titulo = "Outro")) }
        assertEquals(409, erro.statusCode.value())

        val atual = files.pontos().single()
        assertEquals("Texto do Claude.\n", atual.corpo)
        files.atualizarPonto(p.id, atual.copy(titulo = "Outro"))
        assertEquals("Outro", files.pontos().single().titulo)
    }

    @Test
    fun `mover ponto relê o disco e mantém o resto do arquivo`() {
        val files = CampanhaFileService(dir.toString())
        Files.writeString(dir.resolve("teste.md"), arquivo)
        files.rescan()
        files.moverPonto("teste-do-medo", PosicaoRequest(CampanhaRef(id = "r1", nome = "Tyriade"), 0.1, 0.2))
        val p = files.pontos().single()
        assertEquals(0.1, p.x)
        assertEquals("r1", p.mapa?.id)
        assertEquals("Clareira", p.extras["terreno"])
        assertTrue(p.corpo.startsWith("Os Ferok"))
    }

    @Test
    fun `sem ordem no cabecalho vale o prefixo do arquivo`() {
        val files = CampanhaFileService(dir.toString())
        Files.writeString(dir.resolve("03-bau.md"), "---\ntitulo: Baú\n---\n")
        files.rescan()
        assertEquals(3, files.pontos().single().ordem)
        assertEquals("bau", files.pontos().single().id)
    }

    @Test
    fun `vinculos resolvem por nome sem acento e avisam o que falta`() {
        val enemies = mock(EnemyTemplateRepository::class.java)
        val bosses = mock(BossTemplateRepository::class.java)
        val regions = mock(RegionRepository::class.java)
        `when`(enemies.findAll()).thenReturn(listOf(EnemyTemplate(id = "e1", name = "Guerreiro Ferok")))
        `when`(bosses.findAll()).thenReturn(listOf(BossTemplate(id = "b1", name = "Chefe"), BossTemplate(id = "b2", name = "Chefe")))
        `when`(regions.findAll()).thenReturn(listOf(Region(id = "r1", name = "Tyriade")))
        val vinculos = EssentiaCampanhaVinculos(enemies, bosses, mock(AllyTemplateRepository::class.java),
            mock(ItemCatalogRepository::class.java), regions)

        val doc = Frontmatter.parse(arquivo)
        val p = Frontmatter.camposParaPonto(doc.campos, doc.corpo, "x", "")
            .let { it.copy(extras = it.extras + ("chefes" to listOf(mapOf("nome" to "Chefe")))) }
        val r = vinculos.resolver(listOf(p)).getValue(p.id)

        assertEquals(CampanhaRef(id = "r1", nome = "Tyriade"), r.ponto.mapa)
        val inimigos = r.ponto.extras["inimigos"] as List<*>
        assertEquals(mapOf("id" to "e1", "nome" to "Guerreiro Ferok", "qtd" to 3), inimigos[0])
        assertEquals(2, r.avisos.size)
        assertTrue(r.avisos.any { it.contains("Batedor Ferok") && it.contains("não existe") })
        assertTrue(r.avisos.any { it.contains("ambíguo") })

        val semRegiao = vinculos.resolver(listOf(p.copy(mapa = CampanhaRef(nome = "Lugar Nenhum")))).getValue(p.id)
        assertNull(semRegiao.ponto.mapa)
    }
}
