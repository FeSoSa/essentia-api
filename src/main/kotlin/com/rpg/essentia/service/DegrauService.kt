package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.RaceRepository
import com.rpg.essentia.repository.SkillRepository
import org.springframework.stereotype.Service

/** Liga as regras de fraqueza/resistência (DegrauRules) aos dados do combate: quem ataca com o quê, em quem. */
@Service
class DegrauService(
    private val tipoDanoService: TipoDanoService,
    private val skillRepository: SkillRepository,
    private val raceRepository: RaceRepository,
    private val playerRepository: PlayerRepository,
    private val enemyService: EnemyService,
    private val bossService: BossService,
    private val allyService: AllyService
) {
    /**
     * Tipos do golpe do jogador. Ataque básico usa a mão principal; skill com dano da arma usa a mão dela.
     * Skill de fórmula não olha a arma.
     */
    fun tiposDoJogador(player: Player, skill: Skill?): List<String> {
        val usaArma = skill == null || skill.damageSource == "weapon"
        val arma = if (skill?.weaponSlot == "offHand") player.equipment.offHand else player.equipment.mainHand
        return DegrauRules.tiposDoAtaque(skill?.tiposDano, usaArma, arma?.tiposDano, arma != null)
    }

    /** Ataque de inimigo, boss ou aliado: os tipos dele; sem tipos, os da skill ligada; senão sem tipo (Neutro). */
    fun tiposDoAtaque(ataque: EnemyAttack): List<String> =
        ataque.tiposDano.ifEmpty {
            ataque.skillId?.let { skillRepository.findById(it).orElse(null) }?.tiposDano.orEmpty()
        }

    /** Traços do alvo: (raça ou criatura, armadura de corpo). Só jogadores vestem armadura. */
    fun tracosDoAlvo(targetType: String, targetId: String): Pair<List<TracoDano>, List<TracoDano>> = when (targetType) {
        "player" -> playerRepository.findById(targetId).orElse(null)?.let { p ->
            val raca = raceRepository.findByName(p.char.race)?.tracosDano.orEmpty()
            raca to p.equipment.armor?.tracosDano.orEmpty()
        } ?: (emptyList<TracoDano>() to emptyList())
        "enemy" -> enemyService.findInstance(targetId)?.tracosDano.orEmpty() to emptyList()
        "boss" -> bossService.findInstance(targetId)?.tracosDano.orEmpty() to emptyList()
        "ally" -> allyService.findInstance(targetId)?.tracosDano.orEmpty() to emptyList()
        else -> emptyList<TracoDano>() to emptyList()
    }

    fun resolver(tipos: List<String>, targetType: String, targetId: String): DegrauAplicado {
        if (tipos.isEmpty()) return DegrauAplicado()
        val (raca, armadura) = tracosDoAlvo(targetType, targetId)
        val d = DegrauRules.resolver(tipos, raca, armadura)
        return d.copy(tipoNome = d.tipoDanoId?.let { tipoDanoService.nome(it) })
    }
}
