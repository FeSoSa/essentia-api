package com.rpg.essentia.controller

import com.rpg.essentia.model.MapMarker
import com.rpg.essentia.model.MapMarkerVisibilityRequest
import com.rpg.essentia.service.MapMarkerService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/map-markers")
class MapMarkerController(private val mapMarkerService: MapMarkerService) {

    @GetMapping
    fun getAll(): List<MapMarker> = mapMarkerService.getAll()

    @GetMapping("/by-map/{mapId}")
    fun getByMap(
        @PathVariable mapId: String,
        @RequestParam(required = false, defaultValue = "false") visible: Boolean
    ): List<MapMarker> = mapMarkerService.getByMap(mapId, onlyVisible = visible)

    @PostMapping
    fun create(@RequestBody marker: MapMarker): MapMarker = mapMarkerService.create(marker)

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody marker: MapMarker): MapMarker =
        mapMarkerService.update(id, marker)

    @PutMapping("/{id}/visibility")
    fun toggleVisibility(
        @PathVariable id: String,
        @RequestBody req: MapMarkerVisibilityRequest
    ): MapMarker = mapMarkerService.toggleVisible(id, req.visible)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String) = mapMarkerService.delete(id)
}
