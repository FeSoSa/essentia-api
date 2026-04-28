package com.rpg.essentia.controller

import com.rpg.essentia.model.GameImage
import com.rpg.essentia.model.ImageCreateRequest
import com.rpg.essentia.service.ImageService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/images")
class ImageController(private val imageService: ImageService) {

    @GetMapping
    fun getImages(): List<GameImage> = imageService.getImages()

    @PostMapping
    fun createImage(@RequestBody req: ImageCreateRequest): GameImage =
        imageService.createImage(req.url, req.title)

    @PutMapping("/{id}/activate")
    fun activateImage(@PathVariable id: String): GameImage =
        imageService.activateImage(id)
}
