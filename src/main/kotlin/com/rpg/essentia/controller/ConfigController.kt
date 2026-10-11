package com.rpg.essentia.controller

import com.rpg.essentia.model.AchadoConfig
import com.rpg.essentia.model.CombateConfig
import com.rpg.essentia.model.EncontroConfig
import com.rpg.essentia.model.MaestriaConfig
import com.rpg.essentia.service.GameStateService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Leitura das regras editáveis (mestre e jogadores). A edição fica em /api/master. */
@RestController
@RequestMapping("/api/config")
class ConfigController(private val gameStateService: GameStateService) {
    @GetMapping("/maestria")
    fun maestria(): MaestriaConfig = gameStateService.getOrCreate().maestriaConfig

    @GetMapping("/combate")
    fun combate(): CombateConfig = gameStateService.combateConfig()

    @GetMapping("/achado")
    fun achado(): AchadoConfig = gameStateService.achadoConfig()

    @GetMapping("/encontro")
    fun encontro(): EncontroConfig = gameStateService.encontroConfig()
}
