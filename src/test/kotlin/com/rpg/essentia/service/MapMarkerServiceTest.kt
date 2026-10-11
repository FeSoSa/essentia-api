package com.rpg.essentia.service

import com.rpg.essentia.model.MapMarker
import com.rpg.essentia.repository.MapMarkerRepository
import com.rpg.essentia.repository.RegionRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class MapMarkerServiceTest {

    private lateinit var mapMarkerRepository: MapMarkerRepository
    private lateinit var regionRepository: RegionRepository
    private lateinit var mapMarkerService: MapMarkerService
    private val store = linkedMapOf<String, MapMarker>()
    private val validRegionId = "region-1"

    @BeforeEach
    fun setUp() {
        store.clear()
        val broadcaster = mock(WebSocketBroadcaster::class.java)
        mapMarkerRepository = mock(MapMarkerRepository::class.java)

        doAnswer { inv ->
            val marker = inv.getArgument<MapMarker>(0)
            store[marker.id] = marker
            marker
        }.`when`(mapMarkerRepository).save(any())

        `when`(mapMarkerRepository.existsById(anyString())).thenAnswer { inv -> store.containsKey(inv.getArgument<String>(0)) }
        `when`(mapMarkerRepository.findById(anyString())).thenAnswer { inv -> Optional.ofNullable(store[inv.getArgument<String>(0)]) }
        `when`(mapMarkerRepository.findAll()).thenAnswer { store.values.toList() }
        doAnswer { inv -> store.remove(inv.getArgument<String>(0)); Unit }.`when`(mapMarkerRepository).deleteById(anyString())

        regionRepository = mock(RegionRepository::class.java)
        `when`(regionRepository.existsById(validRegionId)).thenReturn(true)

        mapMarkerService = MapMarkerService(mapMarkerRepository, regionRepository, broadcaster)
    }

    @Test
    fun `create persists marker and broadcasts`() {
        val marker = MapMarker(mapId = "essentia-root", x = 0.5, y = 0.5, label = "Cidade", regionId = validRegionId)

        val saved = mapMarkerService.create(marker)

        assertEquals(1, store.size)
        assertEquals(marker.label, saved.label)
    }

    @Test
    fun `create with x out of range throws bad request`() {
        val marker = MapMarker(mapId = "essentia-root", x = 1.5, y = 0.5, label = "Invalida", regionId = validRegionId)

        val ex = assertThrows<ResponseStatusException> { mapMarkerService.create(marker) }
        assertEquals(400, ex.statusCode.value())
    }

    @Test
    fun `create with unknown regionId throws bad request`() {
        val marker = MapMarker(mapId = "essentia-root", x = 0.5, y = 0.5, label = "Invalida", regionId = "nao-existe")

        val ex = assertThrows<ResponseStatusException> { mapMarkerService.create(marker) }
        assertEquals(400, ex.statusCode.value())
    }

    @Test
    fun `update on missing id throws not found`() {
        val marker = MapMarker(mapId = "essentia-root", x = 0.5, y = 0.5, label = "Fantasma", regionId = validRegionId)

        val ex = assertThrows<ResponseStatusException> { mapMarkerService.update("nao-existe", marker) }
        assertEquals(404, ex.statusCode.value())
    }
}
