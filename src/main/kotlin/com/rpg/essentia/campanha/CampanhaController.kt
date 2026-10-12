package com.rpg.essentia.campanha

import org.springframework.web.bind.annotation.*

/** Toda rota devolve o snapshot inteiro; o volume é pequeno e o painel fica sempre coerente. */
@RestController
@RequestMapping("/api/campanha")
class CampanhaController(
    private val service: CampanhaService,
    private val files: CampanhaFileService,
    private val estado: CampanhaEstadoService,
    private val grafo: CampanhaGrafoService
) {
    @GetMapping
    fun snapshot(): CampanhaSnapshot = service.snapshot()

    /* ── Capítulos ── */

    @PostMapping("/capitulos")
    fun criarCapitulo(@RequestBody req: CapituloRequest) = service.alterar { files.criarCapitulo(req) }

    @PutMapping("/capitulos/{id}")
    fun atualizarCapitulo(@PathVariable id: String, @RequestBody req: CapituloRequest) =
        service.alterar { files.atualizarCapitulo(id, req) }

    @DeleteMapping("/capitulos/{id}")
    fun removerCapitulo(@PathVariable id: String) = service.alterar { files.removerCapitulo(id) }

    /* ── Pontos (plano, nos .md) ── */

    @PostMapping("/pontos")
    fun criarPonto(@RequestBody p: CampanhaPonto) = service.alterar { files.criarPonto(p) }

    @PutMapping("/pontos/{id}")
    fun atualizarPonto(@PathVariable id: String, @RequestBody p: CampanhaPonto) =
        service.alterar { files.atualizarPonto(id, p) }

    @PutMapping("/pontos/{id}/posicao")
    fun moverPonto(@PathVariable id: String, @RequestBody req: PosicaoRequest) =
        service.alterar { files.moverPonto(id, req) }

    @DeleteMapping("/pontos/{id}")
    fun removerPonto(@PathVariable id: String) = service.removerPonto(id)

    /* ── Visão em grafo (_grafo.json) ── */

    @PutMapping("/grafo/posicoes")
    fun posicionar(@RequestBody req: GrafoRequest) = service.alterar { grafo.posicionar(req.posicoes) }

    @DeleteMapping("/grafo/posicoes")
    fun soltarTodos() = service.alterar { grafo.soltarTodos() }

    /* ── Estado da sessão (_estado.json) ── */

    @PostMapping("/pontos/{id}/ativar")
    fun ativar(@PathVariable id: String) = service.alterar { estado.ativar(id) }

    @PostMapping("/pontos/{id}/concluir")
    fun concluir(@PathVariable id: String) = service.alterar { estado.concluir(id) }

    @PostMapping("/pontos/{id}/reabrir")
    fun reabrir(@PathVariable id: String) = service.alterar { estado.reabrir(id) }

    @PostMapping("/pontos/{id}/mesa")
    fun registrarMesa(@PathVariable id: String, @RequestBody req: MesaRequest) =
        service.alterar { estado.registrarMesa(id, req.entradas) }

    @PostMapping("/pontos/{id}/achado")
    fun registrarAchado(@PathVariable id: String, @RequestBody achado: Achado) =
        service.alterar { estado.registrarAchado(id, achado) }
}
