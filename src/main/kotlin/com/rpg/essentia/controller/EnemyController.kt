package com.rpg.essentia.controller

import com.rpg.essentia.model.*
import com.rpg.essentia.service.EnemyService
import org.springframework.web.bind.annotation.*

@RestController
class EnemyController(private val enemyService: EnemyService) {

    /* ── Catalog: /api/enemies ───────────────────────────────── */

    @GetMapping("/api/enemies")
    fun listTemplates(): List<EnemyTemplate> = enemyService.listTemplates()

    @PostMapping("/api/enemies")
    fun createTemplate(@RequestBody template: EnemyTemplate): EnemyTemplate =
        enemyService.createTemplate(template)

    @PutMapping("/api/enemies/{id}")
    fun updateTemplate(@PathVariable id: String, @RequestBody template: EnemyTemplate): EnemyTemplate =
        enemyService.updateTemplate(id, template)

    @DeleteMapping("/api/enemies/{id}")
    fun deleteTemplate(@PathVariable id: String) = enemyService.deleteTemplate(id)

    /* ── Combat: /api/combat/enemies ────────────────────────── */

    @GetMapping("/api/combat/enemies")
    fun listInstances(): List<EnemyInstance> = enemyService.listInstances()

    @PostMapping("/api/combat/enemies")
    fun addToCombat(@RequestBody req: AddEnemyRequest): List<EnemyInstance> =
        enemyService.addToCombat(req)

    @PutMapping("/api/combat/enemies/{id}/hp")
    fun adjustHp(@PathVariable id: String, @RequestBody req: EnemyHpRequest): List<EnemyInstance> =
        enemyService.adjustHp(id, req.delta)

    @PutMapping("/api/combat/enemies/{id}/notes")
    fun updateNotes(@PathVariable id: String, @RequestBody req: EnemyNotesRequest): List<EnemyInstance> =
        enemyService.updateNotes(id, req.notes)

    @PostMapping("/api/combat/enemies/{id}/defeat")
    fun defeat(@PathVariable id: String, @RequestBody req: EnemyDefeatRequest): List<EnemyInstance> =
        enemyService.defeat(id, req)
}
