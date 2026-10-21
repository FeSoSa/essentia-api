package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

private const val POINTS_PER_LEVEL = 3

private fun xpParaNivel(n: Int): Int {
    if (n <= 1) return 0
    if (n <= 20) return 75 * (n * (n + 1) / 2 - 1) - 50 * (n - 1)
    val m = n - 20
    return 14725 + m * (2750 + 150 * m)
}

private fun nivelParaXp(xp: Int): Int {
    var nivel = 1
    while (xpParaNivel(nivel + 1) <= xp) nivel++
    return nivel
}

@Service
class MasterService(
    private val playerRepository: PlayerRepository,
    private val playerSkillRepository: PlayerSkillRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster,
    private val attributeService: AttributeService,
    private val essenciaRepository: EssenciaRepository,
    private val itemCatalogService: ItemCatalogService
) {
    fun getPlayers(): List<Player> = playerRepository.findAll().map { resolveItems(it) }

    private fun resolveItems(player: Player): Player =
        player.copy(items = itemCatalogService.resolveAll(player.items))

    fun approveItem(playerId: String, requestId: String): Player {
        val player = loadPlayer(playerId)
        val request = player.pendingRequests.firstOrNull { it.id == requestId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found")
        val usedItem = player.items.find { it.id == request.itemId }
        val newItems = player.items.map { item ->
            if (item.id == request.itemId && item.qty > 0) item.copy(qty = item.qty - 1) else item
        }.filter { it.qty > 0 }
        val newRequests = player.pendingRequests.filter { it.id != requestId }
        var updated = player.copy(items = newItems, pendingRequests = newRequests)

        usedItem?.onUseEffect?.let { e ->
            e.instantHealHp?.let { v ->
                updated = updated.copy(hp = updated.hp.copy(current = (updated.hp.current + v).coerceIn(0, updated.hp.max)))
            }
            e.instantHealFlow?.let { v ->
                updated = updated.copy(flow = updated.flow.copy(current = (updated.flow.current + v).coerceIn(0, updated.flow.max)))
            }
            val hasBonus = !e.attributeBonus.isNullOrEmpty()
            val hasEffects = e.effects.isNotEmpty()
            if (hasBonus || hasEffects) {
                val statusEffect = StatusEffect(
                    id = java.util.UUID.randomUUID().toString(),
                    name = e.name,
                    desc = e.desc,
                    durationTurns = e.durationTurns,
                    icon = e.icon,
                    color = e.color,
                    attributeBonus = e.attributeBonus,
                    effects = e.effects,
                    hitBonus = e.hitBonus,
                    attackBonus = e.attackBonus,
                    damageBonus = e.damageBonus,
                    onExpire = e.onExpire
                )
                updated = updated.copy(statusEffects = updated.statusEffects.applyOrStack(statusEffect))
                updated = recalculateEffective(updated)
            }
        }

        return saveAndBroadcast(updated)
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
        newLevel = nivelParaXp(newTotal)
        val levelDiff = newLevel - player.char.level
        newAvailable += levelDiff * POINTS_PER_LEVEL
        val leveled = newLevel > player.char.level
        var updated = player.copy(
            exp = player.exp.copy(available = newAvailable, total = newTotal),
            char = player.char.copy(level = newLevel)
        )
        if (levelDiff != 0) {
            // Bônus de PV/Fluxo da classe por nível: ao subir, o atual soma o mesmo que o máximo ganhou
            val effective = attributeService.computeEffectiveAttributes(updated, essenciaRepository.findAll())
            updated = attributeService.recalculateVitals(updated, effective, somarGanho = leveled)
        }
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

    /**
     * Coloca um jogador na luta sem reiniciar o combate: entra na posição da iniciativa dele,
     * quem está na vez continua na vez e a contagem de rodadas é mantida.
     */
    fun entrarNaLuta(playerId: String, valor: Int): List<InitiativeEntry> {
        val state = gameStateService.getOrCreate()
        if (state.initiative.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Não há combate em andamento")
        if (state.initiative.any { it.playerId == playerId }) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Jogador já está na luta")
        val player = playerRepository.findById(playerId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado") }

        val pos = state.initiative.indexOfFirst { it.value < valor }.let { if (it < 0) state.initiative.size else it }
        val nova = state.initiative.toMutableList().apply { add(pos, InitiativeEntry(playerId, player.char.name, valor)) }
        val atual = state.currentTurnIndex.coerceIn(0, state.initiative.size - 1)
        val novoIndice = if (pos <= atual) atual + 1 else atual
        salvarOrdem(state, nova, novoIndice)
        saveAndBroadcast(player.copy(desviosRestantes = state.combateConfig.desviosPorCombate))
        gameStateService.addLogEntry(playerId, "${player.char.name} entrou na luta (iniciativa $valor)", "info")
        return nova
    }

    /** Tira um jogador da luta sem reiniciar o combate. Se era a vez dele, a vez passa para o próximo. */
    fun sairDaLuta(playerId: String): List<InitiativeEntry> {
        val state = gameStateService.getOrCreate()
        val pos = state.initiative.indexOfFirst { it.playerId == playerId }
        if (pos < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Jogador não está na luta")
        val nova = state.initiative.toMutableList().apply { removeAt(pos) }
        if (nova.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "É o último na luta: encerre o combate")
        val atual = state.currentTurnIndex.coerceIn(0, state.initiative.size - 1)
        val novoIndice = (if (pos < atual) atual - 1 else atual) % nova.size
        salvarOrdem(state, nova, novoIndice)
        playerRepository.findById(playerId).ifPresent { gameStateService.addLogEntry(playerId, "${it.char.name} saiu da luta", "info") }
        return nova
    }

    /** Salva a ordem nova mantendo a rodada: o total de turnos continua batendo com o índice (total % tamanho). */
    private fun salvarOrdem(state: GameState, nova: List<InitiativeEntry>, novoIndice: Int) {
        val rodada = if (state.initiative.isEmpty()) 0 else state.totalTurns / state.initiative.size
        gameStateService.save(state.copy(initiative = nova, currentTurnIndex = novoIndice, totalTurns = rodada * nova.size + novoIndice))
        broadcaster.broadcastInitiative(nova)
    }

    fun setInitiative(entries: List<InitiativeEntry>) {
        val state = gameStateService.getOrCreate()
        // combatId: novo ao iniciar combate, mantido ao editar a iniciativa, null ao encerrar
        val combatId = when {
            entries.isEmpty()          -> null
            state.combatId != null     -> state.combatId
            else                       -> java.util.UUID.randomUUID().toString()
        }
        gameStateService.save(state.copy(initiative = entries, currentTurnIndex = 0, totalTurns = 0, combatId = combatId))
        broadcaster.broadcastInitiative(entries)
        val desvios = state.combateConfig.desviosPorCombate
        // Reset desvios at combat start AND at combat end; reset cooldowns at combat end
        if (entries.isNotEmpty() || state.initiative.isNotEmpty()) {
            playerRepository.findAll().forEach { p ->
                val resetSlots = if (entries.isEmpty())
                    p.slots.map { it.copy(cooldownRemaining = 0) }
                else
                    p.slots
                saveAndBroadcast(p.copy(desviosRestantes = desvios, slots = resetSlots))
            }
        }
    }

    fun addStatusEffect(playerId: String, effect: StatusEffect): Player {
        val player = loadPlayer(playerId)
        val updated = player.copy(statusEffects = player.statusEffects.applyOrStack(effect))
        return saveAndBroadcast(recalculateEffective(updated))
    }

    fun removeStatusEffect(playerId: String, effectId: String): Player {
        val player = loadPlayer(playerId)
        val newEffects = player.statusEffects.filter { it.id != effectId }
        val updated = player.copy(statusEffects = newEffects)
        return saveAndBroadcast(recalculateEffective(updated))
    }

    fun removeAllStatusEffects(playerId: String): Player {
        val player = loadPlayer(playerId)
        val updated = player.copy(statusEffects = emptyList())
        return saveAndBroadcast(recalculateEffective(updated))
    }

    /** Recalcula effectiveAttributes/vitais para refletir status effects recém-adicionados/removidos. */
    private fun recalculateEffective(player: Player): Player {
        val essencias = essenciaRepository.findAll()
        val effective = attributeService.computeEffectiveAttributes(player, essencias)
        return attributeService.recalculateVitals(player, effective)
    }

    fun setSkillBlocked(playerId: String, skillId: String, blocked: Boolean): PlayerSkill {
        val playerSkill = playerSkillRepository.findByPlayerIdAndSkillId(playerId, skillId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "PlayerSkill not found")

        val saved = playerSkillRepository.save(playerSkill.copy(blocked = blocked))
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
            catalogId = req.catalogId,
            name = req.name,
            desc = req.desc,
            qty = req.qty,
            type = req.type,
            icon = req.icon,
            weaponType = req.weaponType,
            damageBase = req.damageBase,
            damageAttribute = req.damageAttribute,
            equilibrio = req.equilibrio,
            properties = req.properties,
            damageReduction = req.damageReduction,
            armorWeight = req.armorWeight,
            attributeBonus = req.attributeBonus,
            equipSlot = req.equipSlot,
            rarity = req.rarity,
            twoHanded = req.twoHanded,
            requirements = req.requirements,
            requisitoNarrativo = req.requisitoNarrativo,
            onUseEffect = req.onUseEffect,
            goldValue = req.goldValue,
            margemCritico = req.margemCritico,
            negaCritico = req.negaCritico
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

    private fun slotToItem(slot: String, eq: Equipment): Item? = eq.slotToItem(slot)

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
        if (gridItems.size >= player.inventorySize)
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

    /** Ouro de uma derrota: para um jogador ou dividido entre os ativos (a sobra vai para os primeiros). */
    fun entregarOuro(gold: GoldAssignment?) {
        if (gold == null || gold.amount <= 0) return
        if (!gold.distribute) {
            if (gold.playerId.isNotBlank()) adjustGold(gold.playerId, gold.amount)
            return
        }
        val ativos = playerRepository.findAll().filter { it.active }
        if (ativos.isEmpty()) return
        val parte = gold.amount / ativos.size
        val sobra = gold.amount % ativos.size
        ativos.forEachIndexed { i, p ->
            val valor = parte + if (i < sobra) 1 else 0
            if (valor > 0) adjustGold(p.id, valor)
        }
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

    fun toggleActive(id: String): Player {
        val player = loadPlayer(id)
        val updated = player.copy(active = !player.active)
        val saved = resolveItems(playerRepository.save(updated))
        broadcaster.broadcastPlayer(saved)
        broadcaster.broadcastPlayers(playerRepository.findAll().map { resolveItems(it) })
        return saved
    }

    private fun loadPlayer(id: String): Player =
        playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found")
        }

    private fun saveAndBroadcast(player: Player): Player {
        val saved = resolveItems(playerRepository.save(player))
        broadcaster.broadcastPlayer(saved)
        return saved
    }
}
