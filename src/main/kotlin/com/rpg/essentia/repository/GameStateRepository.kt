package com.rpg.essentia.repository

import com.rpg.essentia.model.GameState
import org.springframework.data.mongodb.repository.MongoRepository

interface GameStateRepository : MongoRepository<GameState, String>
