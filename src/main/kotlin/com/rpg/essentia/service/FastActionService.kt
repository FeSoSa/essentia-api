package com.rpg.essentia.service

import com.rpg.essentia.model.FastAction
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class FastActionService(
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun getFastAction(): FastAction = gameStateService.getOrCreate().fastAction

    fun setFastAction(fastAction: FastAction): FastAction {
        val state = gameStateService.getOrCreate()
        gameStateService.save(state.copy(fastAction = fastAction))
        broadcaster.broadcastFastAction(fastAction)
        return fastAction
    }

    fun vote(playerId: String, optionId: String): FastAction {
        val state = gameStateService.getOrCreate()
        val fa = state.fastAction

        if (!fa.active)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Fast action is not active")
        if (fa.options.none { it.id == optionId })
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Option not found")
        if (fa.lockOnePerPlayer && playerId in fa.lockedPlayers)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Player already voted")
        if (optionId in fa.answers.values)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Option already taken")

        val newAnswers = fa.answers + (playerId to optionId)
        val newLocked = if (fa.lockOnePerPlayer) fa.lockedPlayers + playerId else fa.lockedPlayers
        val updated = fa.copy(answers = newAnswers, lockedPlayers = newLocked)

        gameStateService.save(state.copy(fastAction = updated))
        broadcaster.broadcastFastAction(updated)
        return updated
    }

    fun deleteFastAction(): FastAction {
        val state = gameStateService.getOrCreate()
        val empty = FastAction(
            active = false,
            title = "",
            lockOnePerPlayer = false,
            lockedPlayers = emptyList(),
            options = emptyList(),
            answers = emptyMap()
        )
        gameStateService.save(state.copy(fastAction = empty))
        broadcaster.broadcastFastAction(empty)
        return empty
    }
}
