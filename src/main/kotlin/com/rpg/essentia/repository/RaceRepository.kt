package com.rpg.essentia.repository

import com.rpg.essentia.model.Race
import org.springframework.data.mongodb.repository.MongoRepository

interface RaceRepository : MongoRepository<Race, String> {
    fun findByName(name: String): Race?
}
