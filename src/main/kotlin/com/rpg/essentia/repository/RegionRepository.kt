package com.rpg.essentia.repository

import com.rpg.essentia.model.Region
import org.springframework.data.mongodb.repository.MongoRepository

interface RegionRepository : MongoRepository<Region, String>
