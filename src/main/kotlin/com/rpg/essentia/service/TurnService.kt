package com.rpg.essentia.service

import com.rpg.essentia.model.Player
import com.rpg.essentia.model.StatusEffect
import com.rpg.essentia.model.TurnUpdate
import java.util.UUID
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.stereotype.Service

@Service
class TurnService(
    private val playerRepository: PlayerRepository,
    private val skillRepository: SkillRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun nextTurn(): TurnUpdate {
        // Advance currentTurnIndex in GameState
        val state = gameStateService.getOrCreate()
        val size = state.initiative.size
        val newIndex = if (size > 0) (state.currentTurnIndex + 1) % size else 0
        val newTotal = state.totalTurns + 1
        val currentPlayerId = if (size > 0) state.initiative[newIndex].playerId else ""
        gameStateService.save(state.copy(currentTurnIndex = newIndex, totalTurns = newTotal))

        val players = playerRepository.findAll().filter { it.active }

        players.forEach { player ->
            var updated = player

            if (currentPlayerId.isNotBlank() && player.id == currentPlayerId) {
                // Auto-effects, cooldowns and effect durations only advance on this player's own turn
                updated = applyAutoEffects(updated)

                val newSlots = updated.slots.map { slot ->
                    if (slot.cooldownRemaining > 0) slot.copy(cooldownRemaining = slot.cooldownRemaining - 1)
                    else slot
                }

                val decremented = updated.statusEffects.map { effect ->
                    if (effect.durationTurns == -1) effect else effect.copy(durationTurns = effect.durationTurns - 1)
                }
                val (expiring, remaining) = decremented.partition { it.durationTurns == 0 }

                var withExpiry = updated.copy(statusEffects = remaining)
                for (expired in expiring) {
                    // Efeito toggle expirou pelo limite de turnos: desativa slot e inicia cooldown
                    if (expired.sourceSkillId != null) {
                        val tSkill = skillRepository.findById(expired.sourceSkillId).orElse(null)
                        val cooldown = tSkill?.cooldownTurns ?: 0
                        val deactivatedSlots = withExpiry.slots.map { s ->
                            if (s.skillId == expired.sourceSkillId && s.toggleActive)
                                s.copy(toggleActive = false, cooldownRemaining = cooldown)
                            else s
                        }
                        withExpiry = withExpiry.copy(slots = deactivatedSlots)
                        continue
                    }
                    expired.onExpire?.let { e ->
                        e.instantHealHp?.let   { v -> withExpiry = withExpiry.copy(hp   = withExpiry.hp.copy(current   = (withExpiry.hp.current   + v).coerceIn(0, withExpiry.hp.max))) }
                        e.instantDamageHp?.let { v -> withExpiry = withExpiry.copy(hp   = withExpiry.hp.copy(current   = (withExpiry.hp.current   - v).coerceIn(0, withExpiry.hp.max))) }
                        e.instantHealFlow?.let { v -> withExpiry = withExpiry.copy(flow = withExpiry.flow.copy(current = (withExpiry.flow.current + v).coerceIn(0, withExpiry.flow.max))) }
                        val hasBonus   = !e.attributeBonus.isNullOrEmpty()
                        val hasEffects = e.effects.isNotEmpty()
                        val hasCombat  = e.hitBonus != null || e.attackBonus != null || e.damageBonus != null
                        if (hasBonus || hasEffects || hasCombat) {
                            val newEffect = StatusEffect(
                                id = UUID.randomUUID().toString(),
                                name = e.name, desc = e.desc,
                                durationTurns = e.durationTurns,
                                icon = e.icon, color = e.color,
                                attributeBonus = e.attributeBonus,
                                effects = e.effects,
                                hitBonus = e.hitBonus,
                                attackBonus = e.attackBonus,
                                damageBonus = e.damageBonus
                            )
                            withExpiry = withExpiry.copy(statusEffects = withExpiry.statusEffects + newEffect)
                        }
                    }
                }
                updated = withExpiry.copy(slots = newSlots)

                // Debitar custo por turno das habilidades toggle ativas
                for (tSlot in updated.slots.filter { it.toggleActive && it.skillId != null }) {
                    val tSkill = skillRepository.findById(tSlot.skillId!!).orElse(null) ?: continue
                    val costMap = mutableMapOf<String, Int>()
                    for (cost in tSkill.costs) {
                        val base = when (cost.type) {
                            "flow", "ether", "hp" -> cost.value ?: 0
                            "percentual_flow"     -> (updated.flow.max * (cost.percentual ?: 0)) / 100
                            "percentual_hp"       -> (updated.hp.max  * (cost.percentual ?: 0)) / 100
                            else                  -> 0
                        }
                        if (base > 0) costMap[cost.type] = base
                    }
                    val flowCost  = (costMap["flow"] ?: 0) + (costMap["percentual_flow"] ?: 0)
                    val hpCost    = costMap["hp"] ?: 0
                    val etherCost = costMap["ether"] ?: 0
                    val sufficient = updated.flow.current >= flowCost &&
                                     updated.hp.current >= hpCost &&
                                     (etherCost == 0 || (updated.ether.unlocked && updated.ether.current >= etherCost))
                    if (!sufficient) {
                        // Recursos insuficientes: desativar o toggle
                        val deactivatedSlots = updated.slots.map { s ->
                            if (s.id == tSlot.id) s.copy(toggleActive = false) else s
                        }
                        updated = updated.copy(
                            slots = deactivatedSlots,
                            statusEffects = updated.statusEffects.filterNot { it.sourceSkillId == tSlot.skillId }
                        )
                    } else {
                        costMap["flow"]?.let { c -> updated = updated.copy(flow = updated.flow.copy(current = (updated.flow.current - c).coerceAtLeast(0))) }
                        costMap["percentual_flow"]?.let { c -> updated = updated.copy(flow = updated.flow.copy(current = (updated.flow.current - c).coerceAtLeast(0))) }
                        costMap["hp"]?.let { c -> updated = updated.copy(hp = updated.hp.copy(current = (updated.hp.current - c).coerceAtLeast(0))) }
                        costMap["ether"]?.let { c -> updated = updated.copy(ether = updated.ether.copy(current = (updated.ether.current - c).coerceAtLeast(0))) }
                    }
                }
            }

            val saved = playerRepository.save(updated)
            broadcaster.broadcastPlayer(saved)
        }

        val update = TurnUpdate(message = "Next turn started", currentTurnIndex = newIndex, totalTurns = newTotal)
        broadcaster.broadcastTurn(update)
        return update
    }

    private fun applyAutoEffects(player: Player): Player {
        var updated = player
        for (effect in player.statusEffects.filter { it.durationTurns != 0 }) {
            for (auto in effect.effects.filter { it.trigger == "on_turn_start" }) {
                val value = auto.value ?: 0
                updated = when (auto.type) {
                    "damage_hp"   -> updated.copy(hp    = updated.hp.copy(current    = (updated.hp.current    - value).coerceIn(0, updated.hp.max)))
                    "heal_hp"     -> updated.copy(hp    = updated.hp.copy(current    = (updated.hp.current    + value).coerceIn(0, updated.hp.max)))
                    "damage_flow" -> updated.copy(flow  = updated.flow.copy(current  = (updated.flow.current  - value).coerceIn(0, updated.flow.max)))
                    "heal_flow"   -> updated.copy(flow  = updated.flow.copy(current  = (updated.flow.current  + value).coerceIn(0, updated.flow.max)))
                    "damage_ether" -> updated.copy(ether = updated.ether.copy(current = (updated.ether.current - value).coerceIn(0, updated.ether.max)))
                    "heal_ether"   -> updated.copy(ether = updated.ether.copy(current = (updated.ether.current + value).coerceIn(0, updated.ether.max)))
                    else -> updated
                }
            }
        }
        return updated
    }
}
