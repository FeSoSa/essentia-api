package com.rpg.essentia.controller

import com.rpg.essentia.model.Region
import com.rpg.essentia.service.RegionService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/regions")
class RegionController(private val regionService: RegionService) {

    @GetMapping
    fun getAll(): List<Region> = regionService.getAll()

    @PostMapping
    fun create(@RequestBody region: Region): Region = regionService.create(region)

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody region: Region): Region =
        regionService.update(id, region)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String) = regionService.delete(id)
}
