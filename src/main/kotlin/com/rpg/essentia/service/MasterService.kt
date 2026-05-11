package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

private val MAESTRIA_THRESHOLDS = listOf(3, 8, 16, 28)
private const val XP_PER_LEVEL = 100       // nível N requer (N-1) * XP_PER_LEVEL de XP acumulado
private const val POINTS_PER_LEVEL = 5     // pontos de atributo ganhos por level-up

private fun xpThreshold(level: Int) = (level - 1) * XP_PER_LEVEL

@Service
class MasterService(
    private val playerRepository: PlayerRepository,
    private val playerSkillRepository: PlayerSkillRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster,
    private val attributeService: AttributeService,
    private val essenciaRepository: EssenciaRepository
) {
    fun getPlayers(): List<Player> = playerRepository.findAll()

    fun approveItem(playerId: String, requestId: String): Player {
        val player = loadPlayer(playerId)
        val request = player.pendingRequests.firstOrNull { it.id == requestId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found")
        val newItems = player.items.map { item ->
            if (item.id == request.itemId && item.qty > 0) item.copy(qty = item.qty - 1) else item
        }.filter { it.qty > 0 }
        val newRequests = player.pendingRequests.filter { it.id != requestId }
        return saveAndBroadcast(player.copy(items = newItems, pendingRequests = newRequests))
    }

    fun rejectItem(playerId: String, requestId: String): Player {
        val player = loadPlayer(playerId)
        val newRequests = player.pendingRequests.filter { it.id != requestId }
        return saveAndBroadcast(player.copy(pendingRequests = newRequests))
    }

    fun grantExp(playerId: String, amount: Int): Player {
        val player = loadPlayer(playerId)
        val newTotal = player.exp.total + amount
        var newLevel = player.char.level
        var newAvailable = player.exp.available
        while (newTotal >= xpThreshold(newLevel + 1)) {
            newLevel++
            newAvailable += POINTS_PER_LEVEL
        }
        val leveled = newLevel > player.char.level
        val updated = player.copy(
            exp = player.exp.copy(available = newAvailable, total = newTotal),
            char = player.char.copy(level = newLevel)
        )
        if (leveled) gameStateService.addLogEntry(playerId, "${player.char.name} subiu para nível $newLevel!", "level")
        return saveAndBroadcast(updated)
    }

    fun resetSkills(playerId: String?) {
        val players = if (playerId != null) listOf(loadPlayer(playerId)) else playerRepository.findAll()
        players.forEach { player ->
            val newSlots = player.slots.map { it.copy(cooldownRemaining = 0) }
            saveAndBroadcast(player.copy(slots = newSlots))
            playerSkillRepository.findByPlayerId(player.id)
                .filter { it.used }
                .forEach { ps -> playerSkillRepository.save(ps.copy(used = false)) }
        }
    }

    fun setInitiative(entries: List<InitiativeEntry>) {
        val state = gameStateService.getOrCreate()
        gameStateService.save(state.copy(initiative = entries, currentTurnIndex = 0, totalTurns = 0))
        broadcaster.broadcastInitiative(entries)
        // Reset desvios at combat start AND at combat end
        if (entries.isNotEmpty() || state.initiative.isNotEmpty()) {
            playerRepository.findAll().forEach { p ->
                saveAndBroadcast(p.copy(desviosRestantes = 3))
            }
        }
    }

    fun addStatusEffect(playerId: String, effect: StatusEffect): Player {
        val player = loadPlayer(playerId)
        return saveAndBroadcast(player.copy(statusEffects = player.statusEffects + effect))
    }

    fun removeStatusEffect(playerId: String, effectId: String): Player {
        val player = loadPlayer(playerId)
        val newEffects = player.statusEffects.filter { it.id != effectId }
        return saveAndBroadcast(player.copy(statusEffects = newEffects))
    }

    fun removeAllStatusEffects(playerId: String): Player {
        val player = loadPlayer(playerId)
        return saveAndBroadcast(player.copy(statusEffects = emptyList()))
    }

    fun addMaestriaUses(playerId: String, playerSkillId: String, uses: Int): PlayerSkill {
        val playerSkill = playerSkillRepository.findById(playerSkillId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "PlayerSkill not found")
        }
        if (playerSkill.playerId != playerId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "PlayerSkill does not belong to this player")

        val newTotalUses = playerSkill.maestria.totalUses + uses
        val newLevel     = minOf(MAESTRIA_THRESHOLDS.count { newTotalUses >= it } + 1, 5)
        val leveledUp    = newLevel > playerSkill.maestria.level

        val updatedMaestria = playerSkill.maestria.copy(
            totalUses     = newTotalUses,
            level         = newLevel,
            nextLevelUses = if (newLevel < 5) MAESTRIA_THRESHOLDS[newLevel - 1] else Int.MAX_VALUE
        )
        val saved = playerSkillRepository.save(playerSkill.copy(maestria = updatedMaestria))

        // Notifica jogador — sempre (progresso) e especialmente se subiu de nível (pode escolher caminho)
        playerRepository.findById(playerId).ifPresent { broadcaster.broadcastPlayer(it) }

        return saved
    }

    fun addItem(playerId: String, req: AddItemRequest): Player {
        val player = loadPlayer(playerId)
        val stackableTypes = setOf("normal", "chave", "consumable", "currency")
        if (req.type in stackableTypes) {
            val existing = player.items.find { it.name == req.name && it.type == req.type }
            if (existing != null) {
                val updated = player.items.map {
                    if (it.id == existing.id) it.copy(qty = it.qty + req.qty) else it
                }
                return saveAndBroadcast(player.copy(items = updated))
            }
        }
        val newItem = Item(
            id = java.util.UUID.randomUUID().toString(),
            name = req.name,
            desc = req.desc,
            qty = req.qty,
            type = req.type,
            icon = req.icon,
            weaponType = req.weaponType,
            damageBase = req.damageBase,
            damageDice = req.damageDice,
            damageAttribute = req.damageAttribute,
            properties = req.properties,
            damageReduction = req.damageReduction,
            armorWeight = req.armorWeight,
            attributeBonus = req.attributeBonus,
            equipSlot = req.equipSlot,
            rarity = req.rarity,
            twoHanded = req.twoHanded
        )
        val saved = saveAndBroadcast(player.copy(items = player.items + newItem))
        gameStateService.addLogEntry(playerId, "${player.char.name} recebeu ${req.name}${if (req.qty > 1) " ×${req.qty}" else ""}", "item")
        return saved
    }

    fun removeItem(playerId: String, itemId: String): Player {
        val player = loadPlayer(playerId)
        return saveAndBroadcast(player.copy(items = player.items.filter { it.id != itemId }))
    }

    fun adjustItemQty(playerId: String, itemId: String, delta: Int): Player {
        val player = loadPlayer(playerId)
        val updated = player.items.map { item ->
            if (item.id == itemId) item.copy(qty = maxOf(0, item.qty + delta)) else item
        }.filter { it.qty > 0 }
        return saveAndBroadcast(player.copy(items = updated))
    }

    private fun slotToItem(slot: String, eq: Equipment): Item? = when (slot) {
        "mainHand" -> eq.mainHand?.let { Item(id = it.id, name = it.name, type = "weapon",    equipSlot = slot, weaponType = it.weaponType, damageBase = it.damageBase, damageDice = it.damageDice, damageAttribute = it.damageAttribute, attributeBonus = it.attributeBonus) }
        "offHand"  -> eq.offHand?.let  { Item(id = it.id, name = it.name, type = "weapon",    equipSlot = slot, weaponType = it.weaponType, damageBase = it.damageBase, damageDice = it.damageDice, damageAttribute = it.damageAttribute, attributeBonus = it.attributeBonus) }
        "armor"    -> eq.armor?.let    { Item(id = it.id, name = it.name, type = "armor",     equipSlot = slot, damageReduction = it.damageReduction, armorWeight = it.armorWeight, attributeBonus = it.attributeBonus) }
        "amulet"   -> eq.amulet?.let   { Item(id = it.id, name = it.name, type = "accessory", equipSlot = slot, attributeBonus = it.attributeBonus) }
        "ring"     -> eq.ring?.let     { Item(id = it.id, name = it.name, type = "accessory", equipSlot = slot, attributeBonus = it.attributeBonus) }
        "utility"  -> eq.utility?.let  { Item(id = it.id, name = it.name, type = "accessory", equipSlot = slot, attributeBonus = it.attributeBonus) }
        else -> null
    }

    fun setEquipment(playerId: String, slot: String, req: SetEquipmentRequest): Player {
        val player = loadPlayer(playerId)
        val newItemId = req.weapon?.id ?: req.armor?.id ?: req.accessory?.id ?: ""

        // Remove new item from inventory (equipping from inventory)
        var items = if (newItemId.isNotEmpty()) player.items.filter { it.id != newItemId } else player.items

        // Return previously equipped item to inventory (slot swap)
        val previous = slotToItem(slot, player.equipment)
        if (previous != null && previous.id.isNotEmpty()) items = items + previous

        val newEquipment = when (slot) {
            "mainHand" -> player.equipment.copy(mainHand = req.weapon)
            "offHand"  -> player.equipment.copy(offHand  = req.weapon)
            "armor"    -> player.equipment.copy(armor    = req.armor)
            "amulet"   -> player.equipment.copy(amulet   = req.accessory)
            "ring"     -> player.equipment.copy(ring     = req.accessory)
            "utility"  -> player.equipment.copy(utility  = req.accessory)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot inválido: $slot")
        }
        val updated = player.copy(equipment = newEquipment, items = items)
        val effective = attributeService.computeEffectiveAttributes(updated, essenciaRepository.findAll())
        return saveAndBroadcast(attributeService.recalculateVitals(updated, effective))
    }

    fun clearEquipment(playerId: String, slot: String): Player {
        val player = loadPlayer(playerId)
        val previous = slotToItem(slot, player.equipment) ?: return player
        val gridItems = player.items.filter { it.type != "currency" }
        if (gridItems.size >= 16)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Inventário cheio")
        return setEquipment(playerId, slot, SetEquipmentRequest())
    }

    fun addCustomBar(playerId: String, bar: CustomBar): Player {
        val player = loadPlayer(playerId)
        return saveAndBroadcast(player.copy(customBars = player.customBars + bar))
    }

    fun updateCustomBar(playerId: String, barId: String, current: Int?, max: Int?): Player {
        val player = loadPlayer(playerId)
        val bars = player.customBars.map { b ->
            if (b.id != barId) b
            else b.copy(
                current = current?.coerceIn(0, max ?: b.max) ?: b.current,
                max     = max     ?: b.max
            )
        }
        return saveAndBroadcast(player.copy(customBars = bars))
    }

    fun removeCustomBar(playerId: String, barId: String): Player {
        val player = loadPlayer(playerId)
        return saveAndBroadcast(player.copy(customBars = player.customBars.filter { it.id != barId }))
    }

    fun adjustGold(playerId: String, delta: Int): Player {
        val player = loadPlayer(playerId)
        return saveAndBroadcast(player.copy(gold = maxOf(0, player.gold + delta)))
    }

    fun clearSkillFromSlots(playerId: String, skillId: String) {
        val player = loadPlayer(playerId)
        val hasSlot = player.slots.any { it.skillId == skillId }
        if (hasSlot) {
            val cleared = player.copy(
                slots = player.slots.map { slot ->
                    if (slot.skillId == skillId) slot.copy(skillId = null) else slot
                }
            )
            saveAndBroadcast(cleared)
        } else {
            broadcaster.broadcastPlayer(player)
        }
    }

    private fun loadPlayer(id: String): Player =
        playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found")
        }

    private fun saveAndBroadcast(player: Player): Player {
        val saved = playerRepository.save(player)
        broadcaster.broadcastPlayer(saved)
        return saved
    }
}
