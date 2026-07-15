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
    private val broadcaster: WebSocketBroadcaster
) {

    fun list(): List<Skill> = skillRepository.findAll()

    fun create(skill: Skill): Skill {
        validate(skill)
        return skillRepository.save(skill)
    }

    fun update(id: String, skill: Skill): Skill {
        validate(skill)
        val saved = skillRepository.save(skill.copy(id = id))
        val allEssencias = essenciaRepository.findAll()
        playerRepository.findAll().forEach { player ->
            val effective = attributeService.computeEffectiveAttributes(player, allEssencias)
            val updated = attributeService.recalculateVitals(player, effective)
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
    }
}
