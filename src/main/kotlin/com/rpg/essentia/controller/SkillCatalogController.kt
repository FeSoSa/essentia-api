package com.rpg.essentia.controller

import com.rpg.essentia.model.Skill
import com.rpg.essentia.repository.SkillRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/skills")
class SkillCatalogController(private val skillRepository: SkillRepository) {

    @GetMapping
    fun list(): List<Skill> = skillRepository.findAll()

    @PostMapping
    fun create(@RequestBody skill: Skill): ResponseEntity<Skill> =
        ResponseEntity.status(HttpStatus.CREATED).body(skillRepository.save(skill))

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody skill: Skill): Skill =
        skillRepository.save(skill.copy(id = id))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        skillRepository.deleteById(id)
        return ResponseEntity.noContent().build()
    }
}
