package com.rpg.essentia.service

import com.rpg.essentia.model.GameDocument
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class DocumentService(
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun getDocuments(): List<GameDocument> = gameStateService.getOrCreate().documents

    fun createDocument(url: String, title: String): GameDocument {
        val document = GameDocument(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title,
            timestamp = Instant.now().toString(),
            active = false
        )
        val state = gameStateService.getOrCreate()
        val newDocuments = state.documents + document
        gameStateService.save(state.copy(documents = newDocuments))
        broadcaster.broadcastDocuments(newDocuments)
        return document
    }

    fun toggleDocument(id: String): List<GameDocument> {
        val state = gameStateService.getOrCreate()
        state.documents.firstOrNull { it.id == id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found")
        val newDocuments = state.documents.map { doc ->
            if (doc.id == id) doc.copy(active = !doc.active) else doc
        }
        gameStateService.save(state.copy(documents = newDocuments))
        broadcaster.broadcastDocuments(newDocuments)
        return newDocuments
    }

    fun updateDocument(id: String, url: String, title: String): GameDocument {
        val state = gameStateService.getOrCreate()
        val target = state.documents.firstOrNull { it.id == id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found")
        val updated = target.copy(url = url, title = title)
        val newDocuments = state.documents.map { if (it.id == id) updated else it }
        gameStateService.save(state.copy(documents = newDocuments))
        broadcaster.broadcastDocuments(newDocuments)
        return updated
    }

    fun deleteDocument(id: String) {
        val state = gameStateService.getOrCreate()
        val newDocuments = state.documents.filter { it.id != id }
        gameStateService.save(state.copy(documents = newDocuments))
        broadcaster.broadcastDocuments(newDocuments)
    }
}
