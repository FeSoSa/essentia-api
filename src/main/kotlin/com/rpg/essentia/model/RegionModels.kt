package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

@Document(collection = "regions")
data class Region(
    @Id val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val description: String = ""
)
