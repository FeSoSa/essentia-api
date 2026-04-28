package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "essencias")
data class Essencia(
    @Id val id: String,
    val name: String,
    val type: String,              // "Grande" | "Mitica" | "Derivada"
    val desc: String,
    val attributeBonus: Map<String, Int>,
    val skillIds: List<String>
)
