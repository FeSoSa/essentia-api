package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.random.Random

/** Custo de ES para ativar a Sobrecarga (debitado na aprovação). */
const val SOBRECARGA_CUSTO_ATIVACAO = 5

data class SobrecargaNivel(val nivel: Int, val bonus: Int, val custoTurno: Int, val cd: Int, val danoDado: String)

/** Tabela da Sobrecarga (espelhada no mestre e no player). custoTurno é debitado no início de cada turno do jogador. */
val SOBRECARGA_LEVELS = listOf(
    SobrecargaNivel(1, 2,  3,  6,  "2d6"),
    SobrecargaNivel(2, 4,  4,  8,  "3d6"),
    SobrecargaNivel(3, 6,  6,  10, "4d6"),
    SobrecargaNivel(4, 8,  9,  16, "5d6"),
    SobrecargaNivel(5, 10, 12, 18, "6d6"),
    SobrecargaNivel(6, 12, 18, 20, "8d6"),
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

        val lvl = SOBRECARGA_LEVELS.firstOrNull { it.nivel == nivel }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível de sobrecarga inválido: $nivel")
        if (player.flow.current < SOBRECARGA_CUSTO_ATIVACAO + lvl.custoTurno)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ES insuficiente: ativar custa $SOBRECARGA_CUSTO_ATIVACAO e o nível $nivel custa ${lvl.custoTurno} por turno")

        val cd = lvl.cd
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

        // Ativação custa ES na aprovação; o custo por turno é debitado pelo TurnService
        val updated = player.copy(
            sobrecargaAtiva = true,
            flow = player.flow.copy(current = (player.flow.current - SOBRECARGA_CUSTO_ATIVACAO).coerceAtLeast(0))
        )
        val saved = save(updated)

        val result = SobrecargaResult(approved = true, nivel = nivel)
        broadcaster.broadcastSobrecargaResult(playerId, result)
        return saved
    }

    fun rejectSobrecarga(playerId: String, roll: Int, danoDado: String): Player {
        val player = load(playerId)
        val nivel = player.sobrecargaNivel
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível de sobrecarga não definido")

        val cd = SOBRECARGA_LEVELS.firstOrNull { it.nivel == nivel }?.cd ?: 10
        val damage = rollDice(danoDado)

        // Dano da Sobrecarga também consome o Escudo primeiro
        val updated = EscudoRules.receberDano(player, damage).copy(sobrecargaNivel = null)
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
