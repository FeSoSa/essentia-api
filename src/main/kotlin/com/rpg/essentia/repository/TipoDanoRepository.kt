package com.rpg.essentia.repository

import com.rpg.essentia.model.TipoDano
import org.springframework.data.mongodb.repository.MongoRepository

interface TipoDanoRepository : MongoRepository<TipoDano, String>
