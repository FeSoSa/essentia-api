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

        val currentLevel   = playerSkill.maestria.level
        val choicesMade    = playerSkill.maestria.upgrades.size
        val pendingChoices = (currentLevel - 1) - choicesMade  // level 1 = 0 choices needed

        if (pendingChoices <= 0)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "No pending level-up: level=$currentLevel choices=$choicesMade"
            )

        // Escolha é para o próximo nível ainda sem caminho (choices.size + 1)
        val upgradeLevel = choicesMade + 1
        val effectMap    = buildEffectMap(upgradeLevel, path)
        val upgrade      = MaestriaUpgrade(level = upgradeLevel, path = path, effect = effectMap)
        val newUpgrades  = playerSkill.maestria.upgrades + upgrade
        val newComputed  = recomputeComputed(newUpgrades)

        // Nível já foi incrementado pelo master — não altera level nem nextLevelUses
        val updatedMaestria = playerSkill.maestria.copy(
            upgrades = newUpgrades,
            computed = newComputed
        )
        val saved = playerSkillRepository.save(playerSkill.copy(maestria = updatedMaestria))

        // Broadcast updated player
        playerRepository.findById(playerId).ifPresent { broadcaster.broadcastPlayer(it) }

        return saved
    }

    // Incremento de bônus de dano por nível de aumento (índice = nível 1-5)
    private val DANO_INCREMENT = listOf(0.10, 0.10, 0.12, 0.13, 0.15)

    fun recomputeComputed(upgrades: List<MaestriaUpgrade>): MaestriaComputed {
        var bonusDano    = 0.0
        var custoAumento = 0.0
        var reducaoCusto = 0.0

        for (upgrade in upgrades) {
            when (upgrade.path) {
                "aumento"    -> {
                    bonusDano    += DANO_INCREMENT.getOrElse(upgrade.level - 1) { 0.0 }
                    custoAumento += 0.08
                }
                "otimizacao" -> reducaoCusto += 0.08
            }
        }

        return MaestriaComputed(bonusDano, custoAumento, reducaoCusto)
    }

    private fun buildEffectMap(level: Int, path: String): Map<String, Any> = when (path) {
        "aumento"    -> mapOf(
            "bonus_dano"    to DANO_INCREMENT.getOrElse(level - 1) { 0.0 },
            "custo_aumento" to 0.08
        )
        "otimizacao" -> mapOf("reducao_custo" to 0.08)
        else         -> emptyMap()
    }
}
