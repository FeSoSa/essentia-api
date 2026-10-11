package com.rpg.essentia.service

import com.rpg.essentia.model.MapMarker
import com.rpg.essentia.model.Region
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

class RegionServiceTest {

    private lateinit var regionRepository: RegionRepository
    private lateinit var mapMarkerRepository: MapMarkerRepository
    private lateinit var regionService: RegionService
    private val store = linkedMapOf<String, Region>()

    @BeforeEach
    fun setUp() {
        store.clear()
        val broadcaster = mock(WebSocketBroadcaster::class.java)
        regionRepository = mock(RegionRepository::class.java)
        mapMarkerRepository = mock(MapMarkerRepository::class.java)

        doAnswer { inv ->
            val region = inv.getArgument<Region>(0)
            store[region.id] = region
            region
        }.`when`(regionRepository).save(any())

        `when`(regionRepository.existsById(anyString())).thenAnswer { inv -> store.containsKey(inv.getArgument<String>(0)) }
        `when`(regionRepository.findById(anyString())).thenAnswer { inv -> Optional.ofNullable(store[inv.getArgument<String>(0)]) }
        `when`(regionRepository.findAll()).thenAnswer { store.values.toList() }
        doAnswer { inv -> store.remove(inv.getArgument<String>(0)); Unit }.`when`(regionRepository).deleteById(anyString())

        `when`(mapMarkerRepository.findAll()).thenReturn(emptyList())

        regionService = RegionService(regionRepository, mapMarkerRepository, broadcaster)
    }

    @Test
    fun `create persists region and broadcasts`() {
        val region = Region(name = "Aldenar", url = "http://example.com/aldenar.png")

        val saved = regionService.create(region)

        assertEquals(1, store.size)
        assertEquals(region.name, saved.name)
    }

    @Test
    fun `update on missing id throws not found`() {
        val ex = assertThrows<ResponseStatusException> {
            regionService.update("nao-existe", Region(name = "Fantasma"))
        }
        assertEquals(404, ex.statusCode.value())
    }

    @Test
    fun `delete on missing id throws not found`() {
        val ex = assertThrows<ResponseStatusException> { regionService.delete("nao-existe") }
        assertEquals(404, ex.statusCode.value())
    }

    @Test
    fun `delete with referencing marker throws bad request`() {
        val region = regionService.create(Region(name = "Aldenar"))
        `when`(mapMarkerRepository.findAll()).thenReturn(
            listOf(MapMarker(mapId = "essentia-root", regionId = region.id))
        )

        val ex = assertThrows<ResponseStatusException> { regionService.delete(region.id) }
        assertEquals(400, ex.statusCode.value())
    }
}
