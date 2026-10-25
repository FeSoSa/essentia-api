package com.rpg.essentia.repository

import com.rpg.essentia.model.Tabuleiro
import org.springframework.data.mongodb.repository.MongoRepository

interface TabuleiroRepository : MongoRepository<Tabuleiro, String>
