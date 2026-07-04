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
    val skillName: String? = null
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
    val notes: String = ""
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
    val statusEffects: List<StatusEffect> = emptyList()
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
    val specialAbility: BossAbility? = null
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
    val notes: String = ""
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
    val statusEffects: List<StatusEffect> = emptyList()
)

/* ── DTOs ────────────────────────────────────────────────────── */

data class EnemyHpRequest(val delta: Int = 0)
data class EnemyNotesRequest(val notes: String = "")
data class AddEnemyRequest(val templateId: String? = null, val enemy: EnemyInstance)
data class DropAssignment(val itemName: String = "", val icon: String = "", val playerId: String = "")
data class EnemyDefeatRequest(
    val drops: List<DropAssignment> = emptyList(),
    val distributeXp: Boolean = true,
    val xpAmount: Int? = null
)

data class BossHpRequest(val delta: Int = 0)
data class BossNotesRequest(val notes: String = "")
data class AddBossRequest(val templateId: String? = null, val boss: BossInstance)
data class SpecialRewardAssignment(val referenceId: String = "", val playerId: String = "")
data class BossDefeatRequest(
    val drops: List<DropAssignment> = emptyList(),
    val specialReward: SpecialRewardAssignment? = null,
    val distributeXp: Boolean = true,
    val xpAmount: Int? = null
)
