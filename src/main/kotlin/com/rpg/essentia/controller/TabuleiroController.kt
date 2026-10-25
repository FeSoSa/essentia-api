package com.rpg.essentia.controller

import com.rpg.essentia.model.MoverPecaRequest
import com.rpg.essentia.model.Tabuleiro
import com.rpg.essentia.service.TabuleiroService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tabuleiro")
class TabuleiroController(private val tabuleiroService: TabuleiroService) {

    @GetMapping
    fun get(): Tabuleiro = tabuleiroService.get()

    @PutMapping
    fun salvar(@RequestBody tabuleiro: Tabuleiro): Tabuleiro = tabuleiroService.salvar(tabuleiro)

    @PostMapping("/pecas/{id}/mover")
    fun mover(@PathVariable id: String, @RequestBody req: MoverPecaRequest): Tabuleiro =
        tabuleiroService.moverPeca(id, req)
}
