package com.rpg.essentia.config

import com.rpg.essentia.model.ClassKit
import com.rpg.essentia.repository.ClassKitRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Component
class DataInitializer(
    private val classKitRepository: ClassKitRepository,
    private val objectMapper: ObjectMapper
) : CommandLineRunner {

    override fun run(vararg args: String) {
        if (classKitRepository.count() == 0L) {
            val resource = ClassPathResource("class_kits.json")
            val kits: List<ClassKit> = objectMapper.readValue(
                resource.inputStream,
                object : TypeReference<List<ClassKit>>() {}
            )
            classKitRepository.saveAll(kits)
        }
    }
}
