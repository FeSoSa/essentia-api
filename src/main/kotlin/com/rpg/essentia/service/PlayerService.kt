package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

private val VALID_ATTRIBUTES = setOf(
    "strength", "agility", "intelligence", "resistance", "flow", "wisdom", "presence", "defense"
)

@Service
class PlayerService(
    private val playerRepository: PlayerRepository,
    private val attributeService: AttributeService,
    private val essenciaRepository: EssenciaRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun load(id: String): Player =
        playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found")
        }

    fun adjustHp(id: String, delta: Int): Player {
        val player = load(id)
        val newCurrent = (player.hp.current + delta).coerceIn(0, player.hp.max)
        return saveAndBroadcast(player.copy(hp = player.hp.copy(current = newCurrent)))
    }

    fun adjustFlow(id: String, delta: Int): Player {
        val player = load(id)
        val newCurrent = (player.flow.current + delta).coerceIn(0, player.flow.max)
        return saveAndBroadcast(player.copy(flow = player.flow.copy(current = newCurrent)))
    }

    fun adjustEther(id: String, delta: Int): Player {
        val player = load(id)
        val newCurrent = (player.ether.current + delta).coerceIn(0, player.ether.max)
        return saveAndBroadcast(player.copy(ether = player.ether.copy(current = newCurrent)))
    }

    fun adjustAttribute(id: String, attribute: String, delta: Int): Player {
        if (attribute !in VALID_ATTRIBUTES)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attribute: $attribute")
        if (delta <= 0)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Delta must be positive")

        val player = load(id)
        if (player.exp.available < delta)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Insufficient exp (need $delta, have ${player.exp.available})"
            )

        val attrMap = player.attributes.toMap().toMutableMap()
        attrMap[attribute] = (attrMap[attribute] ?: 0) + delta
        var updated = player.copy(
            attributes = attrMap.toAttributes(),
            exp = player.exp.copy(available = player.exp.available - delta)
        )

        // Recalculate vitals when resistance or flow attribute changes
        if (attribute == "resistance" || attribute == "flow") {
            val essencias = essenciaRepository.findAll()
            val effective = attributeService.computeEffectiveAttributes(updated, essencias)
            updated = attributeService.recalculateVitals(updated, effective)
        }

        gameStateService.addLogEntry(id, "${player.char.name} aumentou $attribute em $delta")
        return saveAndBroadcast(updated)
    }

    fun updateSlot(id: String, slotId: String, skillId: String?): Player {
        val player = load(id)
        val newSlots = player.slots.map { slot ->
            if (slot.id == slotId) slot.copy(skillId = skillId) else slot
        }
        return saveAndBroadcast(player.copy(slots = newSlots))
    }

    fun requestItem(id: String, itemId: String): Player {
        val player = load(id)
        player.items.firstOrNull { it.id == itemId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found")
        val request = PendingRequest(
            id = UUID.randomUUID().toString(),
            type = "use_item",
            itemId = itemId,
            timestamp = Instant.now().toString()
        )
        return saveAndBroadcast(player.copy(pendingRequests = player.pendingRequests + request))
    }

    fun saveAndBroadcast(player: Player): Player {
        val saved = playerRepository.save(player)
        broadcaster.broadcastPlayer(saved)
        return saved
    }
}
