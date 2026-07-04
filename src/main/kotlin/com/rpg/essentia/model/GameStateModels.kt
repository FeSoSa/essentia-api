package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

data class GameImage(
    val id: String = "",
    val url: String = "",
    val title: String = "",
    val timestamp: String = "",
    val active: Boolean = false
)

data class FastActionOption(
    val id: String = "",
    val text: String = "",
    val color: String = ""
)

data class FastAction(
    val active: Boolean = false,
    val title: String = "",
    val lockOnePerPlayer: Boolean = false,
    val lockedPlayers: List<String> = emptyList(),
    val options: List<FastActionOption> = emptyList(),
    val answers: Map<String, String> = emptyMap()   // playerId -> optionId
)

data class InitiativeEntry(
    val playerId: String = "",
    val name: String = "",
    val value: Int = 0
)

data class LogEntry(
    val playerId: String = "",
    val text: String = "",
    val time: String = "",
    val timestamp: String = "",
    val type: String = "info"   // "skill"|"hp"|"flow"|"ether"|"attr"|"level"|"item"|"essencia"|"info"
)

data class CollectiveBar(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val color: String = "#3b82f6",
    val current: Int = 0,
    val max: Int = 10
)

data class Note(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String = "",
    val color: String = "#fbbf24",
    val timestamp: String = ""
)

@Document(collection = "game_state")
data class GameState(
    @Id val id: String = "main",
    val images: List<GameImage> = emptyList(),
    val fastAction: FastAction = FastAction(),
    val initiative: List<InitiativeEntry> = emptyList(),
    val currentTurnIndex: Int = 0,
    val totalTurns: Int = 0,
    val log: List<LogEntry> = emptyList(),
    val collectiveBars: List<CollectiveBar> = emptyList(),
    val notes: List<Note> = emptyList()
)
