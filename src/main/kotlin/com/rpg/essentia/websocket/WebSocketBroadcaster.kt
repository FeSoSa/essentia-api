package com.rpg.essentia.websocket

import com.rpg.essentia.model.*
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

@Component
class WebSocketBroadcaster(private val messagingTemplate: SimpMessagingTemplate) {

    fun broadcastImage(image: GameImage) =
        messagingTemplate.convertAndSend("/topic/image", image)

    fun broadcastImages(images: List<GameImage>) =
        messagingTemplate.convertAndSend("/topic/images", images)

    fun broadcastFastAction(fastAction: FastAction) =
        messagingTemplate.convertAndSend("/topic/fast-action", fastAction)

    fun broadcastInitiative(entries: List<InitiativeEntry>) =
        messagingTemplate.convertAndSend("/topic/initiative", entries)

    fun broadcastLog(entry: LogEntry) =
        messagingTemplate.convertAndSend("/topic/log", entry)

    fun broadcastPlayer(player: Player) =
        messagingTemplate.convertAndSend("/topic/player/${player.id}", player)

    fun broadcastTurn(update: TurnUpdate) =
        messagingTemplate.convertAndSend("/topic/turn", update)

    fun broadcastPlayers(players: List<Player>) =
        messagingTemplate.convertAndSend("/topic/players", players)

    fun broadcastCombatEnemies(enemies: List<EnemyInstance>) =
        messagingTemplate.convertAndSend("/topic/combat/enemies", enemies)

    fun broadcastCombatBosses(bosses: List<BossInstance>) =
        messagingTemplate.convertAndSend("/topic/combat/bosses", bosses)

    fun broadcastCollectiveBars(bars: List<CollectiveBar>) =
        messagingTemplate.convertAndSend("/topic/collective-bars", bars)

    fun broadcastSobrecargaRequest(req: SobrecargaBroadcast) =
        messagingTemplate.convertAndSend("/topic/sobrecarga-request", req)

    fun broadcastSobrecargaResult(playerId: String, result: SobrecargaResult) =
        messagingTemplate.convertAndSend("/topic/player/$playerId/sobrecarga-result", result)

    fun broadcastDamageRequest(req: DamageApprovalRequest) =
        messagingTemplate.convertAndSend("/topic/damage-request", req)

    fun broadcastDamageResult(playerId: String, result: DamageResultNotification) =
        messagingTemplate.convertAndSend("/topic/player/$playerId/damage-result", result)
}
