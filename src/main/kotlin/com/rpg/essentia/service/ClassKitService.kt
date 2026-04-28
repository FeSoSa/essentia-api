package com.rpg.essentia.service

import com.rpg.essentia.model.ClassKit
import com.rpg.essentia.repository.ClassKitRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class ClassKitService(private val classKitRepository: ClassKitRepository) {

    fun getByClass(className: String): ClassKit =
        classKitRepository.findByClassName(className)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Kit não encontrado para classe: $className")
}
