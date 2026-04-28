package com.rpg.essentia.repository

import com.rpg.essentia.model.ClassKit
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query

interface ClassKitRepository : MongoRepository<ClassKit, String> {
    @Query("{ 'class': ?0 }")
    fun findByClassName(className: String): ClassKit?
}
