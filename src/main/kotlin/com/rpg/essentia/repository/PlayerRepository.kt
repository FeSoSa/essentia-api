package com.rpg.essentia.repository

import com.rpg.essentia.model.Player
import org.springframework.data.mongodb.repository.MongoRepository

interface PlayerRepository : MongoRepository<Player, String> {
    fun findByCode(code: String): Player?
}
