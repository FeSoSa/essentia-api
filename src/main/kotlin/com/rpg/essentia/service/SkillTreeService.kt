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
    private val attributeService: AttributeService,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun getSkillTree(playerId: String): List<PlayerSkillTreeEntry> {
        val player = loadPlayer(playerId)
        val essencias = essenciaRepository.findAll()
        val effectiveAttrs = attributeService.computeEffectiveAttributes(player, essencias)

        val equippedWeaponTypes = weaponTypesEquipped(player)
        val obtainedEssenciaIds = player.essenciasObtidas.map { it.essenciaId }.toSet()

        val relevantSkills = skillRepository.findAll().filter { skill ->
            when (skill.type) {
                "class"    -> skill.skillClass == null || skill.skillClass == player.char.skillClass
                "weapon"   -> skill.weaponType?.lowercase() in equippedWeaponTypes
                "essencia" -> skill.essenciaId in obtainedEssenciaIds
                else       -> false
            }
        }

        val playerSkills = playerSkillRepository.findByPlayerId(playerId)
        val playerSkillMap = playerSkills.associateBy { it.skillId }
        val unlockedSkillIds = playerSkills.map { it.skillId }.toSet()

        return relevantSkills.map { skill ->
            val entry = computeEntry(skill, player, unlockedSkillIds, effectiveAttrs)
            val ps = playerSkillMap[skill.id]
            toFlat(skill, entry, ps, player)
        }
    }

    fun getMasterSkillTree(playerId: String): List<MasterSkillTreeEntry> {
        val player = loadPlayer(playerId)
        val essencias = essenciaRepository.findAll()
        val effectiveAttrs = attributeService.computeEffectiveAttributes(player, essencias)

        val equippedWeaponTypes = weaponTypesEquipped(player)
        val obtainedEssenciaIds = player.essenciasObtidas.map { it.essenciaId }.toSet()

        val relevantSkills = skillRepository.findAll().filter { skill ->
            when (skill.type) {
                "class"    -> skill.skillClass == null || skill.skillClass == player.char.skillClass
                "weapon"   -> skill.weaponType?.lowercase() in equippedWeaponTypes
                "essencia" -> skill.essenciaId in obtainedEssenciaIds
                "mestre"   -> true   // habilidades de mestre sempre visíveis no painel do mestre
                else       -> false
            }
        }

        val playerSkills = playerSkillRepository.findByPlayerId(playerId)
        val playerSkillMap = playerSkills.associateBy { it.skillId }
        val unlockedSkillIds = playerSkills.map { it.skillId }.toSet()

        return relevantSkills.map { skill ->
            val entry = computeEntry(skill, player, unlockedSkillIds, effectiveAttrs)
            MasterSkillTreeEntry(
                skill      = skill,
                status     = entry.status,
                playerSkill = playerSkillMap[skill.id]
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
        val playerSkill = PlayerSkill(
            id        = UUID.randomUUID().toString(),
            playerId  = playerId,
            skillId   = skillId,
            used      = false,
            equipped  = false,
            slotId    = null,
            maestria  = Maestria(level = 1, totalUses = 0, nextLevelUses = 3, upgrades = emptyList(), computed = MaestriaComputed())
        )
        playerSkillRepository.save(playerSkill)
        broadcaster.broadcastPlayer(player)
        gameStateService.addLogEntry(playerId, "${player.char.name} recebeu ${skill.name}", "skill")
    }

    private fun toFlat(skill: Skill, entry: SkillTreeEntry, ps: PlayerSkill?, player: Player): PlayerSkillTreeEntry {
        val netCostMod = (ps?.maestria?.computed?.custoAumento ?: 0.0) -
                         (ps?.maestria?.computed?.reducaoCusto  ?: 0.0)
        val custo = skill.costs.joinToString(", ") { c ->
            when {
                c.percentual != null -> "${c.percentual}% ${costLabel(c.type)}"
                c.value != null -> {
                    val effective = if (netCostMod != 0.0)
                        maxOf(0, Math.round(c.value * (1.0 + netCostMod)).toInt())
                    else
                        c.value
                    "$effective ${costLabel(c.type)}"
                }
                else -> costLabel(c.type)
            }
        }.ifEmpty { "—" }

        val categoria = when (skill.type) {
            "class"    -> skill.skillClass ?: "Geral"
            "weapon"   -> skill.weaponType ?: "Arma"
            "essencia" -> "Essência"
            else       -> "Geral"
        }

        val effectiveAttrMap = (player.effectiveAttributes ?: player.attributes).toMap()
        val reqText = entry.missing?.let { m ->
            listOfNotNull(
                m.level?.let { "Nível ${player.char.level + it}+" },
                m.attributes?.entries?.joinToString(", ") { (a, v) ->
                    "${attrLabel(a)} ${(effectiveAttrMap[a] ?: 0) + v}+"
                },
                m.weaponRequired?.let { "Arma: $it" }
            ).joinToString(" · ").ifEmpty { null }
        }

        return PlayerSkillTreeEntry(
            skillId          = skill.id ?: "",
            nome             = skill.name,
            custo            = custo,
            descricao        = skill.desc,
            categoria        = categoria,
            unlocked         = entry.status == SkillStatus.UNLOCKED,
            equipped         = ps?.equipped ?: false,
            slotId           = ps?.slotId,
            requirementsText = reqText,
            maestria         = ps?.let { playerSkill ->
                val c = playerSkill.maestria.computed
                MaestriaSimple(
                    playerSkillId = playerSkill.id,
                    level         = playerSkill.maestria.level,
                    totalUses     = playerSkill.maestria.totalUses,
                    nextLevelUses = playerSkill.maestria.nextLevelUses,
                    choices       = playerSkill.maestria.upgrades.map { it.path },
                    bonusDano     = c.bonusDano,
                    custoAumento  = c.custoAumento,
                    reducaoCusto  = c.reducaoCusto,
                )
            },
            isPassive     = skill.passive || !skill.passiveAttributes.isNullOrEmpty(),
            skillType     = skill.type,
            pressaoDice   = skill.pressaoDice,
            danoFormula   = skill.damage?.let { d ->
                d.formula.takeIf { it.isNotBlank() } ?: buildString {
                    if (d.baseFixed != 0) append(d.baseFixed)
                    d.baseDice?.let { dice ->
                        if (isNotEmpty()) append(" + ")
                        append("${dice.quantity}${dice.die}")
                    }
                    d.attribute?.let { attr ->
                        if (isNotEmpty()) append(" + ")
                        append(attr.take(3).uppercase())
                    }
                }.ifEmpty { null }
            },
            danoBase      = skill.damage?.baseFixed?.takeIf { it != 0 },
            cooldownTurns = skill.cooldownTurns
        )
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

    private fun attrLabel(a: String) = when (a) {
        "strength"     -> "FOR"
        "agility"      -> "AGI"
        "intelligence" -> "INT"
        "resistance"   -> "RES"
        "flow"         -> "FLX"
        "wisdom"       -> "SAB"
        "presence"     -> "PRE"
        "defense"      -> "DEF"
        else           -> a
    }

    fun unlockSkill(playerId: String, skillId: String): PlayerSkill {
        val player = loadPlayer(playerId)
        val skill = skillRepository.findById(skillId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found")
        }

        // Validate skill is accessible to this player
        val equippedWeaponTypes = weaponTypesEquipped(player)
        val obtainedEssenciaIds = player.essenciasObtidas.map { it.essenciaId }.toSet()
        val accessible = when (skill.type) {
            "class"    -> skill.skillClass == null || skill.skillClass == player.char.skillClass
            "weapon"   -> skill.weaponType in equippedWeaponTypes
            "essencia" -> skill.essenciaId in obtainedEssenciaIds
            else       -> false
        }
        if (!accessible)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill is not accessible to this player")

        // Remove qualquer PlayerSkill órfão antes de criar novo (garante estado limpo)
        playerSkillRepository.findByPlayerIdAndSkillId(playerId, skillId)?.let {
            playerSkillRepository.delete(it)
        }

        // Validate requirements
        val essencias = essenciaRepository.findAll()
        val effectiveAttrs = attributeService.computeEffectiveAttributes(player, essencias)
        val unlockedSkillIds = playerSkillRepository.findByPlayerId(playerId).map { it.skillId }.toSet()
        val entry = computeEntry(skill, player, unlockedSkillIds, effectiveAttrs)

        if (entry.status == SkillStatus.LOCKED)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Requirements not met: ${entry.missing}"
            )

        // Create PlayerSkill at maestria level 1
        val playerSkill = PlayerSkill(
            id = UUID.randomUUID().toString(),
            playerId = playerId,
            skillId = skillId,
            used = false,
            equipped = false,
            slotId = null,
            maestria = Maestria(
                level = 1,
                totalUses = 0,
                nextLevelUses = 3,
                upgrades = emptyList(),
                computed = MaestriaComputed()
            )
        )
        val saved = playerSkillRepository.save(playerSkill)

        // Broadcast player so frontend refreshes skill tree
        broadcaster.broadcastPlayer(player)
        gameStateService.addLogEntry(playerId, "${player.char.name} desbloqueou ${skill.name}", "skill")

        return saved
    }

    private fun computeEntry(
        skill: Skill,
        player: Player,
        unlockedSkillIds: Set<String>,
        effectiveAttrs: Attributes
    ): SkillTreeEntry {
        if (skill.id in unlockedSkillIds)
            return SkillTreeEntry(skill, SkillStatus.UNLOCKED, null)

        val req = skill.requirements
            ?: return SkillTreeEntry(skill, SkillStatus.AVAILABLE, null)

        val attrMap = effectiveAttrs.toMap()

        val missingLevel = req.level?.let { needed ->
            if (player.char.level < needed) needed - player.char.level else null
        }
        val missingAttrs = req.attributes
            ?.mapNotNull { (attr, needed) ->
                val have = attrMap[attr] ?: 0
                if (have < needed) attr to (needed - have) else null
            }
            ?.toMap()
            ?.ifEmpty { null }
        val missingWeapon = req.weaponRequired?.let { needed ->
            val equipped = weaponTypesEquipped(player)
            if (needed.lowercase() !in equipped) needed else null
        }

        return if (missingLevel == null && missingAttrs == null && missingWeapon == null) {
            SkillTreeEntry(skill, SkillStatus.AVAILABLE, null)
        } else {
            SkillTreeEntry(skill, SkillStatus.LOCKED, MissingRequirements(missingLevel, missingAttrs, missingWeapon))
        }
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
