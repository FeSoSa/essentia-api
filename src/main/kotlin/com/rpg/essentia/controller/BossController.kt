package com.rpg.essentia.controller

import com.rpg.essentia.model.*
import com.rpg.essentia.service.BossService
import org.springframework.web.bind.annotation.*

@RestController
class BossController(private val bossService: BossService) {

    /* ── Catalog: /api/bosses ────────────────────────────────── */

    @GetMapping("/api/bosses")
    fun listTemplates(): List<BossTemplate> = bossService.listTemplates()

    @PostMapping("/api/bosses")
    fun createTemplate(@RequestBody template: BossTemplate): BossTemplate =
        bossService.createTemplate(template)

    @PutMapping("/api/bosses/{id}")
    fun updateTemplate(@PathVariable id: String, @RequestBody template: BossTemplate): BossTemplate =
        bossService.updateTemplate(id, template)

    @DeleteMapping("/api/bosses/{id}")
    fun deleteTemplate(@PathVariable id: String) = bossService.deleteTemplate(id)

    /* ── Combat: /api/combat/bosses ─────────────────────────── */

    @GetMapping("/api/combat/bosses")
    fun listInstances(): List<BossInstance> = bossService.listInstances()

    @PostMapping("/api/combat/bosses")
    fun addToCombat(@RequestBody req: AddBossRequest): List<BossInstance> =
        bossService.addToCombat(req)

    @PutMapping("/api/combat/bosses/{id}/hp")
    fun adjustHp(@PathVariable id: String, @RequestBody req: BossHpRequest): List<BossInstance> =
        bossService.adjustHp(id, req.delta)

    @PutMapping("/api/combat/bosses/{id}/notes")
    fun updateNotes(@PathVariable id: String, @RequestBody req: BossNotesRequest): List<BossInstance> =
        bossService.updateNotes(id, req.notes)

    @PostMapping("/api/combat/bosses/{id}/next-phase")
    fun nextPhase(@PathVariable id: String): List<BossInstance> =
        bossService.nextPhase(id)

    @PostMapping("/api/combat/bosses/{id}/defeat")
    fun defeat(@PathVariable id: String, @RequestBody req: BossDefeatRequest): List<BossInstance> =
        bossService.defeat(id, req)
}
