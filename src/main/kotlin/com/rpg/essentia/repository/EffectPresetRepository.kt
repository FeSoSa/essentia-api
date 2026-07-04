package com.rpg.essentia.repository

import com.rpg.essentia.model.EffectPreset
import org.springframework.data.mongodb.repository.MongoRepository

interface EffectPresetRepository : MongoRepository<EffectPreset, String>
