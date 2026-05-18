package com.rpg.essentia.service

import com.rpg.essentia.model.Player
import com.rpg.essentia.model.TurnUpdate
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.stereotype.Service

@Service
class TurnService(
    private val playerRepository: PlayerRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun nextTurn(): TurnUpdate {
        // Advance currentTurnIndex in GameState
        val state = gameStateService.getOrCreate()
        val size = state.initiative.size
        val newIndex = if (size > 0) (state.currentTurnIndex + 1) % size else 0
        val newTotal = state.totalTurns + 1
        val currentPlayerId = if (size > 0) state.initiative[newIndex].playerId else ""
        gameStateService.save(state.copy(currentTurnIndex = newIndex, totalTurns = newTotal))

        val players = playerRepository.findAll()

        players.forEach { player ->
            var updated = player

            if (currentPlayerId.isNotBlank() && player.id == currentPlayerId) {
                // Auto-effects, cooldowns and effect durations only advance on this player's own turn
                updated = applyAutoEffects(updated)

                val newSlots = updated.slots.map { slot ->
                    if (slot.cooldownRemaining > 0) slot.copy(cooldownRemaining = slot.cooldownRemaining - 1)
                    else slot
                }

                val newStatusEffects = updated.statusEffects
                    .map { effect ->
                        if (effect.durationTurns == -1) effect  // permanent
                        else effect.copy(durationTurns = effect.durationTurns - 1)
                    }
                    .filter { it.durationTurns != 0 }

                updated = updated.copy(slots = newSlots, statusEffects = newStatusEffects)
            }

            val saved = playerRepository.save(updated)
            broadcaster.broadcastPlayer(saved)
        }

        val update = TurnUpdate(message = "Next turn started", currentTurnIndex = newIndex, totalTurns = newTotal)
        broadcaster.broadcastTurn(update)
        return update
    }

    private fun applyAutoEffects(player: Player): Player {
        var updated = player
        for (effect in player.statusEffects.filter { it.durationTurns != 0 }) {
            for (auto in effect.effects.filter { it.trigger == "on_turn_start" }) {
                val value = auto.value ?: 0
                updated = when (auto.type) {
                    "damage_hp"   -> updated.copy(hp    = updated.hp.copy(current    = (updated.hp.current    - value).coerceIn(0, updated.hp.max)))
                    "heal_hp"     -> updated.copy(hp    = updated.hp.copy(current    = (updated.hp.current    + value).coerceIn(0, updated.hp.max)))
                    "damage_flow" -> updated.copy(flow  = updated.flow.copy(current  = (updated.flow.current  - value).coerceIn(0, updated.flow.max)))
                    "heal_flow"   -> updated.copy(flow  = updated.flow.copy(current  = (updated.flow.current  + value).coerceIn(0, updated.flow.max)))
                    "damage_ether" -> updated.copy(ether = updated.ether.copy(current = (updated.ether.current - value).coerceIn(0, updated.ether.max)))
                    "heal_ether"   -> updated.copy(ether = updated.ether.copy(current = (updated.ether.current + value).coerceIn(0, updated.ether.max)))
                    else -> updated
                }
            }
        }
        return updated
    }
}
