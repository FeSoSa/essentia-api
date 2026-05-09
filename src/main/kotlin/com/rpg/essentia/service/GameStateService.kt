package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.GameStateRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Service
class GameStateService(
    private val gameStateRepository: GameStateRepository,
    private val broadcaster: WebSocketBroadcaster
) {
    fun getOrCreate(): GameState =
        gameStateRepository.findById("main").orElseGet { createDefault() }

    fun save(gameState: GameState): GameState =
        gameStateRepository.save(gameState)

    fun addLogEntry(playerId: String, text: String, type: String = "info"): GameState {
        val now = Instant.now()
        val entry = LogEntry(
            playerId = playerId,
            text = text,
            time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            timestamp = now.toString(),
            type = type
        )
        val state = getOrCreate()
        val updated = state.copy(log = state.log + entry)
        val saved = save(updated)
        broadcaster.broadcastLog(entry)
        return saved
    }

    fun getCollectiveBars(): List<CollectiveBar> = getOrCreate().collectiveBars

    fun addCollectiveBar(bar: CollectiveBar): List<CollectiveBar> {
        val state = getOrCreate()
        val updated = save(state.copy(collectiveBars = state.collectiveBars + bar))
        broadcaster.broadcastCollectiveBars(updated.collectiveBars)
        return updated.collectiveBars
    }

    fun updateCollectiveBar(barId: String, current: Int?, max: Int?): List<CollectiveBar> {
        val state = getOrCreate()
        val bars = state.collectiveBars.map { b ->
            if (b.id != barId) b
            else b.copy(
                current = current?.coerceIn(0, max ?: b.max) ?: b.current,
                max     = max ?: b.max
            )
        }
        val updated = save(state.copy(collectiveBars = bars))
        broadcaster.broadcastCollectiveBars(updated.collectiveBars)
        return updated.collectiveBars
    }

    fun removeCollectiveBar(barId: String): List<CollectiveBar> {
        val state = getOrCreate()
        val updated = save(state.copy(collectiveBars = state.collectiveBars.filter { it.id != barId }))
        broadcaster.broadcastCollectiveBars(updated.collectiveBars)
        return updated.collectiveBars
    }

    private fun createDefault() = GameState(
        id = "main",
        images = emptyList(),
        fastAction = FastAction(
            active = false,
            title = "",
            lockOnePerPlayer = false,
            lockedPlayers = emptyList(),
            options = emptyList(),
            answers = emptyMap()
        ),
        initiative = emptyList(),
        log = emptyList()
    )
}
