package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

@Document(collection = "map_markers")
data class MapMarker(
    @Id val id: String = UUID.randomUUID().toString(),
    val mapId: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val label: String = "",
    val regionId: String = "",
    val description: String = "",
    val visible: Boolean = true
)

data class MapMarkerVisibilityRequest(val visible: Boolean)
