package com.rpg.essentia.config

import tools.jackson.databind.ObjectMapper
import com.rpg.essentia.service.GameStateService
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component

/**
 * Aplica o buff pós Sessão 0 a partir de migration/buff-sessao0.json (gerado junto com buff-diff.md).
 * Só roda com essentia.migration.buff=apply, e só uma vez (registro em game_state.migracoes).
 *   essentia.migration.buff.equilibrio=true  aplica também as propostas de equilíbrio
 *   essentia.migration.buff.opcionais=true   aplica também as correções marcadas como opcionais
 */
@Component
class BuffMigrationRunner(
    private val mongo: MongoTemplate,
    private val gameStateService: GameStateService,
    private val objectMapper: ObjectMapper,
    @Value("\${essentia.migration.buff:off}") private val modo: String,
    @Value("\${essentia.migration.buff.equilibrio:false}") private val comEquilibrio: Boolean,
    @Value("\${essentia.migration.buff.opcionais:false}") private val comOpcionais: Boolean
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object { const val NOME = "buff-sessao0" }

    @Suppress("UNCHECKED_CAST")
    override fun run(args: ApplicationArguments) {
        if (modo != "apply") return
        val state = gameStateService.getOrCreate()
        if (NOME in state.migracoes) {
            log.warn("Migração $NOME já aplicada; nada a fazer")
            return
        }
        val dados = ClassPathResource("migration/$NOME.json").inputStream.use {
            objectMapper.readValue(it, Map::class.java) as Map<String, Any?>
        }
        val skills = dados["skills"] as List<Map<String, Any?>>
        val items = dados["items"] as List<Map<String, Any?>>
        val enemies = dados["enemyTemplates"] as List<Map<String, Any?>>
        val bosses = dados["bossTemplates"] as List<Map<String, Any?>>
        val pskills = dados["playerSkills"] as List<Map<String, Any?>>

        // Skills: caminho de maestria, +25% (ou equilíbrio) e correções de atributo
        var nSkills = 0
        for (s in skills) {
            // Maestria por skill deixou de existir (agora é por grupo, migração da revisão): só limpa os campos
            val u = Update().unset("caminhoMaestria").unset("maestriaOverride")
            if (s.containsKey("damage") && s["damage"] == null) u.set("damage", null)
            val base25 = (s["base25"] as Number?)?.toInt()
            if (base25 != null) {
                val usaEquilibrio = comEquilibrio && s["baseEquilibrio"] != null
                val base = if (usaEquilibrio) (s["baseEquilibrio"] as Number).toInt() else base25
                val formula = if (usaEquilibrio) s["formulaEquilibrio"] else s["formula25"]
                u.set("damage.baseFixed", base).set("damage.formula", formula)
                s["atributo"]?.let { u.set("damage.atributo", it) }
            }
            mongo.updateFirst(Query(Criteria.where("_id").`is`(id(s["id"]))), u, "skills")
            nSkills++
        }

        // Catálogo de itens: margemCritico substitui critThreshold
        mongo.updateMulti(Query(Criteria.where("critThreshold").exists(true)), Update().rename("critThreshold", "margemCritico"), "item_catalog")
        val porNome = mutableMapOf<String, Map<String, Any?>>()
        for (it in items) {
            if (it["opcional"] == true && !comOpcionais) continue
            val u = Update()
            for (campo in listOf("margemCritico", "negaCritico", "damageAttribute", "rarity")) it[campo]?.let { v -> u.set(campo, v) }
            mongo.updateFirst(Query(Criteria.where("_id").`is`(it["id"])), u, "item_catalog")
            porNome[it["name"] as String] = it
        }
        // Mesmos campos nas cópias dos jogadores (inventário e equipamento)
        mongo.getCollection("players").find().forEach { p ->
            var mudou = false
            fun ajusta(doc: Document?, campos: List<String>) {
                val fix = doc?.getString("name")?.let { porNome[it] } ?: return
                campos.forEach { c -> fix[c]?.let { v -> doc[c] = v; mudou = true } }
            }
            val eq = p.get("equipment", Document::class.java)
            ajusta(eq?.get("mainHand", Document::class.java), listOf("margemCritico", "damageAttribute", "rarity"))
            ajusta(eq?.get("offHand", Document::class.java), listOf("margemCritico", "damageAttribute", "rarity"))
            ajusta(eq?.get("armor", Document::class.java), listOf("negaCritico", "rarity"))
            (p["items"] as? List<*>)?.filterIsInstance<Document>()?.forEach {
                ajusta(it, listOf("margemCritico", "negaCritico", "damageAttribute", "rarity"))
            }
            if (mudou) mongo.getCollection("players").replaceOne(Document("_id", p["_id"]), p)
        }

        // Inimigos: DEF, acerto, ES, custo de ES por ataque, XP x1,5 e equilíbrio 0 corrigido
        for (e in enemies) {
            val doc = mongo.findById(e["id"]!!, Document::class.java, "enemy_templates") ?: continue
            doc["defesa"] = e["defesa"]; doc["modAcerto"] = e["modAcerto"]; doc["esMax"] = e["esMax"]; doc["xp"] = e["xp"]
            ajustaAtaques(doc["attacks"] as? List<*>, e["custos"] as List<*>, e["equilibrioZeroFix"] as List<*>)
            mongo.getCollection("enemy_templates").replaceOne(Document("_id", doc["_id"]), doc)
        }
        for (b in bosses) {
            val doc = mongo.findById(b["id"]!!, Document::class.java, "boss_templates") ?: continue
            doc["xp"] = b["xp"]
            val fases = b["fases"] as List<Map<String, Any?>>
            (doc["phases"] as? List<*>)?.filterIsInstance<Document>()?.forEachIndexed { i, fase ->
                val f = fases.getOrNull(i) ?: return@forEachIndexed
                fase["defesa"] = f["defesa"]; fase["modAcerto"] = f["modAcerto"]; fase["esMax"] = f["esMax"]
                ajustaAtaques(fase["attacks"] as? List<*>, f["custos"] as List<*>, emptyList<Any>())
            }
            mongo.getCollection("boss_templates").replaceOne(Document("_id", doc["_id"]), doc)
        }

        // Maestria: usos mantidos, escolhas antigas descartadas; registros snake_case convertidos
        for (ps in pskills) {
            val nova = Document(mapOf("usos" to (ps["usos"] as Number).toInt(), "ultimoCombateUso" to null, "usosNoCombate" to 0, "historico" to emptyList<Any>()))
            val u = Update().set("maestria", nova)
            if (ps["legado"] == true) {
                val doc = mongo.findById(id(ps["id"]), Document::class.java, "player_skills")
                if (doc != null) {
                    u.set("playerId", doc["player_id"]).set("skillId", doc["skill_id"]).set("slotId", doc["slot_id"])
                        .unset("player_id").unset("skill_id").unset("slot_id")
                }
            }
            mongo.updateFirst(Query(Criteria.where("_id").`is`(id(ps["id"]))), u, "player_skills")
        }

        gameStateService.save(gameStateService.getOrCreate().let { it.copy(migracoes = it.migracoes + NOME) })
        log.warn("Migração $NOME aplicada: $nSkills skills, ${items.size} itens, ${enemies.size} inimigos, ${bosses.size} bosses, ${pskills.size} maestrias (equilíbrio=$comEquilibrio, opcionais=$comOpcionais)")
    }

    private fun ajustaAtaques(ataques: List<*>?, custos: List<*>, zeroFix: List<*>) {
        ataques?.filterIsInstance<Document>()?.forEachIndexed { i, a ->
            a["custoEs"] = (custos.getOrNull(i) as Number?)?.toInt() ?: 0
            if (zeroFix.any { (it as Number).toInt() == i }) {
                a["equilibrio"] = 7
                (a["damage"] as? String)?.let { d -> a["damage"] = d.replace(Regex("/0\\b"), "/7") }
            }
        }
    }

    /** Skills usam ObjectId; o resto usa UUID string. */
    private fun id(raw: Any?): Any = (raw as String).let { if (ObjectId.isValid(it)) ObjectId(it) else it }
}
