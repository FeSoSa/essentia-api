package com.rpg.essentia.repository

import com.rpg.essentia.model.Descoberta
import org.springframework.data.mongodb.repository.MongoRepository

interface DescobertaRepository : MongoRepository<Descoberta, String> {
    fun existsByCriaturaChaveAndTipoDanoId(criaturaChave: String, tipoDanoId: String): Boolean
}
