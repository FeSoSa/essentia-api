package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

/* ── Shared ──────────────────────────────────────────────────── */

data class EnemyAttack(
    val name: String = "",
    val damage: String = "",         // fórmula legível gerada pelo frontend (ex: "15 + d20×FOR/4")
    val damageBase: Int? = null,     // dano fixo base (ataque básico)
    val damageAttribute: String? = null, // "FOR" | "AGI" | "INT"
    val equilibrio: Int? = null,     // divisor da escala de atributo
    val skillId: String? = null,
    val skillName: String? = null,
    val custoEs: Int = 0             // ES gasta pelo inimigo ao usar este ataque
)

data class GoldDrop(
    val tier: String = "comum",
    val min: Int = 5,
    val max: Int = 30
)

data class EnemyDrop(
    val name: String = "",
    val icon: String = "",
    val itemId: String? = null,
    val targetPlayerId: String? = null,
    val goldDrop: GoldDrop? = null
)

data class EnemyAttributes(val strength: Int = 10, val agility: Int = 10, val intelligence: Int = 10, val defense: Int = 5)

/* ── Enemy catalog template ──────────────────────────────────── */

@Document(collection = "enemy_templates")
data class EnemyTemplate(
    @Id val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: String = "",
    val icon: String = "👹",
    val imageUrl: String? = null,
    val hpMax: Int = 20,
    val attributes: EnemyAttributes = EnemyAttributes(),
    val attacks: List<EnemyAttack> = emptyList(),
    val drops: List<EnemyDrop> = emptyList(),
    val immunities: List<BossImmunity> = emptyList(),
    val xp: Int = 0,
    val desc: String = "",
    val notes: String = "",
    val defesa: Int? = null,          // DEF contra o acerto dos jogadores (null = attributes.defense)
    val modAcerto: Int? = null,       // bônus no d20 de acerto (null = mod de FOR/AGI)
    val esMax: Int = 0,               // ES disponível para os ataques
    val negaCritico: Boolean = false, // nega um crítico por rodada
    val dificuldade: String? = null   // declarada pelo mestre: facil, medio, dificil, mortal (o gerador de encontros respeita)
)

/* ── Enemy combat instance ───────────────────────────────────── */

@Document(collection = "combat_enemies")
data class EnemyInstance(
    @Id val instanceId: String = UUID.randomUUID().toString(),
    val templateId: String? = null,
    val name: String = "",
    val type: String = "",
    val icon: String = "👹",
    val imageUrl: String? = null,
    val hpCurrent: Int = 20,
    val hpMax: Int = 20,
    val attributes: EnemyAttributes = EnemyAttributes(),
    val attacks: List<EnemyAttack> = emptyList(),
    val drops: List<EnemyDrop> = emptyList(),
    val immunities: List<BossImmunity> = emptyList(),
    val xp: Int = 0,
    val desc: String = "",
    val notes: String = "",
    val statusEffects: List<StatusEffect> = emptyList(),
    val defesa: Int? = null,
    val modAcerto: Int? = null,
    val esMax: Int = 0,
    val esCurrent: Int? = null,       // null = cheio (esMax)
    val negaCritico: Boolean = false,
    val negaCriticoRodada: Int? = null // rodada em que a negação já foi usada
)

/* ── Boss models ─────────────────────────────────────────────── */

data class BossAbility(
    val name: String = "",
    val desc: String = "",
    val cooldownTurns: Int = 0
)

data class BossImmunity(
    val type: String = "",
    val icon: String = "",
    val kind: String = "total",
    val sources: List<String> = emptyList(),
    val isFlag: Boolean = false
)
data class BossResistance(val type: String = "", val reduction: Int = 50)
data class BossReward(
    val type: String = "essencia",
    val referenceId: String = "",
    val name: String = "",
    val desc: String = ""
)

data class BossPhase(
    val phaseNumber: Int = 1,
    val name: String = "",
    val hpMax: Int = 100,
    val attributes: Attributes = Attributes(),
    val attacks: List<EnemyAttack> = emptyList(),
    val specialAbility: BossAbility? = null,
    val defesa: Int? = null,
    val modAcerto: Int? = null,
    val esMax: Int = 0
)

@Document(collection = "boss_templates")
data class BossTemplate(
    @Id val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: String = "Boss",
    val icon: String = "👑",
    val imageUrl: String? = null,
    val phases: List<BossPhase> = emptyList(),
    val immunities: List<BossImmunity> = emptyList(),
    val resistances: List<BossResistance> = emptyList(),
    val drops: List<EnemyDrop> = emptyList(),
    val xp: Int = 500,
    val specialReward: BossReward? = null,
    val notes: String = "",
    val negaCritico: Boolean = false
)

@Document(collection = "combat_bosses")
data class BossInstance(
    @Id val instanceId: String = UUID.randomUUID().toString(),
    val templateId: String? = null,
    val name: String = "",
    val icon: String = "👑",
    val imageUrl: String? = null,
    val phases: List<BossPhase> = emptyList(),
    val currentPhase: Int = 0,
    val hpCurrent: Int = 100,
    val immunities: List<BossImmunity> = emptyList(),
    val resistances: List<BossResistance> = emptyList(),
    val drops: List<EnemyDrop> = emptyList(),
    val xp: Int = 500,
    val specialReward: BossReward? = null,
    val notes: String = "",
    val statusEffects: List<StatusEffect> = emptyList(),
    val esCurrent: Int? = null,       // null = cheio (esMax da fase atual)
    val negaCritico: Boolean = false,
    val negaCriticoRodada: Int? = null
)

/* ── DTOs ────────────────────────────────────────────────────── */

data class EnemyHpRequest(val delta: Int = 0)
data class EnemyNotesRequest(val notes: String = "")
data class AddEnemyRequest(val templateId: String? = null, val enemy: EnemyInstance)
data class DropAssignment(val itemName: String = "", val icon: String = "", val itemId: String? = null, val playerId: String = "")
/** Ouro sorteado na derrota: dividido entre os jogadores ativos ou para um só. Null = ninguém recebe. */
data class GoldAssignment(val amount: Int = 0, val distribute: Boolean = false, val playerId: String = "")
data class EnemyDefeatRequest(
    val drops: List<DropAssignment> = emptyList(),
    val distributeXp: Boolean = true,
    val xpPlayerId: String? = null,   // XP só para este jogador (distributeXp = false)
    val xpAmount: Int? = null,
    val goldDrop: GoldAssignment? = null
)

data class BossHpRequest(val delta: Int = 0)
data class BossNotesRequest(val notes: String = "")
data class AddBossRequest(val templateId: String? = null, val boss: BossInstance)
data class SpecialRewardAssignment(val referenceId: String = "", val playerId: String = "")
data class BossDefeatRequest(
    val drops: List<DropAssignment> = emptyList(),
    val specialReward: SpecialRewardAssignment? = null,
    val distributeXp: Boolean = true,
    val xpAmount: Int? = null,
    val goldDrop: GoldAssignment? = null
)

/* ── Turno automático dos inimigos ───────────────────────────── */

@Document(collection = "combat_actions")
data class EnemyAction(
    @Id val id: String = UUID.randomUUID().toString(),
    val combatId: String? = null,
    val rodada: Int = 0,
    val status: String = "PROPOSTA",       // PROPOSTA | AGUARDANDO_JOGADOR | CONCLUIDA | PULADA | ERROU
    val entityId: String = "",
    val entityKind: String = "enemy",      // "enemy" | "boss" | "ally"
    val entityName: String = "",
    val alvoId: String = "",
    val alvoKind: String = "player",       // "player" (ataque de inimigo) | "enemy" | "boss" (ataque de aliado)
    val alvoNome: String = "",
    val ataqueIdx: Int = 0,
    val ataqueNome: String = "",
    val formula: String = "",
    val d20Acerto: Int = 0,
    val modAcerto: Int = 0,
    val totalAcerto: Int = 0,
    val defAlvo: Int = 0,
    val acertou: Boolean = false,
    val d20Dano: Int? = null,
    val dano: Int? = null,
    val custoEs: Int = 0,
    val desviou: Boolean? = null,
    val desvioRoll: Int? = null,
    val desvioTotal: Int? = null,
    val danoAplicado: Int? = null,
    val decisaoMestre: String? = null,     // PERMITIU | TROCOU | PULOU
    val trocas: Int = 0,
    val criadoEm: String = java.time.Instant.now().toString()
)

data class EnemyTurnProposeRequest(val entityId: String, val entityKind: String = "enemy", val alvoId: String? = null, val ataqueIdx: Int? = null)
data class EnemyTurnSwapRequest(val alvoId: String? = null, val ataqueIdx: Int? = null)
data class EnemyAttackResponse(val desviar: Boolean = false, val roll: Int? = null, val sorte: Int? = null)
