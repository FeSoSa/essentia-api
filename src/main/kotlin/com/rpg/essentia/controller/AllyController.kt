package com.rpg.essentia.controller

import com.rpg.essentia.model.*
import com.rpg.essentia.service.AllyService
import org.springframework.web.bind.annotation.*

@RestController
class AllyController(private val allyService: AllyService) {

    /* ── Catalog: /api/allies ────────────────────────────────── */

    @GetMapping("/api/allies")
    fun listTemplates(): List<AllyTemplate> = allyService.listTemplates()

    @PostMapping("/api/allies")
    fun createTemplate(@RequestBody t: AllyTemplate): AllyTemplate = allyService.createTemplate(t)

    @PutMapping("/api/allies/{id}")
    fun updateTemplate(@PathVariable id: String, @RequestBody t: AllyTemplate): AllyTemplate =
        allyService.updateTemplate(id, t)

    @DeleteMapping("/api/allies/{id}")
    fun deleteTemplate(@PathVariable id: String) = allyService.deleteTemplate(id)

    /* ── Combat: /api/combat/allies ──────────────────────────── */

    @GetMapping("/api/combat/allies")
    fun listAllies(): List<CombatAlly> = allyService.listAllies()

    @PostMapping("/api/combat/allies")
    fun addAlly(@RequestBody req: AddAllyRequest): List<CombatAlly> = allyService.addAlly(req)

    @PutMapping("/api/combat/allies/{id}/hp")
    fun adjustHp(@PathVariable id: String, @RequestBody req: AllyHpRequest): List<CombatAlly> =
        allyService.adjustHp(id, req.delta)

    @PutMapping("/api/combat/allies/{id}/notes")
    fun updateNotes(@PathVariable id: String, @RequestBody req: AllyNotesRequest): List<CombatAlly> =
        allyService.updateNotes(id, req.notes)

    @DeleteMapping("/api/combat/allies/{id}")
    fun removeAlly(@PathVariable id: String): List<CombatAlly> = allyService.removeAlly(id)
}
