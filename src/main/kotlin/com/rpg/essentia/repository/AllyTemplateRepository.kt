package com.rpg.essentia.repository

import com.rpg.essentia.model.AllyTemplate
import org.springframework.data.mongodb.repository.MongoRepository

interface AllyTemplateRepository : MongoRepository<AllyTemplate, String>
