package com.rpg.essentia.controller

import com.rpg.essentia.model.TipoDano
import com.rpg.essentia.service.TipoDanoService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tipos-dano")
class TipoDanoController(private val tipoDanoService: TipoDanoService) {

    @GetMapping
    fun list(): List<TipoDano> = tipoDanoService.list()

    @PostMapping
    fun create(@RequestBody tipo: TipoDano): ResponseEntity<TipoDano> =
        ResponseEntity.status(HttpStatus.CREATED).body(tipoDanoService.create(tipo))

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody tipo: TipoDano): TipoDano =
        tipoDanoService.update(id, tipo)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        tipoDanoService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
