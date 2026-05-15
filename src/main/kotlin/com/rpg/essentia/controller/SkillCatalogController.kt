package com.rpg.essentia.controller

import com.rpg.essentia.model.Skill
import com.rpg.essentia.service.SkillCatalogService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/skills")
class SkillCatalogController(private val skillCatalogService: SkillCatalogService) {

    @GetMapping
    fun list(): List<Skill> = skillCatalogService.list()

    @PostMapping
    fun create(@RequestBody skill: Skill): ResponseEntity<Skill> =
        ResponseEntity.status(HttpStatus.CREATED).body(skillCatalogService.create(skill))

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody skill: Skill): Skill =
        skillCatalogService.update(id, skill)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        skillCatalogService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
