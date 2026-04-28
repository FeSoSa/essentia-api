package com.rpg.essentia.controller

import com.rpg.essentia.model.LoginRequest
import com.rpg.essentia.model.Player
import com.rpg.essentia.repository.PlayerRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/auth")
class AuthController(private val playerRepository: PlayerRepository) {

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): Player =
        playerRepository.findByCode(request.code)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid code")
}
