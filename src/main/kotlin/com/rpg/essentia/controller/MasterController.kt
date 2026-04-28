package com.rpg.essentia.controller

import com.rpg.essentia.model.*
import com.rpg.essentia.service.ClassKitService
import com.rpg.essentia.service.EssenciaService
import com.rpg.essentia.service.MasterService
import com.rpg.essentia.service.PlayerCreationService
import com.rpg.essentia.service.TurnService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/master")
class MasterController(
    private val masterService: MasterService,
    private val turnService: TurnService,
    private val classKitService: ClassKitService,
    private val playerCreationService: PlayerCreationService,
    private val essenciaService: EssenciaService
) {
    @GetMapping("/players")
    fun getPlayers(): List<Player> = masterService.getPlayers()

    @GetMapping("/kits/{className}")
    fun getKit(@PathVariable className: String): ClassKit = classKitService.getByClass(className)

    @PostMapping("/players")
    fun createPlayer(@RequestBody req: CreatePlayerRequest): ResponseEntity<Player> {
        val player = playerCreationService.createPlayer(req)
        return ResponseEntity.status(HttpStatus.CREATED).body(player)
    }

    @PutMapping("/players/{id}")
    fun updatePlayer(@PathVariable id: String, @RequestBody req: UpdatePlayerRequest): Player =
        playerCreationService.updatePlayer(id, req)

    @DeleteMapping("/players/{id}")
    fun deletePlayer(@PathVariable id: String): ResponseEntity<Void> {
        playerCreationService.deletePlayer(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/approve-item")
    fun approveItem(@RequestBody req: ApproveRejectItemRequest): Player =
        masterService.approveItem(req.playerId, req.requestId)

    @PostMapping("/reject-item")
    fun rejectItem(@RequestBody req: ApproveRejectItemRequest): Player =
        masterService.rejectItem(req.playerId, req.requestId)

    @PutMapping("/exp")
    fun grantExp(@RequestBody req: ExpRequest): Player =
        masterService.grantExp(req.playerId, req.amount)

    @PostMapping("/next-turn")
    fun nextTurn(): TurnUpdate = turnService.nextTurn()

    @PostMapping("/reset-skills")
    fun resetSkills(@RequestBody req: ResetSkillsRequest) =
        masterService.resetSkills(req.playerId)

    @PutMapping("/initiative")
    fun setInitiative(@RequestBody entries: List<InitiativeEntry>) =
        masterService.setInitiative(entries)

    @PostMapping("/status-effect")
    fun addStatusEffect(@RequestBody req: StatusEffectRequest): Player =
        masterService.addStatusEffect(req.playerId, req.effect)

    @DeleteMapping("/status-effect/{effectId}")
    fun removeStatusEffect(
        @PathVariable effectId: String,
        @RequestBody req: StatusEffectDeleteRequest
    ): Player = masterService.removeStatusEffect(req.playerId, effectId)

    @PostMapping("/players/{id}/items")
    fun addItem(
        @PathVariable id: String,
        @RequestBody req: AddItemRequest
    ): Player = masterService.addItem(id, req)

    @DeleteMapping("/players/{id}/items/{itemId}")
    fun removeItem(
        @PathVariable id: String,
        @PathVariable itemId: String
    ): Player = masterService.removeItem(id, itemId)

    @PutMapping("/players/{id}/items/{itemId}")
    fun adjustItemQty(
        @PathVariable id: String,
        @PathVariable itemId: String,
        @RequestBody req: AdjustItemQtyRequest
    ): Player = masterService.adjustItemQty(id, itemId, req.delta)

    @PutMapping("/players/{id}/maestria")
    fun addMaestriaUses(
        @PathVariable id: String,
        @RequestBody req: MaestriaUsesRequest
    ): PlayerSkill = masterService.addMaestriaUses(id, req.playerSkillId, req.uses)

    // Equipamento
    @PutMapping("/players/{id}/equipment/{slot}")
    fun setEquipment(
        @PathVariable id: String,
        @PathVariable slot: String,
        @RequestBody req: SetEquipmentRequest
    ): Player = masterService.setEquipment(id, slot, req)

    @PutMapping("/players/{id}/gold")
    fun adjustGold(
        @PathVariable id: String,
        @RequestBody req: DeltaRequest
    ): Player = masterService.adjustGold(id, req.delta)

    @DeleteMapping("/players/{id}/equipment/{slot}")
    fun clearEquipment(
        @PathVariable id: String,
        @PathVariable slot: String
    ): Player = masterService.clearEquipment(id, slot)

    // Essências
    @GetMapping("/essencias")
    fun listEssencias(): List<Essencia> = essenciaService.listAll()

    @PostMapping("/players/{id}/essencias")
    fun grantEssencia(
        @PathVariable id: String,
        @RequestBody req: GrantEssenciaRequest
    ): Player = essenciaService.grantEssencia(id, req.essenciaId)

    @DeleteMapping("/players/{id}/essencias/{essenciaId}")
    fun removeEssencia(
        @PathVariable id: String,
        @PathVariable essenciaId: String
    ): Player = essenciaService.removeEssencia(id, essenciaId)
}
