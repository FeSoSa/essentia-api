package com.rpg.essentia.service

import com.rpg.essentia.model.MapMarker
import com.rpg.essentia.repository.MapMarkerRepository
import com.rpg.essentia.repository.RegionRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class MapMarkerService(
    private val mapMarkerRepository: MapMarkerRepository,
    private val regionRepository: RegionRepository,
    private val broadcaster: WebSocketBroadcaster
) {
    fun getAll(): List<MapMarker> = mapMarkerRepository.findAll()

    fun getByMap(mapId: String, onlyVisible: Boolean): List<MapMarker> =
        if (onlyVisible) mapMarkerRepository.findByMapIdAndVisibleTrue(mapId)
        else mapMarkerRepository.findByMapId(mapId)

    fun create(marker: MapMarker): MapMarker {
        validate(marker)
        val saved = mapMarkerRepository.save(marker.copy(id = java.util.UUID.randomUUID().toString()))
        broadcaster.broadcastMapMarkers(mapMarkerRepository.findAll())
        return saved
    }

    fun update(id: String, marker: MapMarker): MapMarker {
        if (!mapMarkerRepository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Marker not found")
        validate(marker)
        val saved = mapMarkerRepository.save(marker.copy(id = id))
        broadcaster.broadcastMapMarkers(mapMarkerRepository.findAll())
        return saved
    }

    fun toggleVisible(id: String, visible: Boolean): MapMarker {
        val existing = mapMarkerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Marker not found")
        }
        val saved = mapMarkerRepository.save(existing.copy(visible = visible))
        broadcaster.broadcastMapMarkers(mapMarkerRepository.findAll())
        return saved
    }

    fun delete(id: String) {
        if (!mapMarkerRepository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Marker not found")
        mapMarkerRepository.deleteById(id)
        broadcaster.broadcastMapMarkers(mapMarkerRepository.findAll())
    }

    private fun validate(marker: MapMarker) {
        if (marker.x < 0.0 || marker.x > 1.0 || marker.y < 0.0 || marker.y > 1.0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "x/y must be between 0 and 1")
        }
        if (marker.regionId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "regionId is required")
        }
        if (!regionRepository.existsById(marker.regionId)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "regionId does not reference an existing region")
        }
    }
}
