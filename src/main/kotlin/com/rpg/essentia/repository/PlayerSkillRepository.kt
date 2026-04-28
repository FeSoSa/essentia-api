package com.rpg.essentia.repository

import com.rpg.essentia.model.PlayerSkill
import org.springframework.data.mongodb.repository.MongoRepository

interface PlayerSkillRepository : MongoRepository<PlayerSkill, String> {
    fun findByPlayerId(playerId: String): List<PlayerSkill>
    fun findByPlayerIdAndSlotId(playerId: String, slotId: String): PlayerSkill?
    fun findByPlayerIdAndSkillId(playerId: String, skillId: String): PlayerSkill?
}
