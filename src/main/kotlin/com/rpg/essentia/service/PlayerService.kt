package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.BossInstanceRepository
import com.rpg.essentia.repository.EnemyInstanceRepository
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

private val VALID_ATTRIBUTES = setOf(
    "strength", "agility", "intelligence", "resistance", "flow", "wisdom", "presence", "defense"
)

@Service
class PlayerService(
    private val playerRepository: PlayerRepository,
    private val attributeService: AttributeService,
    private val essenciaRepository: EssenciaRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster,
    private val enemyRepository: EnemyInstanceRepository,
    private val bossRepository: BossInstanceRepository,
) {
    private fun combatActive(): Boolean =
        enemyRepository.count() > 0 || bossRepository.count() > 0

    fun load(id: String): Player =
        playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found")
        }

    fun adjustHp(id: String, delta: Int): Player {
        val player = load(id)
        val newCurrent = (player.hp.current + delta).coerceIn(0, player.hp.max)
        val actual = newCurrent - player.hp.current
        if (actual != 0)
            gameStateService.addLogEntry(id, "${player.char.name} HP ${if (actual > 0) "+$actual" else "$actual"}", "hp")
        return saveAndBroadcast(player.copy(hp = player.hp.copy(current = newCurrent)))
    }

    fun adjustFlow(id: String, delta: Int): Player {
        val player = load(id)
        val newCurrent = (player.flow.current + delta).coerceIn(0, player.flow.max)
        val actual = newCurrent - player.flow.current
        if (actual != 0)
            gameStateService.addLogEntry(id, "${player.char.name} ES ${if (actual > 0) "+$actual" else "$actual"}", "flow")
        return saveAndBroadcast(player.copy(flow = player.flow.copy(current = newCurrent)))
    }

    fun adjustEther(id: String, delta: Int): Player {
        val player = load(id)
        val newCurrent = (player.ether.current + delta).coerceIn(0, player.ether.max)
        val actual = newCurrent - player.ether.current
        if (actual != 0)
            gameStateService.addLogEntry(id, "${player.char.name} Éter ${if (actual > 0) "+$actual" else "$actual"}", "ether")
        return saveAndBroadcast(player.copy(ether = player.ether.copy(current = newCurrent)))
    }

    fun adjustPressao(id: String, delta: Int): Player {
        val player = load(id)
        val p = player.pressao ?: return player
        val newCurrent = (p.current + delta).coerceIn(0, p.max)
        return saveAndBroadcast(player.copy(pressao = p.copy(current = newCurrent)))
    }

    fun adjustAttribute(id: String, attribute: String, delta: Int): Player {
        if (attribute !in VALID_ATTRIBUTES)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attribute: $attribute")
        if (delta <= 0)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Delta must be positive")

        val player = load(id)
        if (player.exp.available < delta)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Insufficient exp (need $delta, have ${player.exp.available})"
            )

        val attrMap = player.attributes.toMap().toMutableMap()
        attrMap[attribute] = (attrMap[attribute] ?: 0) + delta
        var updated = player.copy(
            attributes = attrMap.toAttributes(),
            exp = player.exp.copy(available = player.exp.available - delta)
        )

        // Recalculate vitals when resistance or flow changes
        if (attribute == "resistance" || attribute == "flow") {
            val essencias = essenciaRepository.findAll()
            val effective = attributeService.computeEffectiveAttributes(updated, essencias)
            updated = attributeService.recalculateVitals(updated, effective)
        }

        // Sempre recalcula éter max quando desbloqueado (min(floor(SAB/4), 10))
        if (updated.ether.unlocked) {
            val newEtherMax = minOf(updated.attributes.wisdom / 4, 10)
            updated = updated.copy(
                ether = updated.ether.copy(
                    max     = newEtherMax,
                    current = updated.ether.current.coerceAtMost(newEtherMax)
                )
            )
        }

        gameStateService.addLogEntry(id, "${player.char.name} aumentou $attribute +$delta", "attr")
        return saveAndBroadcast(updated)
    }

    fun updateSlot(id: String, slotId: String, skillId: String?): Player {
        if (combatActive())
            throw ResponseStatusException(HttpStatus.CONFLICT, "Não é possível alterar habilidades durante o combate.")
        val player = load(id)
        val newSlots = player.slots.map { slot ->
            if (slot.id == slotId) slot.copy(skillId = skillId) else slot
        }
        return saveAndBroadcast(player.copy(slots = newSlots))
    }

    fun requestItem(id: String, itemId: String): Player {
        val player = load(id)
        player.items.firstOrNull { it.id == itemId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found")
        val request = PendingRequest(
            id = UUID.randomUUID().toString(),
            type = "use_item",
            itemId = itemId,
            timestamp = Instant.now().toString()
        )
        val saved = saveAndBroadcast(player.copy(pendingRequests = player.pendingRequests + request))
        broadcaster.broadcastPlayers(playerRepository.findAll())
        return saved
    }

    fun equipFromInventory(id: String, itemId: String): Player {
        val player = load(id)
        val item = player.items.firstOrNull { it.id == itemId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item não encontrado no inventário")
        val slot = item.equipSlot
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Item não tem slot de equipamento")

        val previousItem: Item? = when (slot) {
            "mainHand" -> player.equipment.mainHand?.let {
                Item(id = it.id, name = it.name, type = "weapon", equipSlot = slot,
                    weaponType = it.weaponType, damageBase = it.damageBase, damageDice = it.damageDice,
                    damageAttribute = it.damageAttribute, attributeBonus = it.attributeBonus)
            }
            "offHand" -> player.equipment.offHand?.let {
                Item(id = it.id, name = it.name, type = "weapon", equipSlot = slot,
                    weaponType = it.weaponType, damageBase = it.damageBase, damageDice = it.damageDice,
                    damageAttribute = it.damageAttribute, attributeBonus = it.attributeBonus)
            }
            "armor" -> player.equipment.armor?.let {
                Item(id = it.id, name = it.name, type = "armor", equipSlot = slot,
                    damageReduction = it.damageReduction, attributeBonus = it.attributeBonus)
            }
            "amulet" -> player.equipment.amulet?.let {
                Item(id = it.id, name = it.name, type = "accessory", equipSlot = slot,
                    attributeBonus = it.attributeBonus)
            }
            "ring" -> player.equipment.ring?.let {
                Item(id = it.id, name = it.name, type = "accessory", equipSlot = slot,
                    attributeBonus = it.attributeBonus)
            }
            "utility" -> player.equipment.utility?.let {
                Item(id = it.id, name = it.name, type = "accessory", equipSlot = slot,
                    attributeBonus = it.attributeBonus)
            }
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot inválido: $slot")
        }

        val newEquipment = when (slot) {
            "mainHand" -> player.equipment.copy(mainHand = WeaponEquip(
                id = item.id, name = item.name, weaponType = item.weaponType ?: "",
                damageBase = item.damageBase ?: 0, damageDice = item.damageDice ?: Dice(1, "d6"),
                damageAttribute = item.damageAttribute ?: "", attributeBonus = item.attributeBonus))
            "offHand" -> player.equipment.copy(offHand = WeaponEquip(
                id = item.id, name = item.name, weaponType = item.weaponType ?: "",
                damageBase = item.damageBase ?: 0, damageDice = item.damageDice ?: Dice(1, "d6"),
                damageAttribute = item.damageAttribute ?: "", attributeBonus = item.attributeBonus))
            "armor" -> player.equipment.copy(armor = ArmorEquip(
                id = item.id, name = item.name,
                damageReduction = item.damageReduction ?: 0, attributeBonus = item.attributeBonus))
            "amulet" -> player.equipment.copy(amulet = AccessoryEquip(
                id = item.id, name = item.name, attributeBonus = item.attributeBonus))
            "ring" -> player.equipment.copy(ring = AccessoryEquip(
                id = item.id, name = item.name, attributeBonus = item.attributeBonus))
            "utility" -> player.equipment.copy(utility = AccessoryEquip(
                id = item.id, name = item.name, attributeBonus = item.attributeBonus))
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot inválido: $slot")
        }

        var items = player.items.filter { it.id != itemId }
        if (previousItem != null) items = items + previousItem

        val updated = player.copy(equipment = newEquipment, items = items)
        val essencias = essenciaRepository.findAll()
        val effective = attributeService.computeEffectiveAttributes(updated, essencias)
        return saveAndBroadcast(attributeService.recalculateVitals(updated, effective))
    }

    fun unequipItem(id: String, slot: String): Player {
        val player = load(id)
        val nonCurrencyCount = player.items.count { it.type != "currency" }
        if (nonCurrencyCount >= 16)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Inventário cheio")

        val previousItem: Item = when (slot) {
            "mainHand" -> player.equipment.mainHand?.let {
                Item(id = it.id, name = it.name, type = "weapon", equipSlot = slot,
                    weaponType = it.weaponType, damageBase = it.damageBase, damageDice = it.damageDice,
                    damageAttribute = it.damageAttribute, attributeBonus = it.attributeBonus)
            }
            "offHand" -> player.equipment.offHand?.let {
                Item(id = it.id, name = it.name, type = "weapon", equipSlot = slot,
                    weaponType = it.weaponType, damageBase = it.damageBase, damageDice = it.damageDice,
                    damageAttribute = it.damageAttribute, attributeBonus = it.attributeBonus)
            }
            "armor" -> player.equipment.armor?.let {
                Item(id = it.id, name = it.name, type = "armor", equipSlot = slot,
                    damageReduction = it.damageReduction, attributeBonus = it.attributeBonus)
            }
            "amulet" -> player.equipment.amulet?.let {
                Item(id = it.id, name = it.name, type = "accessory", equipSlot = slot,
                    attributeBonus = it.attributeBonus)
            }
            "ring" -> player.equipment.ring?.let {
                Item(id = it.id, name = it.name, type = "accessory", equipSlot = slot,
                    attributeBonus = it.attributeBonus)
            }
            "utility" -> player.equipment.utility?.let {
                Item(id = it.id, name = it.name, type = "accessory", equipSlot = slot,
                    attributeBonus = it.attributeBonus)
            }
            else -> null
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Slot vazio ou inválido: $slot")

        val newEquipment = when (slot) {
            "mainHand" -> player.equipment.copy(mainHand = null)
            "offHand"  -> player.equipment.copy(offHand = null)
            "armor"    -> player.equipment.copy(armor = null)
            "amulet"   -> player.equipment.copy(amulet = null)
            "ring"     -> player.equipment.copy(ring = null)
            "utility"  -> player.equipment.copy(utility = null)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot inválido: $slot")
        }

        val updated = player.copy(equipment = newEquipment, items = player.items + previousItem)
        val essencias = essenciaRepository.findAll()
        val effective = attributeService.computeEffectiveAttributes(updated, essencias)
        return saveAndBroadcast(attributeService.recalculateVitals(updated, effective))
    }

    fun recalculate(id: String): Player {
        val player = load(id)
        val essencias = essenciaRepository.findAll()
        val effective = attributeService.computeEffectiveAttributes(player, essencias)
        var updated = attributeService.recalculateVitals(player, effective)
        if (updated.ether.unlocked) {
            val newEtherMax = minOf(updated.attributes.wisdom / 4, 10)
            updated = updated.copy(
                ether = updated.ether.copy(
                    max     = newEtherMax,
                    current = updated.ether.current.coerceAtMost(newEtherMax)
                )
            )
        }
        return saveAndBroadcast(updated)
    }

    fun executeDesvio(id: String): Player {
        val player = load(id)
        if (player.desviosRestantes <= 0)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Sem desvios restantes")
        return saveAndBroadcast(player.copy(desviosRestantes = player.desviosRestantes - 1))
    }

    fun saveAndBroadcast(player: Player): Player {
        val saved = playerRepository.save(player)
        broadcaster.broadcastPlayer(saved)
        return saved
    }
}
