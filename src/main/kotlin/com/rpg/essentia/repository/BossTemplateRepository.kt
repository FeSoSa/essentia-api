package com.rpg.essentia.repository

import com.rpg.essentia.model.BossTemplate
import org.springframework.data.mongodb.repository.MongoRepository

interface BossTemplateRepository : MongoRepository<BossTemplate, String>
