package com.rpg.essentia.repository

import com.rpg.essentia.model.Essencia
import org.springframework.data.mongodb.repository.MongoRepository

interface EssenciaRepository : MongoRepository<Essencia, String>
