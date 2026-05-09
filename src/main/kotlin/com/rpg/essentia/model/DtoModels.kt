package com.rpg.essentia.model

// Auth
data class LoginRequest(val code: String)

// Player vitals
data class DeltaRequest(val delta: Int)
data class AttributeDeltaRequest(val attribute: String, val delta: Int)

// Skills
data class UseSkillRequest(val slotId: String)
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
    val damageDice: Dice? = null,
    val damageAttribute: String? = null,
    val properties: String? = null,
    val damageReduction: Int? = null,
    val attributeBonus: Map<String, Int>? = null,
    val equipSlot: String? = null
)
data class AdjustItemQtyRequest(val delta: Int)

// Images
data class ImageCreateRequest(val url: String, val title: String)
data class ImageUpdateRequest(val url: String, val title: String)

// Fast action
data class VoteRequest(val playerId: String, val optionId: String)

// Responses
data class DamageResult(
    val skillName: String,
    val damage: Int?,
    val costPaid: List<Cost>,
    val cooldownSet: Int
)

data class TurnUpdate(val message: String)

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
    val maestria: MaestriaSimple?
)

data class MaestriaSimple(
    val level: Int,
    val totalUses: Int,
    val nextLevelUses: Int
)

// DTO interno — usado apenas no SkillTreeService
data class SkillTreeEntry(
    val skill: Skill,
    val status: SkillStatus,
    val missing: MissingRequirements?
)
