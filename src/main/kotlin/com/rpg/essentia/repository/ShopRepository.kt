package com.rpg.essentia.repository

import com.rpg.essentia.model.Shop
import org.springframework.data.mongodb.repository.MongoRepository

interface ShopRepository : MongoRepository<Shop, String> {
    fun findByActiveTrue(): List<Shop>
}
