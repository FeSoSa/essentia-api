package com.rpg.essentia.controller

import com.rpg.essentia.model.*
import com.rpg.essentia.service.MaestriaService
import com.rpg.essentia.service.PlayerService
import com.rpg.essentia.service.SkillTreeService
import com.rpg.essentia.service.SkillUseService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/players/{id}")
class PlayerController(
    private val playerService: PlayerService,
    private val skillUseService: SkillUseService,
    private val maestriaService: MaestriaService,
    private val skillTreeService: SkillTreeService
) {
    @PutMapping("/hp")
    fun adjustHp(@PathVariable id: String, @RequestBody req: DeltaRequest): Player =
        playerService.adjustHp(id, req.delta)

    @PutMapping("/flow")
    fun adjustFlow(@PathVariable id: String, @RequestBody req: DeltaRequest): Player =
        playerService.adjustFlow(id, req.delta)

    @PutMapping("/ether")
    fun adjustEther(@PathVariable id: String, @RequestBody req: DeltaRequest): Player =
        playerService.adjustEther(id, req.delta)

    @PutMapping("/pressao")
    fun adjustPressao(@PathVariable id: String, @RequestBody req: DeltaRequest): Player =
        playerService.adjustPressao(id, req.delta)

    @PutMapping("/attributes")
    fun adjustAttribute(@PathVariable id: String, @RequestBody req: AttributeDeltaRequest): Player =
        playerService.adjustAttribute(id, req.attribute, req.delta)

    @PostMapping("/use-skill")
    fun useSkill(@PathVariable id: String, @RequestBody req: UseSkillRequest): DamageResult =
        skillUseService.useSkill(id, req)

    @PutMapping("/slots")
    fun updateSlot(@PathVariable id: String, @RequestBody req: SlotUpdateRequest): Player =
        playerService.updateSlot(id, req.slotId, req.skillId)

    @PostMapping("/maestria-upgrade")
    fun maestriaUpgrade(@PathVariable id: String, @RequestBody req: MaestriaUpgradeRequest): PlayerSkill =
        maestriaService.applyUpgrade(id, req.playerSkillId, req.path)

    @PostMapping("/request-item")
    fun requestItem(@PathVariable id: String, @RequestBody req: RequestItemRequest): Player =
        playerService.requestItem(id, req.itemId)

    @PostMapping("/equip")
    fun equipItem(@PathVariable id: String, @RequestBody req: EquipItemRequest): Player =
        playerService.equipFromInventory(id, req.itemId)

    @DeleteMapping("/equipment/{slot}")
    fun unequipItem(@PathVariable id: String, @PathVariable slot: String): Player =
        playerService.unequipItem(id, slot)

    @GetMapping("/skill-tree")
    fun getSkillTree(@PathVariable id: String): List<PlayerSkillTreeEntry> =
        skillTreeService.getSkillTree(id)

    @PostMapping("/unlock-skill")
    fun unlockSkill(@PathVariable id: String, @RequestBody req: UnlockSkillRequest): PlayerSkill =
        skillTreeService.unlockSkill(id, req.skillId)

    @PostMapping("/desvio")
    fun desvio(@PathVariable id: String): Player =
        playerService.executeDesvio(id)
}
