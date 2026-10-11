package com.rpg.essentia.repository

import com.rpg.essentia.model.FastActionTemplate
import org.springframework.data.mongodb.repository.MongoRepository

interface FastActionTemplateRepository : MongoRepository<FastActionTemplate, String>
