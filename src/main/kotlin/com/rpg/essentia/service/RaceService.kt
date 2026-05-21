package com.rpg.essentia.service

import com.rpg.essentia.model.Race
import com.rpg.essentia.repository.RaceRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class RaceService(private val raceRepository: RaceRepository) {

    fun listAll(): List<Race> = raceRepository.findAll()

    fun getByName(name: String): Race? = raceRepository.findByName(name)

    fun create(race: Race): Race = raceRepository.save(race)

    fun update(id: String, race: Race): Race {
        if (!raceRepository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Raça não encontrada: $id")
        return raceRepository.save(race.copy(id = id))
    }

    fun delete(id: String) {
        if (!raceRepository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Raça não encontrada: $id")
        raceRepository.deleteById(id)
    }
}
