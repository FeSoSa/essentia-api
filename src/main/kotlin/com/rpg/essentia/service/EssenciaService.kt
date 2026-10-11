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

    fun create(essencia: Essencia): Essencia = essenciaRepository.save(validar(essencia))

    fun update(id: String, essencia: Essencia): Essencia {
        val saved = essenciaRepository.save(validar(essencia.copy(id = id)))
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
        // Receber a Essência coloca o personagem na maestria nível 1 dela
        val updated = MaestriaRules.garantir(player.copy(essenciasObtidas = player.essenciasObtidas + obtained))
        val effective = attributeService.computeEffectiveAttributes(updated, allEssencias)
        return saveAndBroadcast(attributeService.recalculateVitals(updated, effective))
    }

    /** Mestre define o nível (1 a 4); Capacidade, penalidade e vitais são recalculados. */
    fun setNivel(playerId: String, essenciaId: String, nivel: Int): Player {
        if (nivel !in EssenciaRules.NIVEL_MIN..EssenciaRules.NIVEL_MAX)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível de Essência deve ser de 1 a 4")
        val player = loadPlayer(playerId)
        if (player.essenciasObtidas.none { it.essenciaId == essenciaId })
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não possui esta essência")
        val updated = player.copy(essenciasObtidas = player.essenciasObtidas.map {
            if (it.essenciaId == essenciaId) it.copy(nivel = nivel) else it
        })
        val effective = attributeService.computeEffectiveAttributes(updated, essenciaRepository.findAll())
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

    /** Nível da mãe para liberar a Derivada: 1 a 4, e só faz sentido em Derivada com mãe. */
    private fun validar(e: Essencia): Essencia {
        val nivel = e.nivelMaeNecessario ?: return e
        if (nivel !in EssenciaRules.NIVEL_MIN..EssenciaRules.NIVEL_MAX)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível da Essência-mãe deve ser de 1 a 4")
        return if (e.type == "Derivada" && e.parentId != null) e else e.copy(nivelMaeNecessario = null)
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
