package com.rpg.essentia.service

import com.rpg.essentia.model.FastActionTemplate
import com.rpg.essentia.repository.FastActionTemplateRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class FastActionTemplateService(private val repository: FastActionTemplateRepository) {

    fun list(): List<FastActionTemplate> = repository.findAll()

    fun create(template: FastActionTemplate): FastActionTemplate =
        repository.save(template.copy(id = UUID.randomUUID().toString()))

    fun update(id: String, template: FastActionTemplate): FastActionTemplate {
        if (!repository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found")
        return repository.save(template.copy(id = id))
    }

    fun delete(id: String) {
        if (!repository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found")
        repository.deleteById(id)
    }
}
