package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class PlayerCreationService(
    private val playerRepository: PlayerRepository,
    private val playerSkillRepository: PlayerSkillRepository,
    private val classKitService: ClassKitService,
    private val broadcaster: WebSocketBroadcaster
) {

    fun createPlayer(req: CreatePlayerRequest): Player {
        if (req.code.isBlank())
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Código não pode ser vazio")

        if (playerRepository.findByCode(req.code) != null)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Código já existe: ${req.code}")

        val kit = classKitService.getByClass(req.skillClass)

        if (req.skillClass == "Artífice" && req.race != "Gnomo")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Artífice só pode ser criado com raça Gnomo")

        with(req.attributes) {
            listOf(strength, agility, intelligence, resistance, flow, wisdom, presence, defense).forEach {
                if (it < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Atributos não podem ser negativos")
            }
        }

        val slotsClass = 2
        val slotsFree = if (req.race == "Humano") 7 else 6
        val slotsTotal = slotsClass + slotsFree

        val hpMax = 20 + (req.attributes.resistance * 5)
        val flowMax = 20 + (req.attributes.flow * 5)

        val slots = kit.starterSlots.toMutableList()
        if (req.race == "Humano") {
            slots.add(Slot(id = UUID.randomUUID().toString(), type = "human_bonus"))
        }

        val player = Player(
            id = UUID.randomUUID().toString(),
            code = req.code,
            char = CharInfo(
                name = req.name,
                skillClass = req.skillClass,
                subClass = req.subClass,
                race = req.race,
                level = 1,
                slotsClass = slotsClass,
                slotsFree = slotsFree,
                slotsTotal = slotsTotal
            ),
            hp = Vital(current = hpMax, max = hpMax),
            flow = Vital(current = flowMax, max = flowMax),
            attributes = req.attributes,
            equipment = req.equipment,
            items = req.items,
            slots = slots
        )
        playerRepository.save(player)

        kit.starterSkillIds.forEach { skillId ->
            playerSkillRepository.save(
                PlayerSkill(
                    id = UUID.randomUUID().toString(),
                    playerId = player.id,
                    skillId = skillId
                )
            )
        }

        broadcaster.broadcastPlayers(playerRepository.findAll())

        return player
    }

    fun updatePlayer(id: String, req: UpdatePlayerRequest): Player {
        val existing = playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado: $id")
        }

        val codeConflict = playerRepository.findByCode(req.code)
        if (codeConflict != null && codeConflict.id != id)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Código já existe: ${req.code}")

        val hpMax = 20 + (req.attributes.resistance * 5)
        val flowMax = 20 + (req.attributes.flow * 5)

        val updated = existing.copy(
            code = req.code,
            char = existing.char.copy(
                name = req.name,
                skillClass = req.skillClass,
                subClass = req.subClass,
                race = req.race
            ),
            hp = existing.hp.copy(max = hpMax),
            flow = existing.flow.copy(max = flowMax),
            attributes = req.attributes
        )
        playerRepository.save(updated)
        broadcaster.broadcastPlayers(playerRepository.findAll())
        return updated
    }

    fun deletePlayer(id: String) {
        playerRepository.deleteById(id)
        playerSkillRepository.findByPlayerId(id).forEach {
            playerSkillRepository.deleteById(it.id)
        }
        broadcaster.broadcastPlayers(playerRepository.findAll())
    }
}
