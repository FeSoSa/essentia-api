package com.rpg.essentia.model

// Auth
data class LoginRequest(val code: String)

// Player vitals
data class DeltaRequest(val delta: Int)
/** Sorte: o d6 somado a uma rolagem de d20; contexto é acerto, desvio ou teste. */
data class SorteRequest(val d6: Int, val contexto: String = "teste")
data class AttributeDeltaRequest(val attribute: String, val delta: Int)

// Skills
data class UseSkillRequest(val slotId: String, val diceRoll: Int? = null, val commit: Boolean = true, val critico: Boolean = false)
data class SlotUpdateRequest(val slotId: String, val skillId: String?)
data class SkillMissRequest(val slotId: String? = null, val costs: Map<String, Int> = emptyMap())

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
    val etherUnlocked: Boolean = false,
    val inventorySize: Int = 18,
    val perfil: Perfil = Perfil()
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
    val expTotal: Int? = null,
    val inventorySize: Int? = null,
    val perfil: Perfil? = null      // as anotações do jogador são mantidas, o mestre não as sobrescreve
)

data class AnotacoesRequest(val texto: String)

/** Jogador entra num combate já em andamento, com a iniciativa rolada. */
data class EntrarNaLutaRequest(val playerId: String, val valor: Int)
data class SairDaLutaRequest(val playerId: String)

// Master
data class EnemyAttackRequest(val targetPlayerId: String, val damage: Int)
data class ApproveRejectItemRequest(val playerId: String, val requestId: String)
data class ExpRequest(val playerId: String, val amount: Int)
data class ResetSkillsRequest(val playerId: String?)
data class StatusEffectRequest(val playerId: String, val effect: StatusEffect)
data class StatusEffectDeleteRequest(val playerId: String)
data class MaestriaUsesRequest(val uses: Int)
data class NivelClasseRequest(val nivel: Int)
data class SetSkillBlockedRequest(val blocked: Boolean)

// Equipment slot management
data class SetEquipmentRequest(
    val weapon: WeaponEquip? = null,
    val armor: ArmorEquip? = null,
    val accessory: AccessoryEquip? = null
)

// Essências
data class GrantEssenciaRequest(val essenciaId: String)
data class NivelEssenciaRequest(val nivel: Int)
data class ProficienciasRequest(val proficiencias: List<String>)

// Master item management
data class AddItemRequest(
    val catalogId: String? = null,
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
    val requisitoNarrativo: String? = null,
    val onUseEffect: OnUseEffect? = null,
    val goldValue: Int? = null,
    val margemCritico: Int? = null,
    val negaCritico: Boolean? = null
)
data class AdjustItemQtyRequest(val delta: Int)
data class TransferItemRequest(val itemId: String, val targetPlayerId: String, val qty: Int = 1)

// Images
data class ImageCreateRequest(val url: String, val title: String)
data class ImageUpdateRequest(val url: String, val title: String)

// Documents
data class DocumentCreateRequest(val url: String, val title: String)
data class DocumentUpdateRequest(val url: String, val title: String)

// Fast action
data class VoteRequest(val playerId: String, val optionId: String)

// Damage approval
data class DamageApprovalRequest(
    val requestId: String,
    val playerId: String,
    val playerName: String,
    val targetId: String,
    val targetType: String,   // "enemy" | "boss" | "player" | "ally" (fogo amigo)
    val targetName: String,
    val damage: Int,
    val costs: Map<String, Int> = emptyMap(),
    val onHitEffects: List<StatusEffect> = emptyList(),
    val slotId: String? = null,
    val skillId: String? = null,
    val critico: Boolean = false,          // acerto crítico (d20 de acerto >= margem)
    val danoSemCritico: Int? = null,       // dano da mesma rolagem sem o crítico (usado se o alvo negar)
    val ataqueBasico: Boolean = false,     // ataque básico: recupera ES ao acertar
    val degrau: DegrauAplicado? = null     // prévia da fraqueza/resistência do alvo (o servidor recalcula ao aprovar)
)
data class DamageRequestBody(
    val requestId: String,
    val targetId: String,
    val targetType: String,
    val targetName: String,
    val damage: Int,
    val costs: Map<String, Int> = emptyMap(),
    val skillId: String? = null,
    val slotId: String? = null,
    val critico: Boolean = false,
    val danoSemCritico: Int? = null,
    val ataqueBasico: Boolean = false
)
/** Skill em área: um pedido com vários alvos (o servidor separa em um pedido por alvo para o mestre). */
data class MultiDamageTarget(val targetId: String, val targetType: String, val targetName: String, val damage: Int)
data class MultiDamageRequestBody(
    val requestId: String,
    val targets: List<MultiDamageTarget>,
    val costs: Map<String, Int> = emptyMap(),
    val skillId: String? = null,
    val slotId: String? = null
)

data class DamageApproveBody(
    val requestId: String,
    val playerId: String,
    val targetId: String,
    val targetType: String,
    val damage: Int,
    val costs: Map<String, Int> = emptyMap(),  // custo resolvido a debitar ao aprovar
    val onHitEffects: List<StatusEffect> = emptyList(),
    val slotId: String? = null,
    val skillId: String? = null,
    val critico: Boolean = false,          // acerto crítico (d20 de acerto >= margem)
    val danoSemCritico: Int? = null,       // dano da mesma rolagem sem o crítico (usado se o alvo negar)
    val ataqueBasico: Boolean = false      // ataque básico: recupera ES ao acertar
)
data class DamageResultNotification(
    val requestId: String,
    val approved: Boolean,
    val criticoNegado: Boolean = false,
    val degrau: DegrauAplicado? = null,   // só chega depois do golpe: o jogador não vê a fraqueza antes
    val danoAplicado: Int? = null
)

// Effect approval (separado do damage)
data class EffectApprovalRequest(
    val requestId: String,
    val playerId: String,
    val playerName: String,
    val targets: List<EffectTarget>,
    val onHitEffects: List<StatusEffect>,
    val skillId: String? = null
)
data class EffectTarget(
    val targetId: String,
    val targetType: String,   // "enemy" | "boss" | "player" | "ally" (fogo amigo)
    val targetName: String
)
data class EffectRequestBody(
    val requestId: String,
    val targets: List<EffectTarget>,
    val onHitEffects: List<StatusEffect> = emptyList(),   // ignorado quando skillId vem preenchido
    val skillId: String? = null
)
data class EffectApproveBody(
    val requestId: String,
    val playerId: String,
    val targets: List<EffectTarget>,
    val onHitEffects: List<StatusEffect>
)

// Skill em aliados (cura, Escudo, buffs): o jogador escolhe quem recebe e o mestre aprova
data class AliadoResumo(val id: String, val nome: String, val hp: Int, val hpMax: Int, val escudo: Int, val portraitUrl: String?)
data class AllyEffectRequestBody(val requestId: String, val slotId: String, val alvos: List<String>)
data class AllyEffectApprovalRequest(
    val requestId: String,
    val playerId: String,
    val playerName: String,
    val skillId: String,
    val skillName: String,
    val slotId: String,
    val alvos: List<AliadoResumo>,
    val cura: Int,                       // cura na hora em cada aliado
    val escudo: Int,                     // Escudo em cada aliado
    val efeitos: List<StatusEffect>,     // buffs, condições e cura por turno em cada aliado
    val costs: Map<String, Int>
)
data class AllyEffectApproveBody(val requestId: String, val playerId: String, val slotId: String, val alvos: List<String>)

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

// Flat DTO para o app do jogador
data class PlayerSkillTreeEntry(
    val skillId: String,
    val nome: String,
    val custo: String,
    val custoBase: Int? = null,          // custo de Fluxo sem desconto (maestria e efeitos), para mostrar a conta
    val descricao: String,
    val categoria: String,
    val unlocked: Boolean,
    val equipped: Boolean,
    val slotId: String?,
    val requirementsText: String?,
    val maestria: MaestriaGrupoInfo?,   // maestria do grupo da skill (arma ou Essência)
    val isPassive: Boolean = false,
    val passiva: Boolean = false,         // skill passiva: só cabe e só vale nos slots de passiva
    val danoFormula: String? = null,
    val danoBase: Int? = null,
    val atributo: String? = null,
    val equilibrio: Double? = null,
    val tier: Int? = null,
    val semProficiencia: String? = null,  // categoria de arma sem proficiência: skill bloqueada
    val bloqueio: String? = null,         // requisito por origem/tier que falta (ou sem proficiência): skill bloqueada
    val origem: String? = null,           // "CLASSE" | "ARMA" | "ESSENCIA"
    val alvo: String? = null,             // em quem a skill é usada: "USUARIO" | "INIMIGOS" | "ALIADOS"
    val grupoMaestria: String? = null,    // grupo de maestria da skill para este jogador
    val damageSource: String = "formula", // "formula" | "weapon"
    val weaponSlot: String? = null,       // "mainHand" | "offHand" — quando damageSource == "weapon"
    val weaponDamageModifiers: List<AutoEffect> = emptyList(), // modificadores da skill sobre o dano da arma equipada
    val cooldownTurns: Int = 0,
    val skillType: String = "class",  // "class" | "weapon" | "essencia" | "mestre"
    val pressaoDice: Boolean = false, // técnica consome Pressão e ganha +1d6 de dano por ponto
    val critThreshold: Int? = null,   // margem própria da skill; null usa a margem da arma (padrão 20)
    val bonusAcerto: Int? = null,     // +X no acerto desta skill
    val exclusivaCom: String? = null, // skill que não equipa junto com esta
    val ignoraDef: Boolean = false,   // acerta sempre, sem rolar acerto
    val tiposDano: List<String> = emptyList(), // tipos de dano do golpe (os da skill ou, se vazio e o dano é da arma, os da arma)
    val actionType: String = "main",  // "main" | "bonus" | "both"
    val multiTarget: MultiTarget? = null,
    val efeitos: List<Efeito> = emptyList()
)


// DTO para o app do mestre — árvore de habilidades com status e playerSkill
data class MasterSkillTreeEntry(
    val skill: Skill,
    val status: SkillStatus,
    val playerSkill: PlayerSkill?,
    val maestria: MaestriaGrupoInfo? = null,
    val bloqueio: String? = null           // requisito por origem/tier que falta
)
