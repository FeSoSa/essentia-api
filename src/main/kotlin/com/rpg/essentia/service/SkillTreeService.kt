package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.repository.SkillRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class SkillTreeService(
    private val skillRepository: SkillRepository,
    private val playerSkillRepository: PlayerSkillRepository,
    private val playerRepository: PlayerRepository,
    private val essenciaRepository: EssenciaRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun getSkillTree(playerId: String): List<PlayerSkillTreeEntry> {
        val player = loadPlayer(playerId)
        val essencias = essenciaRepository.findAll()
        val playerSkills = playerSkillRepository.findByPlayerId(playerId)
        val playerSkillMap = playerSkills.associateBy { it.skillId }
        return skillRepository.findAll()
            .filter { acessivel(it, player, essencias) }
            .map { toFlat(it, playerSkillMap[it.id], player, essencias) }
    }

    fun getMasterSkillTree(playerId: String): List<MasterSkillTreeEntry> {
        val player = loadPlayer(playerId)
        val essencias = essenciaRepository.findAll()
        val cfg = gameStateService.getOrCreate().maestriaConfig
        val playerSkillMap = playerSkillRepository.findByPlayerId(playerId).associateBy { it.skillId }
        return skillRepository.findAll()
            .filter { it.type == "mestre" || acessivel(it, player, essencias) }   // skills de mestre sempre visíveis no painel
            .map { skill ->
                val ps = playerSkillMap[skill.id]
                val bloqueio = MaestriaRules.requisito(skill, player, essencias)
                MasterSkillTreeEntry(
                    skill       = skill,
                    status      = when { ps != null -> SkillStatus.UNLOCKED; bloqueio != null -> SkillStatus.LOCKED; else -> SkillStatus.AVAILABLE },
                    playerSkill = ps,
                    maestria    = maestriaDaSkill(skill, player, cfg, essencias),
                    bloqueio    = bloqueio
                )
            }
    }

    fun grantMasterSkill(playerId: String, skillId: String) {
        val player = loadPlayer(playerId)
        val skill  = skillRepository.findById(skillId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found")
        }
        // Remove PlayerSkill órfão se existir
        playerSkillRepository.findByPlayerIdAndSkillId(playerId, skillId)?.let {
            playerSkillRepository.delete(it)
        }
        playerSkillRepository.save(PlayerSkill(id = UUID.randomUUID().toString(), playerId = playerId, skillId = skillId))
        broadcaster.broadcastPlayer(player)
        gameStateService.addLogEntry(playerId, "${player.char.name} recebeu ${skill.name}", "skill")
    }

    fun unlockSkill(playerId: String, skillId: String): PlayerSkill {
        val player = loadPlayer(playerId)
        val skill = skillRepository.findById(skillId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found")
        }
        val essencias = essenciaRepository.findAll()
        if (!acessivel(skill, player, essencias))
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill is not accessible to this player")

        // Requisito por origem e tier (nível de classe, nível da Essência ou maestria da arma)
        MaestriaRules.requisito(skill, player, essencias)?.let {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, it)
        }

        // Remove qualquer PlayerSkill órfão antes de criar novo (garante estado limpo)
        playerSkillRepository.findByPlayerIdAndSkillId(playerId, skillId)?.let {
            playerSkillRepository.delete(it)
        }
        val saved = playerSkillRepository.save(PlayerSkill(id = UUID.randomUUID().toString(), playerId = playerId, skillId = skillId))

        // Broadcast player so frontend refreshes skill tree
        broadcaster.broadcastPlayer(player)
        gameStateService.addLogEntry(playerId, "${player.char.name} desbloqueou ${skill.name}", "skill")
        return saved
    }

    /** Skill que aparece para o jogador: da classe dele, de arma equipada ou de Essência acessível. */
    private fun acessivel(skill: Skill, player: Player, essencias: List<Essencia>): Boolean = when (skill.type) {
        "class"    -> skill.skillClass == null || skill.skillClass == player.char.skillClass
        "weapon"   -> skill.categoriasArma().any { it in weaponTypesEquipped(player) }
        "essencia" -> MaestriaRules.essenciaAcessivel(skill.essenciaId, player, essencias)
        else -> false
    }

    private fun maestriaDaSkill(skill: Skill, player: Player, cfg: MaestriaConfig, essencias: List<Essencia>): MaestriaGrupoInfo? {
        val (grupo, _) = MaestriaRules.grupoDaSkill(skill, player, essencias) ?: return null
        return MaestriaRules.maestria(player, grupo)?.let { MaestriaRules.info(player, it, cfg, essencias) }
    }

    private fun toFlat(skill: Skill, ps: PlayerSkill?, player: Player, essencias: List<Essencia>): PlayerSkillTreeEntry {
        val maestriaCfg = gameStateService.getOrCreate().maestriaConfig
        val maestria = maestriaDaSkill(skill, player, maestriaCfg, essencias)
        val reducaoMaestria = if (maestria?.ativa == true) maestria.custoPct / 100.0 else 0.0
        val custo = skill.costs.joinToString(", ") { c ->
            val reducao = reducaoMaestria + player.statusEffects.resourceCostModifierPercent(c.type)
            when {
                c.percentual != null -> "${c.percentual}% ${costLabel(c.type)}"
                c.value != null -> "${CombatRules.custoFinal(c.value, reducao)} ${costLabel(c.type)}"
                else -> costLabel(c.type)
            }
        }.ifEmpty { "sem custo" }

        val categoria = when (skill.type) {
            "class"    -> skill.skillClass ?: "Geral"
            "weapon"   -> skill.weaponTypes?.joinToString("/") ?: skill.weaponType ?: "Arma"
            "essencia" -> "Essência"
            else       -> "Geral"
        }

        val weapon = if (skill.damageSource == "weapon") MaestriaRules.armaDaSkill(skill, player.equipment) else null
        val combate = gameStateService.combateConfig()
        val equilibrio = skill.equilibrio(combate)
            ?: if (skill.damageSource == "weapon") ProficienciaRules.equilibrioAtaqueBasico(weapon, player.proficiencias, combate) else null
        val bloqueio = MaestriaRules.requisito(skill, player, essencias)
        val semProficiencia = ProficienciaRules.categoriaSemProficiencia(skill, player.equipment, player.proficiencias)

        return PlayerSkillTreeEntry(
            skillId          = skill.id ?: "",
            nome             = skill.name,
            custo            = custo,
            custoBase        = skill.costs.firstOrNull { it.type == "flow" }?.value,
            descricao        = skill.desc,
            categoria        = categoria,
            unlocked         = ps != null,
            equipped         = ps?.equipped ?: false,
            slotId           = ps?.slotId,
            requirementsText = bloqueio,
            maestria         = maestria,
            isPassive     = skill.passive || skill.efeitos.any { it.passivo },
            skillType     = skill.type,
            origem        = origem(skill.type),
            alvo          = skill.alvo,
            grupoMaestria = MaestriaRules.grupoDaSkill(skill, player, essencias)?.first,
            pressaoDice   = skill.pressaoDice,
            danoFormula   = if (skill.damageSource == "weapon") {
                "Arma (${if (skill.weaponSlot == "offHand") "mão secundária" else "mão principal"})"
            } else skill.damage?.let { d ->
                TierRules.formula(d.baseFixed, d.atributo, equilibrio).ifEmpty { null }
            },
            danoBase          = if (skill.damageSource == "weapon") weapon?.damageBase else skill.damage?.baseFixed?.takeIf { it != 0 },
            atributo          = if (skill.damageSource == "weapon") weapon?.damageAttribute else skill.damage?.atributo,
            equilibrio        = equilibrio,
            tier              = skill.tier,
            semProficiencia   = semProficiencia,
            bloqueio          = bloqueio ?: semProficiencia?.let { "Sem proficiência em $it" },
            damageSource      = skill.damageSource,
            weaponSlot        = skill.weaponSlot,
            weaponDamageModifiers = skill.weaponDamageModifiers,
            cooldownTurns     = skill.cooldownTurns,
            critThreshold     = skill.critThreshold,
            actionType        = skill.actionType,
            multiTarget       = skill.multiTarget,
            efeitos           = skill.efeitos
        )
    }

    private fun origem(type: String): String? = when (type) {
        "class" -> "CLASSE"; "weapon" -> "ARMA"; "essencia" -> "ESSENCIA"; else -> null
    }

    private fun costLabel(type: String) = when (type) {
        "flow"            -> "FLX"
        "hp"              -> "HP"
        "ether"           -> "ÉTER"
        "charge"          -> "Carga"
        "percentual_flow" -> "FLX"
        "percentual_hp"   -> "HP"
        "pressao"         -> "Pressão"
        else              -> type
    }

    private fun weaponTypesEquipped(player: Player): Set<String> =
        listOfNotNull(
            player.equipment.mainHand?.weaponType?.takeIf { it.isNotBlank() }?.lowercase(),
            player.equipment.offHand?.weaponType?.takeIf { it.isNotBlank() }?.lowercase()
        ).toSet()

    private fun loadPlayer(id: String): Player =
        playerRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found")
        }
}
