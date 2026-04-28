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
        gameStateService.save(state.copy(images = state.images + image))
        return image
    }

    fun activateImage(id: String): GameImage {
        val state = gameStateService.getOrCreate()
        val target = state.images.firstOrNull { it.id == id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found")
        val newImages = state.images.map { img ->
            img.copy(active = img.id == id)
        }
        gameStateService.save(state.copy(images = newImages))
        val activated = target.copy(active = true)
        broadcaster.broadcastImage(activated)
        return activated
    }
}
