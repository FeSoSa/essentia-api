package com.rpg.essentia.repository

import com.rpg.essentia.model.Skill
import org.springframework.data.mongodb.repository.MongoRepository

interface SkillRepository : MongoRepository<Skill, String>
