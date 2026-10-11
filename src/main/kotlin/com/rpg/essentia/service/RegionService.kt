package com.rpg.essentia.service

import com.rpg.essentia.model.Region
import com.rpg.essentia.repository.MapMarkerRepository
import com.rpg.essentia.repository.RegionRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class RegionService(
    private val regionRepository: RegionRepository,
    private val mapMarkerRepository: MapMarkerRepository,
    private val broadcaster: WebSocketBroadcaster
) {
    fun getAll(): List<Region> = regionRepository.findAll()

    fun create(region: Region): Region {
        val saved = regionRepository.save(region.copy(id = UUID.randomUUID().toString()))
        broadcaster.broadcastRegions(regionRepository.findAll())
        return saved
    }

    fun update(id: String, region: Region): Region {
        if (!regionRepository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Region not found")
        val saved = regionRepository.save(region.copy(id = id))
        broadcaster.broadcastRegions(regionRepository.findAll())
        return saved
    }

    fun delete(id: String) {
        if (!regionRepository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Region not found")
        if (mapMarkerRepository.findAll().any { it.regionId == id }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Região possui marcadores vinculados")
        }
        regionRepository.deleteById(id)
        broadcaster.broadcastRegions(regionRepository.findAll())
    }
}
