package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class EssenciaService(
    private val essenciaRepository: EssenciaRepository,
    private val playerRepository: PlayerRepository,
    private val playerSkillRepository: PlayerSkillRepository,
    private val attributeService: AttributeService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun listAll(): List<Essencia> = essenciaRepository.findAll()

    fun create(essencia: Essencia): Essencia = essenciaRepository.save(essencia)

    fun update(id: String, essencia: Essencia): Essencia {
        val saved = essenciaRepository.save(essencia.copy(id = id))
        broadcastAllPlayers()
        return saved
    }

    fun delete(id: String) = essenciaRepository.deleteById(id)

    fun grantEssencia(playerId: String, essenciaId: String): Player {
        val player = loadPlayer(playerId)
        essenciaRepository.findById(essenciaId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Essência não encontrada: $essenciaId")
        }
        if (player.essenciasObtidas.any { it.essenciaId == essenciaId })
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Jogador já possui esta essência")

        val allEssencias = essenciaRepository.findAll()
        val essencia = allEssencias.first { it.id == essenciaId }
        val obtained = EssenciaObtida(
            essenciaId = essenciaId,
            attributeBonusActive = true,
            unlockedSkillIds = essencia.skillIds
        )
        val updated = player.copy(essenciasObtidas = player.essenciasObtidas + obtained)
        val effective = attributeService.computeEffectiveAttributes(updated, allEssencias)
        return saveAndBroadcast(attributeService.recalculateVitals(updated, effective))
    }

    fun removeEssencia(playerId: String, essenciaId: String): Player {
        val player = loadPlayer(playerId)
        val allEssencias = essenciaRepository.findAll()

        // Remove skills unlocked exclusively by this essência
        val lostSkillIds = allEssencias.firstOrNull { it.id == essenciaId }?.skillIds?.toSet() ?: emptySet()
        if (lostSkillIds.isNotEmpty()) {
            playerSkillRepository.findByPlayerId(playerId)
                .filter { it.skillId in lostSkillIds }
                .forEach { playerSkillRepository.deleteById(it.id) }
        }

        val updated = player.copy(
            essenciasObtidas = player.essenciasObtidas.filter { it.essenciaId != essenciaId },
            slots = if (lostSkillIds.isEmpty()) player.slots else player.slots.map { slot ->
                if (slot.skillId in lostSkillIds) slot.copy(skillId = null, cooldownRemaining = 0) else slot
            }
        )
        val effective = attributeService.computeEffectiveAttributes(updated, allEssencias)
        return saveAndBroadcast(attributeService.recalculateVitals(updated, effective))
    }

    private fun loadPlayer(id: String): Player =
        playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado: $id")
        }

    private fun broadcastAllPlayers() {
        val allEssencias = essenciaRepository.findAll()
        playerRepository.findAll().forEach { player ->
            val effective = attributeService.computeEffectiveAttributes(player, allEssencias)
            val updated = attributeService.recalculateVitals(player, effective)
            val saved = playerRepository.save(updated)
            broadcaster.broadcastPlayer(saved)
        }
    }

    private fun saveAndBroadcast(player: Player): Player {
        val saved = playerRepository.save(player)
        broadcaster.broadcastPlayer(saved)
        return saved
    }
}
