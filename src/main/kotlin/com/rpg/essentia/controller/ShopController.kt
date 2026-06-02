package com.rpg.essentia.controller

import com.rpg.essentia.model.*
import com.rpg.essentia.service.ShopService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/shops")
class ShopController(private val shopService: ShopService) {

    @GetMapping
    fun getAll(): List<Shop> = shopService.getAll()

    @GetMapping("/active")
    fun getActive(): List<Shop> = shopService.getActive()

    @PostMapping
    fun create(@RequestBody shop: Shop): Shop = shopService.create(shop)

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody shop: Shop): Shop = shopService.update(id, shop)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String) = shopService.delete(id)

    @PostMapping("/{id}/purchase")
    fun purchase(@PathVariable id: String, @RequestBody req: ShopPurchaseRequest): Player =
        shopService.purchase(id, req)
}
