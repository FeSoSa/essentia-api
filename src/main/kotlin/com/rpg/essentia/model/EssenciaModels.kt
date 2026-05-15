package com.rpg.essentia.model

import java.util.UUID
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "essencias")
data class Essencia(
    @Id val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,              // "Grande" | "Mitica" | "Derivada"
    val desc: String,
    val attributeBonus: Map<String, Int>,
    val skillIds: List<String>,
    val icon: String? = null,
    val color: String? = null,
    val parentId: String? = null
)
