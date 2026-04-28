package com.rpg.essentia.repository

import com.rpg.essentia.model.ItemCatalog
import org.springframework.data.mongodb.repository.MongoRepository

interface ItemCatalogRepository : MongoRepository<ItemCatalog, String>
