package com.rpg.essentia.mundo

import org.springframework.web.bind.annotation.*

/** Toda rota devolve o snapshot inteiro, igual à Campanha. */
@RestController
@RequestMapping("/api/mundo")
class MundoController(private val files: MundoFileService) {

    @GetMapping
    fun snapshot(): MundoSnapshot = files.snapshot()

    @PostMapping("/paginas")
    fun criar(@RequestBody req: MundoPaginaRequest): MundoSnapshot { files.criar(req); return files.snapshot() }

    @PutMapping("/paginas/{id}")
    fun atualizar(@PathVariable id: String, @RequestBody req: MundoPaginaRequest): MundoSnapshot { files.atualizar(id, req); return files.snapshot() }

    @DeleteMapping("/paginas/{id}")
    fun remover(@PathVariable id: String): MundoSnapshot { files.remover(id); return files.snapshot() }

    @PostMapping("/categorias")
    fun criarCategoria(@RequestBody req: MundoCategoriaRequest): MundoSnapshot { files.criarCategoria(req.nome); return files.snapshot() }

    @DeleteMapping("/categorias/{nome}")
    fun removerCategoria(@PathVariable nome: String): MundoSnapshot { files.removerCategoria(nome); return files.snapshot() }
}
