package com.rpg.essentia.repository

import com.rpg.essentia.model.EnemyAction
import org.springframework.data.mongodb.repository.MongoRepository

interface EnemyActionRepository : MongoRepository<EnemyAction, String> {
    fun findByCombatId(combatId: String): List<EnemyAction>
}
