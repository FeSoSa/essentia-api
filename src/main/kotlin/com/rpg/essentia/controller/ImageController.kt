package com.rpg.essentia.controller

import com.rpg.essentia.model.GameImage
import com.rpg.essentia.model.ImageCreateRequest
import com.rpg.essentia.model.ImageUpdateRequest
import com.rpg.essentia.service.ImageService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/images")
class ImageController(private val imageService: ImageService) {

    @GetMapping
    fun getImages(): List<GameImage> = imageService.getImages()

    @PostMapping
    fun createImage(@RequestBody req: ImageCreateRequest): GameImage =
        imageService.createImage(req.url, req.title)

    @PutMapping("/{id}")
    fun updateImage(@PathVariable id: String, @RequestBody req: ImageUpdateRequest): GameImage =
        imageService.updateImage(id, req.url, req.title)

    @PutMapping("/{id}/toggle")
    fun toggleImage(@PathVariable id: String): List<GameImage> =
        imageService.toggleImage(id)

    @DeleteMapping("/{id}")
    fun deleteImage(@PathVariable id: String): ResponseEntity<Void> {
        imageService.deleteImage(id)
        return ResponseEntity.noContent().build()
    }
}
