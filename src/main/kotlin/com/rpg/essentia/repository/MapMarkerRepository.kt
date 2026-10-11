package com.rpg.essentia.repository

import com.rpg.essentia.model.MapMarker
import org.springframework.data.mongodb.repository.MongoRepository

interface MapMarkerRepository : MongoRepository<MapMarker, String> {
    fun findByMapId(mapId: String): List<MapMarker>
    fun findByMapIdAndVisibleTrue(mapId: String): List<MapMarker>
}
