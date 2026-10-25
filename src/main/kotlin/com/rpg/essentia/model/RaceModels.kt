package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "races")
data class Race(
    @Id val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val starterAttributes: Attributes = Attributes(),
    val inventorySize: Int = 18,
    val tracosDano: List<TracoDano> = emptyList()   // fraquezas, resistências e imunidades da raça (vencem a armadura)
)
