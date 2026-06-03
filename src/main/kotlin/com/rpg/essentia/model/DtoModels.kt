package com.rpg.essentia.model

// Auth
data class LoginRequest(val code: String)

// Player vitals
data class DeltaRequest(val delta: Int)
data class AttributeDeltaRequest(val attribute: String, val delta: Int)

// Skills
data class UseSkillRequest(val slotId: String, val diceRoll: Int? = null)
data class SlotUpdateRequest(val slotId: String, val skillId: String?)
data class MaestriaUpgradeRequest(val playerSkillId: String, val path: String)

// Items
data class RequestItemRequest(val itemId: String)
data class EquipItemRequest(val itemId: String)

// Player creation
data class CreatePlayerRequest(
    val code: String,
    val name: String,
    val skillClass: String,
    val subClass: String?,
    val race: String,
    val attributes: Attributes,
    val equipment: Equipment,
    val items: List<Item>,
    val slotsClass: Int = 2,
    val slotsFree: Int = 6,
    val etherUnlocked: Boolean = false
)

// Player update (master edits name/code/race/class/attributes/slots/ether)
data class UpdatePlayerRequest(
    val code: String,
    val name: String,
    val skillClass: String,
    val subClass: String?,
    val race: String,
    val attributes: Attributes,
    val portraitUrl: String? = null,
    val level: Int? = null,
    val slotsClass: Int? = null,
    val slotsFree: Int? = null,
    val etherUnlocked: Boolean? = null,
    val sobrecargaDesbloqueada: Boolean? = null,
    val expAvailable: Int? = null,
    val expTotal: Int? = null
)

// Master
data class EnemyAttackRequest(val targetPlayerId: String, val damage: Int)
data class ApproveRejectItemRequest(val playerId: String, val requestId: String)
data class ExpRequest(val playerId: String, val amount: Int)
data class ResetSkillsRequest(val playerId: String?)
data class StatusEffectRequest(val playerId: String, val effect: StatusEffect)
data class StatusEffectDeleteRequest(val playerId: String)
data class MaestriaUsesRequest(val playerSkillId: String, val uses: Int)

// Equipment slot management
data class SetEquipmentRequest(
    val weapon: WeaponEquip? = null,
    val armor: ArmorEquip? = null,
    val accessory: AccessoryEquip? = null
)

// Essências
data class GrantEssenciaRequest(val essenciaId: String)

// Master item management
data class AddItemRequest(
    val name: String,
    val desc: String = "",
    val qty: Int = 1,
    val type: String = "consumable",
    val icon: String? = null,
    val weaponType: String? = null,
    val damageBase: Int? = null,
    val damageAttribute: String? = null,
    val equilibrio: Int? = null,
    val properties: String? = null,
    val damageReduction: Int? = null,
    val armorWeight: String? = null,
    val attributeBonus: Map<String, Int>? = null,
    val equipSlot: String? = null,
    val rarity: String? = null,
    val twoHanded: Boolean? = null,
    val requirements: ItemRequirements? = null,
    val onUseEffect: OnUseEffect? = null
)
data class AdjustItemQtyRequest(val delta: Int)
data class TransferItemRequest(val itemId: String, val targetPlayerId: String, val qty: Int = 1)

// Images
data class ImageCreateRequest(val url: String, val title: String)
data class ImageUpdateRequest(val url: String, val title: String)

// Fast action
data class VoteRequest(val playerId: String, val optionId: String)

// Damage approval
data class DamageApprovalRequest(
    val requestId: String,
    val playerId: String,
    val playerName: String,
    val targetId: String,
    val targetType: String,   // "enemy" | "boss"
    val targetName: String,
    val damage: Int,
    val costs: Map<String, Int> = emptyMap(),
    val onHitEffects: List<StatusEffect> = emptyList()
)
data class DamageRequestBody(
    val requestId: String,
    val targetId: String,
    val targetType: String,
    val targetName: String,
    val damage: Int,
    val costs: Map<String, Int> = emptyMap(),
    val skillId: String? = null
)
data class DamageApproveBody(
    val requestId: String,
    val playerId: String,
    val targetId: String,
    val targetType: String,
    val damage: Int,
    val costs: Map<String, Int> = emptyMap(),  // custo resolvido a debitar ao aprovar
    val onHitEffects: List<StatusEffect> = emptyList()
)
data class DamageResultNotification(val requestId: String, val approved: Boolean)

// Sobrecarga
data class SobrecargaRequestBody(val nivel: Int)
data class SobrecargaRejectRequest(val roll: Int, val danoDado: String)
data class SobrecargaResult(
    val approved: Boolean,
    val nivel: Int,
    val roll: Int? = null,
    val cd: Int? = null,
    val damageTaken: Int? = null
)
data class SobrecargaBroadcast(
    val playerId: String,
    val playerName: String,
    val nivel: Int,
    val cd: Int,
    val sabMod: Int
)

// Responses
data class DamageResult(
    val skillName: String,
    val damage: Int?,
    val costPaid: List<Cost>,
    val cooldownSet: Int
)

data class TurnUpdate(val message: String, val currentTurnIndex: Int = 0, val totalTurns: Int = 0)

// Skill tree
data class UnlockSkillRequest(val skillId: String)

enum class SkillStatus { UNLOCKED, AVAILABLE, LOCKED }

data class MissingRequirements(
    val level: Int?,
    val attributes: Map<String, Int>?,
    val weaponRequired: String?
)

// Flat DTO para o app do jogador
data class PlayerSkillTreeEntry(
    val skillId: String,
    val nome: String,
    val custo: String,
    val descricao: String,
    val categoria: String,
    val unlocked: Boolean,
    val equipped: Boolean,
    val slotId: String?,
    val requirementsText: String?,
    val maestria: MaestriaSimple?,
    val isPassive: Boolean = false,
    val danoFormula: String? = null,
    val danoBase: Int? = null,
    val atributo: String? = null,
    val equilibrio: Int? = null,
    val cooldownTurns: Int = 0,
    val skillType: String = "class",  // "class" | "weapon" | "essencia" | "mestre"
    val pressaoDice: Boolean = false, // técnica consome Pressão e ganha +1d6 de dano por ponto
    val buffDurationTurns: Int? = null,
    val hitBonus: Int? = null,
    val attackBonus: Int? = null,
    val damageBonus: Int? = null,
    val toggle: Boolean = false,
    val critThreshold: Int? = null,
    val actionType: String = "main",  // "main" | "bonus" | "both"
    val multiTarget: MultiTarget? = null
)

data class MaestriaSimple(
    val playerSkillId: String,
    val level: Int,
    val totalUses: Int,
    val nextLevelUses: Int,
    val choices: List<String>,  // "aumento" | "otimizacao" por nível, na ordem
    val bonusDano: Double,      // ex: 0.32 = +32%
    val custoAumento: Double,   // ex: 0.24 = +24%
    val reducaoCusto: Double,   // ex: 0.16 = -16%
)

// DTO para o app do mestre — árvore de habilidades com status e playerSkill
data class MasterSkillTreeEntry(
    val skill: Skill,
    val status: SkillStatus,
    val playerSkill: PlayerSkill?
)

// DTO interno — usado apenas no SkillTreeService
data class SkillTreeEntry(
    val skill: Skill,
    val status: SkillStatus,
    val missing: MissingRequirements?
)
