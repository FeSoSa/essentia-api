package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Maestria por grupo (categoria de arma ou Essência): todo uso válido em combate conta, nível 1 a 4.
 * Ao bater os usos do próximo nível ele fica "disponível" e o mestre confirma no painel.
 */
@Service
class MaestriaService(
    private val playerRepository: PlayerRepository,
    private val skillRepository: SkillRepository,
    private val essenciaRepository: EssenciaRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun config(): MaestriaConfig = gameStateService.getOrCreate().maestriaConfig

    fun updateConfig(cfg: MaestriaConfig): MaestriaConfig {
        val niveis = cfg.niveis.sortedBy { it.nivel }
        if (niveis.map { it.nivel } != (1..4).toList())
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A tabela precisa dos níveis 1 a 4")
        if (niveis.zipWithNext().any { (a, b) -> b.usos <= a.usos })
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Os usos precisam crescer a cada nível")
        if (niveis.any { it.danoPct < 0 || it.custoPct !in 0..100 })
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Dano não pode ser negativo e custo vai de 0 a 100%")
        val clean = cfg.copy(niveis = niveis.mapIndexed { i, n -> if (i == 0) n.copy(usos = 0) else n })
        gameStateService.save(gameStateService.getOrCreate().copy(maestriaConfig = clean))
        broadcaster.broadcastMaestriaConfig(clean)
        playerRepository.findAll().forEach { broadcaster.broadcastPlayer(it) }
        return clean
    }

    /** Uso válido de skill em combate: conta para o grupo da skill. */
    fun registrarUsoSkill(playerId: String, skillId: String) {
        val skill = skillRepository.findById(skillId).orElse(null) ?: return
        val player = playerRepository.findById(playerId).orElse(null) ?: return
        val (grupo, _) = MaestriaRules.grupoDaSkill(skill, player, essenciaRepository.findAll()) ?: return
        registrar(player, grupo)
    }

    /** Ataque básico aprovado: conta para a maestria da categoria da arma principal (precisa de proficiência). */
    fun registrarUsoArma(playerId: String) {
        val player = playerRepository.findById(playerId).orElse(null) ?: return
        val categoria = player.equipment.mainHand?.weaponType?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        registrar(player, categoria)
    }

    private fun registrar(player: Player, grupo: String) {
        val state = gameStateService.getOrCreate()
        val novas = MaestriaRules.registrarUso(player, grupo, state.combatId, essenciaRepository.findAll()) ?: return
        broadcaster.broadcastPlayer(playerRepository.save(player.copy(maestrias = novas)))
    }

    /** Mestre confirma o nível que ficou disponível. */
    fun confirmarNivel(playerId: String, grupo: String): Player {
        val player = load(playerId)
        val m = MaestriaRules.maestria(player, grupo)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não tem maestria neste grupo")
        val novo = MaestriaRules.confirmarNivel(m, config())
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ainda não há nível disponível para confirmar")
        return salvar(player.copy(maestrias = player.maestrias.map { if (it.grupo == grupo) novo else it }))
    }

    /** Mestre define o nível direto (Essência Elemental não sobe por uso; vale para corrigir qualquer grupo). */
    fun definirNivel(playerId: String, grupo: String, nivel: Int): Player {
        val cfg = config()
        if (nivel !in 1..cfg.nivelMax) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nível de maestria deve ser de 1 a ${cfg.nivelMax}")
        val player = load(playerId)
        if (MaestriaRules.maestria(player, grupo) == null)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não tem maestria neste grupo")
        return salvar(player.copy(maestrias = player.maestrias.map { if (it.grupo == grupo) it.copy(nivel = nivel) else it }))
    }

    /** Correção manual do mestre: soma (ou subtrai) usos, nunca abaixo de 0. */
    fun ajustarUsos(playerId: String, grupo: String, delta: Int): Player {
        val player = load(playerId)
        if (MaestriaRules.maestria(player, grupo) == null)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não tem maestria neste grupo")
        return salvar(player.copy(maestrias = player.maestrias.map {
            if (it.grupo == grupo) it.copy(usos = (it.usos + delta).coerceAtLeast(0)) else it
        }))
    }

    /** Maestrias do jogador já calculadas (nome, bônus, nível disponível). */
    fun infos(player: Player): List<MaestriaGrupoInfo> {
        val cfg = config()
        val essencias = essenciaRepository.findAll()
        return player.maestrias.map { MaestriaRules.info(player, it, cfg, essencias) }
    }

    private fun load(id: String): Player = playerRepository.findById(id).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador não encontrado")
    }

    private fun salvar(player: Player): Player {
        val saved = playerRepository.save(player)
        broadcaster.broadcastPlayer(saved)
        return saved
    }
}
