package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.BossInstanceRepository
import com.rpg.essentia.repository.ClassKitRepository
import com.rpg.essentia.repository.EnemyInstanceRepository
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.repository.SkillRepository
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
    private val playerSkillRepository: PlayerSkillRepository,
    private val skillRepository: SkillRepository,
    private val attributeService: AttributeService,
    private val essenciaRepository: EssenciaRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster,
    private val enemyRepository: EnemyInstanceRepository,
    private val bossRepository: BossInstanceRepository,
    private val itemCatalogService: ItemCatalogService,
    private val classKitRepository: ClassKitRepository,
    private val maestriaService: MaestriaService,
) {
    private fun combatActive(): Boolean =
        gameStateService.getOrCreate().initiative.isNotEmpty()

    fun load(id: String): Player =
        playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found")
        }

    /** Delta negativo é dano (consome o Escudo primeiro); positivo é cura (nunca gera Escudo). */
    fun adjustHp(id: String, delta: Int): Player {
        val player = load(id)
        val updated = if (delta < 0) EscudoRules.receberDano(player, -delta) else EscudoRules.curar(player, delta)
        val actual = updated.hp.current - player.hp.current
        val absorvido = player.escudo - updated.escudo
        if (actual != 0 || absorvido != 0)
            gameStateService.addLogEntry(id, "${player.char.name} HP ${if (actual >= 0) "+$actual" else "$actual"}" +
                (if (absorvido > 0) " (Escudo absorveu $absorvido)" else ""), "hp")
        return saveAndBroadcast(updated)
    }

    /** Mestre (ou skill em aliado) dá Escudo: soma até o teto de 25% do PV máximo. */
    fun darEscudo(id: String, valor: Int): Player {
        if (valor <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "O Escudo precisa ser maior que 0")
        val player = load(id)
        val updated = EscudoRules.ganhar(player, valor)
        gameStateService.addLogEntry(id, "${player.char.name} ganhou ${updated.escudo - player.escudo} de Escudo", "hp")
        return saveAndBroadcast(updated)
    }

    /** Ajuste manual do mestre: positivo dá (até o teto), negativo tira (até 0). */
    fun ajustarEscudo(id: String, delta: Int): Player {
        val player = load(id)
        val updated = if (delta >= 0) EscudoRules.ganhar(player, delta) else player.copy(escudo = (player.escudo + delta).coerceAtLeast(0))
        val mudou = updated.escudo - player.escudo
        if (mudou != 0) gameStateService.addLogEntry(id, "${player.char.name} Escudo ${if (mudou > 0) "+$mudou" else "$mudou"}", "hp")
        return saveAndBroadcast(updated)
    }

    /** Fim do dia: zera o Escudo de todos os jogadores. */
    fun fimDoDia(): List<Player> =
        playerRepository.findAll().filter { it.escudo != 0 }.map { saveAndBroadcast(it.copy(escudo = 0)) }

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

        // Always recompute effectiveAttributes so the final column reflects the new base
        val essencias = essenciaRepository.findAll()
        val effective = attributeService.computeEffectiveAttributes(updated, essencias)
        val oldHpMax = updated.hp.max
        updated = attributeService.recalculateVitals(updated, effective)
        if (attribute == "resistance") {
            val gained = updated.hp.max - oldHpMax
            updated = updated.copy(hp = updated.hp.copy(current = minOf(updated.hp.current + gained, updated.hp.max)))
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

        // Validate: class slots only accept class skills
        if (skillId != null) {
            val slot = player.slots.firstOrNull { it.id == slotId }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot não encontrado")
            if (slot.type == "class") {
                val skill = skillRepository.findById(skillId).orElseThrow {
                    ResponseStatusException(HttpStatus.NOT_FOUND, "Habilidade não encontrada")
                }
                if (skill.type != "class")
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Slots de classe só aceitam habilidades de classe"
                    )
            }
        }

        // Clear slotId from the skill previously in this slot
        val previousSkillId = player.slots.firstOrNull { it.id == slotId }?.skillId
        if (previousSkillId != null && previousSkillId != skillId) {
            playerSkillRepository.findByPlayerIdAndSkillId(id, previousSkillId)?.let { ps ->
                playerSkillRepository.save(ps.copy(slotId = null, equipped = false))
            }
        }
        // Set slotId on the new skill being equipped
        if (skillId != null) {
            playerSkillRepository.findByPlayerIdAndSkillId(id, skillId)?.let { ps ->
                playerSkillRepository.save(ps.copy(slotId = slotId, equipped = true))
            }
        }

        val newSlots = player.slots.map { slot ->
            if (slot.id == slotId) slot.copy(skillId = skillId) else slot
        }
        val withSlots = player.copy(slots = newSlots)
        val skills = skillRepository.findAllById(newSlots.mapNotNull { it.skillId })
        val synced = attributeService.syncPassiveSelfEffects(withSlots, skills)
        return saveAndBroadcast(synced)
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
        broadcaster.broadcastPlayers(playerRepository.findAll().map { it.copy(items = itemCatalogService.resolveAll(it.items)) })
        return saved
    }

    fun equipFromInventory(id: String, itemId: String): Player {
        val player = load(id)
        // Resolve contra o catálogo para levar campos novos (ex: margemCritico) ao equipamento
        val item = player.items.firstOrNull { it.id == itemId }?.let { itemCatalogService.resolve(it) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item não encontrado no inventário")
        val slot = when (item.equipSlot) {
            null -> when (item.type) {
                "armor"  -> "armor"
                "weapon" -> "mainHand"
                else     -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Item não tem slot de equipamento")
            }
            "both" -> if (player.equipment.mainHand == null) "mainHand" else "offHand"
            else -> item.equipSlot
        }

        // Armas: qualquer personagem equipa qualquer arma; a proficiência é que libera skills e propriedade
        if (item.type != "weapon") item.requirements?.let { req ->
            val essencias = essenciaRepository.findAll()
            val effective = attributeService.computeEffectiveAttributes(player, essencias)
            val attrMap = effective.toMap()
            req.level?.let { minLevel ->
                if (player.char.level < minLevel)
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível insuficiente: requer nível $minLevel")
            }
            req.attributes?.forEach { (attr, minVal) ->
                val current = attrMap[attr] ?: 0
                if (current < minVal)
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Atributo insuficiente: ${attr.uppercase()} requer $minVal (atual: $current)")
            }
        }

        val previousItem: Item? = player.equipment.slotToItem(slot)
            ?: if (slot !in setOf("mainHand","offHand","armor","amulet","ring","utility"))
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot inválido: $slot") else null

        val newEquipment = when (slot) {
            "mainHand" -> player.equipment.copy(mainHand = WeaponEquip(
                id = item.id, name = item.name, desc = item.desc.ifBlank { null }, icon = item.icon,
                weaponType = item.weaponType ?: "", damageBase = item.damageBase ?: 0,
                damageAttribute = item.damageAttribute ?: "", equilibrio = item.equilibrio,
                properties = item.properties, attributeBonus = item.attributeBonus,
                rarity = item.rarity, twoHanded = item.twoHanded ?: false, equipSlot = item.equipSlot,
                margemCritico = item.margemCritico))
            "offHand" -> player.equipment.copy(offHand = WeaponEquip(
                id = item.id, name = item.name, desc = item.desc.ifBlank { null }, icon = item.icon,
                weaponType = item.weaponType ?: "", damageBase = item.damageBase ?: 0,
                damageAttribute = item.damageAttribute ?: "", equilibrio = item.equilibrio,
                properties = item.properties, attributeBonus = item.attributeBonus,
                rarity = item.rarity, twoHanded = item.twoHanded ?: false, equipSlot = item.equipSlot,
                margemCritico = item.margemCritico))
            "armor" -> player.equipment.copy(armor = ArmorEquip(
                id = item.id, name = item.name, desc = item.desc.ifBlank { null }, icon = item.icon,
                damageReduction = item.damageReduction ?: 0, attributeBonus = item.attributeBonus,
                armorWeight = item.armorWeight, rarity = item.rarity, negaCritico = item.negaCritico ?: false))
            "amulet" -> player.equipment.copy(amulet = AccessoryEquip(
                id = item.id, name = item.name, desc = item.desc.ifBlank { null }, icon = item.icon,
                attributeBonus = item.attributeBonus, rarity = item.rarity))
            "ring" -> player.equipment.copy(ring = AccessoryEquip(
                id = item.id, name = item.name, desc = item.desc.ifBlank { null }, icon = item.icon,
                attributeBonus = item.attributeBonus, rarity = item.rarity))
            "utility" -> player.equipment.copy(utility = AccessoryEquip(
                id = item.id, name = item.name, desc = item.desc.ifBlank { null }, icon = item.icon,
                attributeBonus = item.attributeBonus, rarity = item.rarity))
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
        if (nonCurrencyCount >= player.inventorySize)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Inventário cheio")

        val previousItem: Item = player.equipment.slotToItem(slot)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Slot vazio ou inválido: $slot")

        val newEquipment = when (slot) {
            "mainHand" -> player.equipment.copy(mainHand = null)
            "offHand"  -> player.equipment.copy(offHand = null)
            "armor"    -> player.equipment.copy(armor = null)
            "amulet"   -> player.equipment.copy(amulet = null)
            "ring"     -> player.equipment.copy(ring = null)
            "utility"  -> player.equipment.copy(utility = null)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot inválido: $slot")
        }

        var finalPlayer = player.copy(equipment = newEquipment, items = player.items + previousItem)

        // Se desquipou uma arma, remove skills de arma que não têm mais suporte
        if (slot in setOf("mainHand", "offHand")) {
            val remainingWeaponTypes = listOfNotNull(
                finalPlayer.equipment.mainHand?.weaponType?.takeIf { it.isNotBlank() }?.lowercase(),
                finalPlayer.equipment.offHand?.weaponType?.takeIf { it.isNotBlank() }?.lowercase()
            ).toSet()
            val allPlayerSkills = playerSkillRepository.findByPlayerId(id)
            val lostSkillIds = allPlayerSkills.mapNotNull { ps ->
                val skill = skillRepository.findById(ps.skillId).orElse(null)
                val sTypes = skill?.weaponTypes?.map { it.lowercase() }
                    ?: listOfNotNull(skill?.weaponType?.lowercase())
                if (skill != null && skill.type == "weapon" &&
                    sTypes.isNotEmpty() && sTypes.none { it in remainingWeaponTypes })
                    ps.skillId else null
            }.toSet()
            if (lostSkillIds.isNotEmpty()) {
                allPlayerSkills.filter { it.skillId in lostSkillIds }
                    .forEach { playerSkillRepository.deleteById(it.id) }
                finalPlayer = finalPlayer.copy(
                    slots = finalPlayer.slots.map { s ->
                        if (s.skillId in lostSkillIds) s.copy(skillId = null, cooldownRemaining = 0) else s
                    }
                )
            }
        }

        val essencias = essenciaRepository.findAll()
        val effective = attributeService.computeEffectiveAttributes(finalPlayer, essencias)
        return saveAndBroadcast(attributeService.recalculateVitals(finalPlayer, effective))
    }

    /** Mestre define as proficiências em categorias de arma (ganho por treino ou uso é manual). */
    fun setProficiencias(id: String, proficiencias: List<String>): Player {
        val player = load(id)
        val limpas = proficiencias.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        // Ganhar proficiência coloca o personagem na maestria nível 1 da categoria (perder não apaga a maestria)
        return saveAndBroadcast(MaestriaRules.garantir(player.copy(proficiencias = limpas)))
    }

    /** Anotações do jogador na aba Personagem (texto livre, só ele edita). */
    fun setAnotacoes(id: String, texto: String): Player {
        if (texto.length > 5000) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Anotações com no máximo 5000 caracteres")
        val player = load(id)
        return saveAndBroadcast(player.copy(perfil = player.perfil.copy(anotacoes = texto)))
    }

    /** Nível de classe (1 a 4): só o mestre altera; é o requisito das skills de classe pelo tier. */
    fun setNivelClasse(id: String, nivel: Int): Player {
        if (nivel !in 1..4) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível de classe deve ser de 1 a 4")
        return saveAndBroadcast(load(id).copy(nivelClasse = nivel))
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

    /** Sorte: soma 1d6 a uma rolagem de d20 (acerto, desvio ou teste). Só 1 por rolagem e não volta com o tempo. */
    fun usarSorte(id: String, d6: Int, contexto: String): Player {
        if (d6 !in 1..6) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o d6 da sorte (1 a 6)")
        val player = load(id)
        if (player.sorte <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Sem sorte restante")
        val onde = when (contexto) { "acerto" -> "no acerto"; "desvio" -> "no desvio"; else -> "num teste" }
        gameStateService.addLogEntry(id, "${player.char.name} usou SORTE $onde: +$d6 (restam ${player.sorte - 1})", "info")
        return saveAndBroadcast(player.copy(sorte = player.sorte - 1))
    }

    /** Mestre dá ou tira sorte (recompensa rara). */
    fun ajustarSorte(id: String, delta: Int): Player {
        val player = load(id)
        val nova = (player.sorte + delta).coerceAtLeast(0)
        if (nova != player.sorte) gameStateService.addLogEntry(id, "${player.char.name} Sorte ${if (delta > 0) "+$delta" else "$delta"} (agora $nova)", "info")
        return saveAndBroadcast(player.copy(sorte = nova))
    }

    fun deductCosts(id: String, costs: Map<String, Int>, slotId: String? = null): Player {
        var player = load(id)
        costs["flow"]?.let { c ->
            player = player.copy(flow = player.flow.copy(current = (player.flow.current - c).coerceAtLeast(0)))
        }
        costs["percentual_flow"]?.let { c ->
            player = player.copy(flow = player.flow.copy(current = (player.flow.current - c).coerceAtLeast(0)))
        }
        costs["hp"]?.let { c ->
            player = player.copy(hp = player.hp.copy(current = (player.hp.current - c).coerceAtLeast(0)))
        }
        costs["percentual_hp"]?.let { c ->
            player = player.copy(hp = player.hp.copy(current = (player.hp.current - c).coerceAtLeast(0)))
        }
        costs["ether"]?.let { c ->
            player = player.copy(ether = player.ether.copy(current = (player.ether.current - c).coerceAtLeast(0)))
        }
        costs["pressao"]?.let { c ->
            player.pressao?.let { p ->
                player = player.copy(pressao = p.copy(current = (p.current - c).coerceAtLeast(0)))
            }
        }
        // Ao errar, o cooldown da habilidade é setado agora (não no início do uso)
        if (slotId != null) {
            val slot = player.slots.firstOrNull { it.id == slotId }
            val cooldownTurns = slot?.skillId?.let { skillRepository.findById(it).orElse(null) }?.cooldownTurns ?: 0
            player = player.copy(slots = player.slots.map { s ->
                if (s.id == slotId) s.copy(cooldownRemaining = cooldownTurns) else s
            })
        }
        return saveAndBroadcast(player)
    }

    fun transferItem(senderId: String, itemId: String, targetPlayerId: String, qty: Int): Player {
        val sender = load(senderId)
        val target = load(targetPlayerId)

        val sourceItem = sender.items.firstOrNull { it.id == itemId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Item não encontrado no inventário")
        if (qty < 1 || qty > sourceItem.qty)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantidade inválida")

        val targetNonCurrency = target.items.count { it.type != "currency" }
        if (sourceItem.type != "currency" && targetNonCurrency >= target.inventorySize)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Inventário do jogador alvo está cheio")

        // Remove or reduce from sender
        val newSenderItems = if (sourceItem.qty - qty <= 0)
            sender.items.filter { it.id != itemId }
        else
            sender.items.map { if (it.id == itemId) it.copy(qty = it.qty - qty) else it }

        // Add or stack on target
        val stackable = sourceItem.type in setOf("normal", "chave", "consumable", "currency")
        val existing = if (stackable) target.items.find { it.name == sourceItem.name && it.type == sourceItem.type } else null
        val newTargetItems = if (existing != null)
            target.items.map { if (it.id == existing.id) it.copy(qty = it.qty + qty) else it }
        else
            target.items + sourceItem.copy(id = UUID.randomUUID().toString(), qty = qty)

        saveAndBroadcast(sender.copy(items = newSenderItems))
        val savedTarget = saveAndBroadcast(target.copy(items = newTargetItems))

        gameStateService.addLogEntry(
            senderId,
            "${sender.char.name} transferiu ${sourceItem.name}${if (qty > 1) " ×$qty" else ""} para ${target.char.name}",
            "item"
        )

        return playerRepository.findById(senderId).orElse(sender.copy(items = newSenderItems))
    }

    fun saveAndBroadcast(player: Player): Player {
        val saved = playerRepository.save(player)
        val resolved = saved.copy(items = itemCatalogService.resolveAll(saved.items))
        broadcaster.broadcastPlayer(resolved)
        return resolved
    }
}
