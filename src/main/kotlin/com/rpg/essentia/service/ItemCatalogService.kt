package com.rpg.essentia.service

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
}
