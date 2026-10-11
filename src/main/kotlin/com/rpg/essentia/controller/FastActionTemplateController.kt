package com.rpg.essentia.controller

import com.rpg.essentia.model.FastActionTemplate
import com.rpg.essentia.service.FastActionTemplateService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/fast-action-templates")
class FastActionTemplateController(private val service: FastActionTemplateService) {

    @GetMapping
    fun list(): List<FastActionTemplate> = service.list()

    @PostMapping
    fun create(@RequestBody template: FastActionTemplate): FastActionTemplate = service.create(template)

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody template: FastActionTemplate): FastActionTemplate =
        service.update(id, template)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String) = service.delete(id)
}
