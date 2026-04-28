package com.rpg.essentia.repository

import com.rpg.essentia.model.BossInstance
import org.springframework.data.mongodb.repository.MongoRepository

interface BossInstanceRepository : MongoRepository<BossInstance, String>
