package com.rpg.essentia.controller

import com.rpg.essentia.model.EffectPreset
import com.rpg.essentia.repository.EffectPresetRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/master/effect-presets")
class EffectPresetController(private val repo: EffectPresetRepository) {

    @GetMapping
    fun getAll(): List<EffectPreset> = repo.findAll()

    @PostMapping
    fun create(@RequestBody preset: EffectPreset): EffectPreset = repo.save(preset)

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody preset: EffectPreset): ResponseEntity<EffectPreset> {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(repo.save(preset.copy(id = id)))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build()
        repo.deleteById(id)
        return ResponseEntity.noContent().build()
    }
}
