package com.rpg.essentia.campanha.vinculos

import com.rpg.essentia.campanha.CampanhaPonto
import com.rpg.essentia.campanha.CampanhaRef
import com.rpg.essentia.campanha.Frontmatter
import com.rpg.essentia.repository.AllyTemplateRepository
import com.rpg.essentia.repository.BossTemplateRepository
import com.rpg.essentia.repository.EnemyTemplateRepository
import com.rpg.essentia.repository.ItemCatalogRepository
import com.rpg.essentia.repository.RegionRepository
import org.springframework.stereotype.Component
import java.text.Normalizer

/**
 * Liga a campanha ao Essentia: mapas são o mapa raiz ou regiões, e os campos
 * `inimigos`, `chefes`, `aliados` e `itens` apontam para o Bestiário e o catálogo.
 *
 * Regras: o id manda; sem id (ou com id que não existe mais) procura pelo nome,
 * ignorando maiúsculas e acentos. Nome repetido ou inexistente vira aviso.
 */
@Component
class EssentiaCampanhaVinculos(
    private val enemies: EnemyTemplateRepository,
    private val bosses: BossTemplateRepository,
    private val allies: AllyTemplateRepository,
    private val items: ItemCatalogRepository,
    private val regions: RegionRepository
) : CampanhaVinculos {

    private data class Entrada(val id: String, val nome: String)

    private class Catalogo(val rotulo: String, val onde: String, entradas: List<Entrada>) {
        val porId = entradas.associateBy { it.id }
        val porNome = entradas.groupBy { normal(it.nome) }
    }

    private fun catalogos(): Map<String, Catalogo> = mapOf(
        "inimigos" to Catalogo("Inimigo", "no Bestiário", enemies.findAll().map { Entrada(it.id, it.name) }),
        "chefes" to Catalogo("Chefe", "no Bestiário", bosses.findAll().map { Entrada(it.id, it.name) }),
        "aliados" to Catalogo("Aliado", "no Bestiário", allies.findAll().map { Entrada(it.id, it.name) }),
        "itens" to Catalogo("Item", "no catálogo", items.findAll().map { Entrada(it.id, it.name) }),
        "mapa" to Catalogo("Mapa", "nas regiões", listOf(Entrada(ROOT_MAP_ID, "Essentia")) + regions.findAll().map { Entrada(it.id, it.name) })
    )

    override fun resolver(pontos: List<CampanhaPonto>): Map<String, Resolucao> {
        val cats = catalogos()
        return pontos.associate { p ->
            val avisos = mutableListOf<String>()
            val mapa = p.mapa?.let { ref ->
                resolverRef(ref, cats.getValue("mapa"), avisos, quandoFalta = "o ponto ficou sem mapa")
            }
            val extras = LinkedHashMap(p.extras)
            for (campo in listOf("inimigos", "chefes", "aliados", "itens")) {
                val lista = p.extras[campo] as? List<*> ?: continue
                extras[campo] = lista.mapNotNull { Frontmatter.refDe(it) }.map { ref ->
                    val r = resolverRef(ref, cats.getValue(campo), avisos) ?: ref
                    Frontmatter.refParaMapa(r.copy(qtd = ref.qtd ?: 1))
                }
            }
            p.id to Resolucao(p.copy(mapa = mapa, extras = extras), avisos)
        }
    }

    private fun resolverRef(ref: CampanhaRef, cat: Catalogo, avisos: MutableList<String>, quandoFalta: String? = null): CampanhaRef? {
        ref.id?.let { id ->
            cat.porId[id]?.let { return ref.copy(id = it.id, nome = it.nome) }
        }
        // Sem id, ou id que não existe mais: o nome (ou o próprio id escrito à mão) decide.
        val chave = ref.nome ?: ref.id
        val achados = chave?.let { cat.porId[it]?.let(::listOf) ?: cat.porNome[normal(it)] }.orEmpty()
        return when {
            achados.size == 1 -> ref.copy(id = achados[0].id, nome = achados[0].nome)
            achados.size > 1 -> {
                avisos += "${cat.rotulo} \"$chave\" é ambíguo: há ${achados.size} com esse nome ${cat.onde}"
                ref
            }
            else -> {
                avisos += "${cat.rotulo} \"$chave\" não existe ${cat.onde}" + (quandoFalta?.let { ", $it" } ?: "")
                if (quandoFalta != null) null else ref
            }
        }
    }

    override fun gerarReferencias(): String {
        val cats = catalogos()
        val sb = StringBuilder("# Referências\n\nGerado pela API a partir do banco. Use estes nomes (ou ids) nos pontos da campanha.\n")
        listOf("mapa" to "Mapas (mapa raiz e regiões)", "inimigos" to "Inimigos", "chefes" to "Chefes", "aliados" to "Aliados", "itens" to "Itens")
            .forEach { (campo, titulo) ->
                sb.append("\n## $titulo\n\n")
                val lista = cats.getValue(campo).porId.values.sortedBy { normal(it.nome) }
                if (lista.isEmpty()) sb.append("_nenhum_\n")
                lista.forEach { sb.append("- ${it.nome} `${it.id}`\n") }
            }
        return sb.toString()
    }

    companion object {
        const val ROOT_MAP_ID = "essentia-root"
        fun normal(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim().lowercase()
    }
}
