package com.rpg.essentia.service

import com.rpg.essentia.model.TurnUpdate
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.stereotype.Service

@Service
class TurnService(
    private val playerRepository: PlayerRepository,
    private val broadcaster: WebSocketBroadcaster
) {
    fun nextTurn(): TurnUpdate {
        val players = playerRepository.findAll()

        players.forEach { player ->
            // Decrement slot cooldowns (min 0)
            val newSlots = player.slots.map { slot ->
                if (slot.cooldownRemaining > 0) slot.copy(cooldownRemaining = slot.cooldownRemaining - 1)
                else slot
            }

            // Phase 1: decrement status effect durations, remove those that expire (durationTurns != -1)
            val newStatusEffects = player.statusEffects
                .map { effect ->
                    if (effect.durationTurns == -1) effect  // permanent
                    else effect.copy(durationTurns = effect.durationTurns - 1)
                }
                .filter { it.durationTurns != 0 }

            val updated = player.copy(slots = newSlots, statusEffects = newStatusEffects)
            val saved = playerRepository.save(updated)
            broadcaster.broadcastPlayer(saved)
        }

        val update = TurnUpdate(message = "Next turn started")
        broadcaster.broadcastTurn(update)
        return update
    }
}
