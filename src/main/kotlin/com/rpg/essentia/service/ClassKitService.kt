package com.rpg.essentia.service

import com.rpg.essentia.model.ClassKit
import com.rpg.essentia.repository.ClassKitRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class ClassKitService(private val classKitRepository: ClassKitRepository) {

    fun listAll(): List<ClassKit> = classKitRepository.findAll()

    fun create(kit: ClassKit): ClassKit = classKitRepository.save(kit)

    fun update(id: String, kit: ClassKit): ClassKit = classKitRepository.save(kit.copy(id = id))

    fun delete(id: String) = classKitRepository.deleteById(id)

    fun getByClass(className: String): ClassKit =
        classKitRepository.findByClassName(className)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Kit não encontrado para classe: $className")
}
