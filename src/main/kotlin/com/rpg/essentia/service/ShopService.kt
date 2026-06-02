package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.ItemCatalogRepository
import com.rpg.essentia.repository.ShopRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class ShopService(
    private val shopRepository: ShopRepository,
    private val itemCatalogRepository: ItemCatalogRepository,
    private val masterService: MasterService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun getAll(): List<Shop> = shopRepository.findAll()

    fun getActive(): List<Shop> = shopRepository.findByActiveTrue()

    fun create(shop: Shop): Shop = shopRepository.save(shop)

    fun update(id: String, shop: Shop): Shop {
        if (!shopRepository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found")
        val saved = shopRepository.save(shop.copy(id = id))
        broadcaster.broadcastShops(shopRepository.findByActiveTrue())
        return saved
    }

    fun delete(id: String) {
        if (!shopRepository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found")
        shopRepository.deleteById(id)
        broadcaster.broadcastShops(shopRepository.findByActiveTrue())
    }

    fun purchase(shopId: String, req: ShopPurchaseRequest): Player {
        val shop = shopRepository.findById(shopId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found")
        }
        if (!shop.active) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Shop is not active")

        val shopItem = shop.items.find { it.itemId == req.itemId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item not in shop")

        shopItem.stockLimit?.let { limit ->
            if (req.qty > limit) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity exceeds stock limit")
        }

        val catalog = itemCatalogRepository.findById(req.itemId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found in catalog")
        }

        val goldValue = catalog.goldValue
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Item has no price set")

        val cost = goldValue * req.qty

        val addReq = AddItemRequest(
            name = catalog.name,
            desc = catalog.desc,
            qty = req.qty,
            type = catalog.type,
            icon = catalog.icon,
            weaponType = catalog.weaponType,
            damageBase = catalog.damageBase,
            damageAttribute = catalog.damageAttribute,
            equilibrio = catalog.equilibrio,
            properties = catalog.properties,
            damageReduction = catalog.damageReduction,
            armorWeight = catalog.armorWeight,
            attributeBonus = catalog.attributeBonus,
            equipSlot = catalog.equipSlot,
            rarity = catalog.rarity,
            twoHanded = catalog.twoHanded,
            requirements = catalog.requirements,
            onUseEffect = catalog.onUseEffect
        )

        // addItem validates player exists; adjustGold will then run on updated player
        masterService.addItem(req.playerId, addReq)
        return masterService.adjustGold(req.playerId, -cost)
    }
}
