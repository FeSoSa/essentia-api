package com.rpg.essentia.controller

import com.rpg.essentia.model.BestiarioEntrada
import com.rpg.essentia.service.DescobertaService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/descobertas")
class DescobertaController(private val descobertaService: DescobertaService) {

    /** Jogadores: só as liberadas. */
    @GetMapping
    fun list(): List<BestiarioEntrada> = descobertaService.listar()

    /** Mestre: todas, inclusive as pendentes. */
    @GetMapping("/todas")
    fun listTodas(): List<BestiarioEntrada> = descobertaService.listar(todas = true)

    @PutMapping("/{id}/liberar")
    fun liberar(@PathVariable id: String, @RequestBody body: Map<String, Boolean>) =
        descobertaService.liberar(id, body["liberada"] ?: true)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        descobertaService.apagar(id)
        return ResponseEntity.noContent().build()
    }
}
