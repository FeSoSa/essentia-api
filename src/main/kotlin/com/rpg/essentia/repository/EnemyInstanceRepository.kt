package com.rpg.essentia.repository

import com.rpg.essentia.model.EnemyInstance
import org.springframework.data.mongodb.repository.MongoRepository

interface EnemyInstanceRepository : MongoRepository<EnemyInstance, String>
