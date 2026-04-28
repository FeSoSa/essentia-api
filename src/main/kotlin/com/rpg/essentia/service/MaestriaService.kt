package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class MaestriaService(
    private val playerSkillRepository: PlayerSkillRepository,
    private val playerRepository: PlayerRepository,
    private val broadcaster: WebSocketBroadcaster
) {
    // Thresholds for total uses to reach each level
    private val levelThresholds = listOf(3, 8, 16, 28)

    fun computeLevel(totalUses: Int): Int =
        levelThresholds.count { totalUses >= it } + 1

    fun nextLevelUses(level: Int): Int =
        if (level <= 4) levelThresholds[level - 1] else Int.MAX_VALUE

    fun applyUpgrade(playerId: String, playerSkillId: String, path: String): PlayerSkill {
        if (path != "aumento" && path != "otimizacao")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path: $path")

        val playerSkill = playerSkillRepository.findById(playerSkillId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "PlayerSkill not found")
        }
        if (playerSkill.playerId != playerId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "PlayerSkill does not belong to this player")

        val currentLevel = playerSkill.maestria.level
        if (currentLevel >= 5)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Maestria is already at max level")

        val upgradeLevel = currentLevel  // upgrading FROM current level TO next
        val effectMap = buildEffectMap(upgradeLevel, path)
        val upgrade = MaestriaUpgrade(level = upgradeLevel, path = path, effect = effectMap)
        val newUpgrades = playerSkill.maestria.upgrades + upgrade
        val newComputed = recomputeComputed(newUpgrades)
        val newLevel = currentLevel + 1

        val updatedMaestria = playerSkill.maestria.copy(
            level = newLevel,
            nextLevelUses = nextLevelUses(newLevel),
            upgrades = newUpgrades,
            computed = newComputed
        )
        val saved = playerSkillRepository.save(playerSkill.copy(maestria = updatedMaestria))

        // Broadcast updated player
        playerRepository.findById(playerId).ifPresent { broadcaster.broadcastPlayer(it) }

        return saved
    }

    fun recomputeComputed(upgrades: List<MaestriaUpgrade>): MaestriaComputed {
        var damageFixedBonus = 0
        var esBonus = 0
        var costReduction = 0
        var cooldownReduction = 0
        var canUseTwice = false

        for (upgrade in upgrades) {
            when (upgrade.path) {
                "aumento" -> when (upgrade.level) {
                    1 -> { damageFixedBonus += 1; esBonus += 2 }
                    2 -> { damageFixedBonus += 2; esBonus += 3 }
                    3 -> { esBonus += 3 }           // +1d4 damage is handled dynamically in skill use
                    4 -> { damageFixedBonus += 2; esBonus += 4 }
                    5 -> { esBonus += 4 }           // +1d6 damage is handled dynamically in skill use
                }
                "otimizacao" -> when (upgrade.level) {
                    1 -> costReduction += 3
                    2 -> costReduction += 5
                    3 -> cooldownReduction += 1
                    4 -> costReduction += 8
                    5 -> canUseTwice = true
                }
            }
        }

        return MaestriaComputed(
            damageFixedBonus = damageFixedBonus,
            esBonus = esBonus,
            costReduction = costReduction,
            cooldownReduction = cooldownReduction,
            canUseTwice = canUseTwice
        )
    }

    private fun buildEffectMap(level: Int, path: String): Map<String, Any> = when (path) {
        "aumento" -> when (level) {
            1 -> mapOf("damage_fixed" to 1, "es_bonus" to 2)
            2 -> mapOf("damage_fixed" to 2, "es_bonus" to 3)
            3 -> mapOf("damage_dice" to "1d4", "es_bonus" to 3)
            4 -> mapOf("damage_fixed" to 2, "es_bonus" to 4)
            5 -> mapOf("damage_dice" to "1d6", "es_bonus" to 4)
            else -> emptyMap()
        }
        "otimizacao" -> when (level) {
            1 -> mapOf("cost_reduction" to 3)
            2 -> mapOf("cost_reduction" to 5)
            3 -> mapOf("cooldown_reduction" to 1)
            4 -> mapOf("cost_reduction" to 8)
            5 -> mapOf("can_use_twice" to true)
            else -> emptyMap()
        }
        else -> emptyMap()
    }
}
