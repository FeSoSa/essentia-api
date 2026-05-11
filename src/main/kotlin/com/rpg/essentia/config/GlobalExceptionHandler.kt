package com.rpg.essentia.config

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<Map<String, Any>> {
        val body = mapOf(
            "message" to (ex.reason ?: ex.message ?: "Erro desconhecido"),
            "status"  to ex.statusCode.value()
        )
        return ResponseEntity.status(ex.statusCode).body(body)
    }
}
