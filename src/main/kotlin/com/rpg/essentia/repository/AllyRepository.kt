package com.rpg.essentia.repository

import com.rpg.essentia.model.CombatAlly
import org.springframework.data.mongodb.repository.MongoRepository

interface AllyRepository : MongoRepository<CombatAlly, String>
