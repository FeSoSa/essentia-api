package com.rpg.essentia.service

import com.rpg.essentia.model.Item
import com.rpg.essentia.model.ItemCatalog
import com.rpg.essentia.repository.ItemCatalogRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ItemCatalogService(private val repository: ItemCatalogRepository) {

    fun list(): List<ItemCatalog> = repository.findAll()

    fun create(item: ItemCatalog): ItemCatalog =
        repository.save(item.copy(id = UUID.randomUUID().toString()))

    fun update(id: String, item: ItemCatalog): ItemCatalog {
        if (!repository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found")
        return repository.save(item.copy(id = id))
    }

    fun delete(id: String) {
        if (!repository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found")
        repository.deleteById(id)
    }

    /**
     * Resolve os campos de definição de um Item de jogador contra o catálogo atual,
     * preservando os dados de instância (id, qty). Itens sem catalogId, ou cujo
     * catálogo de origem foi removido, continuam usando os campos embutidos como fallback.
     */
    fun resolve(item: Item): Item {
        val catalogId = item.catalogId ?: return item
        val catalog = repository.findById(catalogId).orElse(null) ?: return item
        return item.copy(
            name = catalog.name,
            desc = catalog.desc,
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
            requisitoNarrativo = catalog.requisitoNarrativo,
            onUseEffect = catalog.onUseEffect,
            goldValue = catalog.goldValue,
            margemCritico = catalog.margemCritico,
            negaCritico = catalog.negaCritico,
        )
    }

    fun resolveAll(items: List<Item>): List<Item> = items.map { resolve(it) }
}
