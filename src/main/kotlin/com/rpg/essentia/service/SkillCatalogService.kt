package com.rpg.essentia.service

import com.rpg.essentia.model.Skill
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class SkillCatalogService(
    private val skillRepository: SkillRepository,
    private val playerRepository: PlayerRepository,
    private val essenciaRepository: EssenciaRepository,
    private val attributeService: AttributeService,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {

    fun list(): List<Skill> = skillRepository.findAll()

    fun create(skill: Skill): Skill {
        validate(skill)
        return skillRepository.save(normalizar(skill))
    }

    fun update(id: String, skill: Skill): Skill {
        validate(skill)
        val saved = skillRepository.save(normalizar(skill.copy(id = id)))
        val allEssencias = essenciaRepository.findAll()
        val allSkills = skillRepository.findAll()
        playerRepository.findAll().forEach { player ->
            val synced = attributeService.syncPassiveSelfEffects(player, allSkills)
            val effective = attributeService.computeEffectiveAttributes(synced, allEssencias)
            val updated = attributeService.recalculateVitals(synced, effective)
            val saved2 = playerRepository.save(updated)
            broadcaster.broadcastPlayer(saved2)
        }
        return saved
    }

    fun delete(id: String) {
        if (!skillRepository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Skill não encontrada: $id")
        skillRepository.deleteById(id)
    }

    private fun validate(skill: Skill) {
        if (skill.damageSource == "weapon" && skill.weaponSlot !in listOf("mainHand", "offHand"))
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "weaponSlot deve ser 'mainHand' ou 'offHand' quando damageSource é 'weapon'")
        if (skill.tier == null && skill.usaTier())
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill precisa de tier (1 a 4)")
        if (skill.tier != null && skill.tier !in 1..4)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tier deve ser de 1 a 4")
        if (skill.type == "weapon" && skill.grupoMaestria != null && skill.grupoMaestria.lowercase() !in skill.categoriasArma())
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "O grupo de maestria precisa ser uma das categorias de arma da skill")
        TierRules.erroCusto(skill, gameStateService.combateConfig())?.let {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, it)
        }
        DegrauRules.erroTipos(skill.tiposDano)?.let { throw ResponseStatusException(HttpStatus.BAD_REQUEST, it) }
    }

    /** Skills com tier: o equilíbrio não fica salvo na fórmula e o texto da fórmula é refeito a partir do tier. */
    private fun normalizar(skill: Skill): Skill {
        if (!skill.usaTier()) return skill.copy(tier = null)   // Geral, mestre e inimigo: equilíbrio próprio
        val tier = skill.tier ?: return skill
        val d = skill.damage ?: return skill
        val eq = gameStateService.combateConfig().tier(tier).equilibrio
        return skill.copy(damage = d.copy(equilibrio = null, formula = TierRules.formula(d.baseFixed, d.atributo, eq)))
    }

    companion object {
        /** Skills de jogador; inimigo e mestre ficam fora do tier. */
        val TIPOS_COM_TIER = setOf("class", "weapon", "essencia")
    }
}
