package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class PlayerCreationService(
    private val playerRepository: PlayerRepository,
    private val playerSkillRepository: PlayerSkillRepository,
    private val skillRepository: SkillRepository,
    private val classKitService: ClassKitService,
    private val raceService: RaceService,
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

        val slotsClass = req.slotsClass
        val slotsFree  = req.slotsFree
        val slotsTotal = slotsClass + slotsFree

        val hpMax   = 20 + (req.attributes.resistance * 5)
        val flowMax = 20 + (req.attributes.flow * 5)

        val slots = mutableListOf<Slot>()
        repeat(slotsClass) { slots.add(Slot(id = UUID.randomUUID().toString(), type = "class")) }
        repeat(slotsFree)  { slots.add(Slot(id = UUID.randomUUID().toString(), type = "free"))  }
        if (req.race == "Humano") slots.add(Slot(id = UUID.randomUUID().toString(), type = "human_bonus"))

        val player = Player(
            id = UUID.randomUUID().toString(),
            code = req.code,
            char = CharInfo(
                name = req.name,
                classe = req.skillClass,
                skillClass = req.skillClass,
                race = req.race,
                level = 1,
                slotsClass = slotsClass,
                slotsFree = slotsFree,
                slotsTotal = slotsTotal,
                unarmedAttack = kit.perks.unarmedAttack
            ),
            hp   = Vital(current = hpMax,  max = hpMax),
            flow = Vital(current = flowMax, max = flowMax),
            ether = Ether(
                unlocked = req.etherUnlocked,
                max      = minOf(req.attributes.wisdom / 4, 10),
                current  = minOf(req.attributes.wisdom / 4, 10)
            ),
            pressao = if (kit.perks.hasPressureBar) Vital(current = 0, max = 5) else null,
            attributes = req.attributes,
            equipment  = req.equipment,
            items      = req.items,
            slots      = slots
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

        val newSlotsClass = req.slotsClass ?: existing.char.slotsClass
        val newSlotsFree  = req.slotsFree  ?: existing.char.slotsFree

        // Reconstrói a lista de slots preservando habilidades equipadas
        val existingClass = existing.slots.filter { it.type == "class" }
        val existingFree  = existing.slots.filter { it.type == "free" }
        val bonusSlots    = existing.slots.filter { it.type == "human_bonus" }

        fun resize(current: List<Slot>, newCount: Int, type: String): List<Slot> =
            List(newCount) { i ->
                current.getOrElse(i) { Slot(id = UUID.randomUUID().toString(), type = type) }
            }

        val newSlots = resize(existingClass, newSlotsClass, "class") +
                       resize(existingFree,  newSlotsFree,  "free")  +
                       bonusSlots

        val kitPerks = try { classKitService.getByClass(req.skillClass).perks } catch (_: Exception) { null }

        val updatedPressao = when {
            kitPerks?.hasPressureBar == true && existing.pressao == null -> Vital(current = 0, max = 5)
            kitPerks?.hasPressureBar == true -> existing.pressao
            else -> null
        }

        val updated = existing.copy(
            code = req.code,
            char = existing.char.copy(
                name          = req.name,
                classe        = req.skillClass,
                skillClass    = req.skillClass,
                race          = req.race,
                portraitUrl   = req.portraitUrl ?: existing.char.portraitUrl,
                level         = req.level       ?: existing.char.level,
                slotsClass    = newSlotsClass,
                slotsFree     = newSlotsFree,
                slotsTotal    = newSlotsClass + newSlotsFree,
                unarmedAttack = kitPerks?.unarmedAttack ?: existing.char.unarmedAttack
            ),
            hp    = existing.hp.copy(max = hpMax),
            flow  = existing.flow.copy(max = flowMax),
            ether = existing.ether.copy(
                unlocked = req.etherUnlocked ?: existing.ether.unlocked,
                max      = minOf(req.attributes.wisdom / 4, 10),
                current  = minOf(existing.ether.current, minOf(req.attributes.wisdom / 4, 10))
            ),
            pressao    = updatedPressao,
            attributes = req.attributes,
            exp = Exp(
                available = req.expAvailable ?: existing.exp.available,
                total     = req.expTotal     ?: existing.exp.total
            ),
            slots = newSlots,
            sobrecargaDesbloqueada = req.sobrecargaDesbloqueada ?: existing.sobrecargaDesbloqueada
        )
        // Se a classe mudou, remove skills exclusivas da classe antiga
        if (req.skillClass != existing.char.skillClass) {
            val newClass = req.skillClass
            val allPlayerSkills = playerSkillRepository.findByPlayerId(id)
            val lostSkillIds = allPlayerSkills.mapNotNull { ps ->
                val skill = skillRepository.findById(ps.skillId).orElse(null)
                if (skill != null && skill.type == "class" && !skill.skillClass.isNullOrBlank() && skill.skillClass != newClass)
                    ps.skillId else null
            }.toSet()
            if (lostSkillIds.isNotEmpty()) {
                allPlayerSkills.filter { it.skillId in lostSkillIds }
                    .forEach { playerSkillRepository.deleteById(it.id) }
                val clearedSlots = updated.slots.map { slot ->
                    if (slot.skillId in lostSkillIds) slot.copy(skillId = null, cooldownRemaining = 0) else slot
                }
                val withCleared = updated.copy(slots = clearedSlots)
                val saved = playerRepository.save(withCleared)
                broadcaster.broadcastPlayer(saved)
                broadcaster.broadcastPlayers(playerRepository.findAll())
                return saved
            }
        }

        val saved = playerRepository.save(updated)
        broadcaster.broadcastPlayer(saved)
        broadcaster.broadcastPlayers(playerRepository.findAll())
        return saved
    }

    fun resetAttributes(id: String): Player {
        val player = playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado: $id")
        }
        val initial = raceService.getByName(player.char.race)?.starterAttributes
            ?: try { classKitService.getByClass(player.char.skillClass).starterAttributes } catch (_: Exception) { null }
            ?: Attributes()

        fun cost(v: Int) = when {
            v <= 15 -> 1; v <= 20 -> 2; v <= 25 -> 3; v <= 30 -> 4; else -> 5
        }
        fun spent(ini: Int, cur: Int) = if (cur <= ini) 0 else (ini until cur).sumOf { cost(it) }

        val totalSpent = listOf(
            spent(initial.strength,     player.attributes.strength),
            spent(initial.agility,      player.attributes.agility),
            spent(initial.intelligence, player.attributes.intelligence),
            spent(initial.resistance,   player.attributes.resistance),
            spent(initial.flow,         player.attributes.flow),
            spent(initial.wisdom,       player.attributes.wisdom),
            spent(initial.presence,     player.attributes.presence),
            spent(initial.defense,      player.attributes.defense),
        ).sum()

        val updated = player.copy(
            attributes = initial,
            exp = player.exp.copy(available = player.exp.available + totalSpent)
        )
        playerRepository.save(updated)
        broadcaster.broadcastPlayer(updated)
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
