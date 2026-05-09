package com.rpg.essentia.service

import com.rpg.essentia.model.GameImage
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class ImageService(
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun getImages(): List<GameImage> = gameStateService.getOrCreate().images

    fun createImage(url: String, title: String): GameImage {
        val image = GameImage(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title,
            timestamp = Instant.now().toString(),
            active = false
        )
        val state = gameStateService.getOrCreate()
        val newImages = state.images + image
        gameStateService.save(state.copy(images = newImages))
        broadcaster.broadcastImages(newImages)
        return image
    }

    fun toggleImage(id: String): List<GameImage> {
        val state = gameStateService.getOrCreate()
        state.images.firstOrNull { it.id == id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found")
        val newImages = state.images.map { img ->
            if (img.id == id) img.copy(active = !img.active) else img
        }
        gameStateService.save(state.copy(images = newImages))
        broadcaster.broadcastImages(newImages)
        return newImages
    }

    fun updateImage(id: String, url: String, title: String): GameImage {
        val state = gameStateService.getOrCreate()
        val target = state.images.firstOrNull { it.id == id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found")
        val updated = target.copy(url = url, title = title)
        val newImages = state.images.map { if (it.id == id) updated else it }
        gameStateService.save(state.copy(images = newImages))
        broadcaster.broadcastImages(newImages)
        return updated
    }

    fun deleteImage(id: String) {
        val state = gameStateService.getOrCreate()
        val newImages = state.images.filter { it.id != id }
        gameStateService.save(state.copy(images = newImages))
        broadcaster.broadcastImages(newImages)
    }
}
