package com.rpg.essentia.repository

import com.rpg.essentia.model.EnemyTemplate
import org.springframework.data.mongodb.repository.MongoRepository

interface EnemyTemplateRepository : MongoRepository<EnemyTemplate, String>
