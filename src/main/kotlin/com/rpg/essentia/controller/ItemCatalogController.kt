package com.rpg.essentia.controller

import com.rpg.essentia.model.ItemCatalog
import com.rpg.essentia.service.ItemCatalogService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/items")
class ItemCatalogController(private val service: ItemCatalogService) {

    @GetMapping
    fun list(): List<ItemCatalog> = service.list()

    @PostMapping
    fun create(@RequestBody item: ItemCatalog): ItemCatalog = service.create(item)

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody item: ItemCatalog): ItemCatalog =
        service.update(id, item)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String) = service.delete(id)
}
