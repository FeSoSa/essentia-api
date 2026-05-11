package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.random.Random

private val SOBRECARGA_LEVELS = listOf(
    mapOf("nivel" to 1, "bonus" to 2,  "custo" to 60,  "cd" to 10, "danoDado" to "2d6"),
    mapOf("nivel" to 2, "bonus" to 4,  "custo" to 90,  "cd" to 12, "danoDado" to "3d6"),
    mapOf("nivel" to 3, "bonus" to 6,  "custo" to 125, "cd" to 14, "danoDado" to "4d6"),
    mapOf("nivel" to 4, "bonus" to 8,  "custo" to 165, "cd" to 16, "danoDado" to "5d6"),
    mapOf("nivel" to 5, "bonus" to 10, "custo" to 210, "cd" to 18, "danoDado" to "6d6"),
    mapOf("nivel" to 6, "bonus" to 12, "custo" to 260, "cd" to 20, "danoDado" to "8d6"),
)

@Service
class SobrecargaService(
    private val playerRepository: PlayerRepository,
    private val broadcaster: WebSocketBroadcaster
) {
    fun requestSobrecarga(playerId: String, nivel: Int): Player {
        val player = load(playerId)
        if (!player.sobrecargaDesbloqueada)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Sobrecarga não desbloqueada")
        if (player.sobrecargaAtiva)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Sobrecarga já está ativa")

        val lvl = SOBRECARGA_LEVELS.firstOrNull { it["nivel"] == nivel }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível de sobrecarga inválido: $nivel")

        val cd = lvl["cd"] as Int
        val sabMod = wisdomMod(player.attributes.wisdom)

        val broadcast = SobrecargaBroadcast(
            playerId = playerId,
            playerName = player.char.name,
            nivel = nivel,
            cd = cd,
            sabMod = sabMod
        )
        broadcaster.broadcastSobrecargaRequest(broadcast)
        return player
    }

    fun approveSobrecarga(playerId: String): Player {
        val player = load(playerId)
        val nivel = player.sobrecargaNivel
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível de sobrecarga não definido")

        val updated = player.copy(sobrecargaAtiva = true)
        val saved = save(updated)

        val result = SobrecargaResult(approved = true, nivel = nivel)
        broadcaster.broadcastSobrecargaResult(playerId, result)
        return saved
    }

    fun rejectSobrecarga(playerId: String, roll: Int, danoDado: String): Player {
        val player = load(playerId)
        val nivel = player.sobrecargaNivel
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível de sobrecarga não definido")

        val lvl = SOBRECARGA_LEVELS.firstOrNull { it["nivel"] == nivel }
        val cd = (lvl?.get("cd") as? Int) ?: 10
        val damage = rollDice(danoDado)

        val newHp = maxOf(0, player.hp.current - damage)
        val updated = player.copy(
            hp = player.hp.copy(current = newHp),
            sobrecargaNivel = null
        )
        val saved = save(updated)

        val result = SobrecargaResult(
            approved = false,
            nivel = nivel,
            roll = roll,
            cd = cd,
            damageTaken = damage
        )
        broadcaster.broadcastSobrecargaResult(playerId, result)
        return saved
    }

    fun setPendingNivel(playerId: String, nivel: Int): Player {
        val player = load(playerId)
        val updated = player.copy(sobrecargaNivel = nivel)
        return save(updated)
    }

    fun deactivateSobrecarga(playerId: String): Player {
        val player = load(playerId)
        val updated = player.copy(sobrecargaAtiva = false, sobrecargaNivel = null)
        return save(updated)
    }

    private fun wisdomMod(wisdom: Int): Int = when {
        wisdom <= 7  -> -1
        wisdom <= 11 -> 0
        wisdom <= 15 -> 1
        wisdom <= 19 -> 2
        wisdom <= 23 -> 3
        wisdom <= 27 -> 4
        wisdom <= 31 -> 5
        wisdom <= 35 -> 6
        wisdom <= 40 -> 7
        else         -> 8
    }

    private fun rollDice(formula: String): Int {
        val m = Regex("(\\d+)d(\\d+)").find(formula) ?: return 0
        val qty = m.groupValues[1].toInt()
        val sides = m.groupValues[2].toInt()
        return (1..qty).sumOf { Random.nextInt(1, sides + 1) }
    }

    private fun load(id: String): Player =
        playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado: $id")
        }

    private fun save(player: Player): Player {
        val saved = playerRepository.save(player)
        broadcaster.broadcastPlayer(saved)
        return saved
    }
}
