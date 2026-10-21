package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

data class CharInfo(
    val name: String = "",
    val classe: String = "",
    val skillClass: String = "",
    val race: String = "",
    val level: Int = 1,
    val slotsClass: Int = 2,
    val slotsFree: Int = 6,
    val slotsTotal: Int = 8,
    val portraitUrl: String? = null,
    val unarmedAttack: UnarmedAttack? = null
)

data class Vital(val current: Int = 0, val max: Int = 0)

data class Ether(
    val current: Int = 0,
    val max: Int = 0,
    val unlocked: Boolean = false
)

data class Exp(
    val available: Int = 0,
    val total: Int = 0
)

// JSON keys: strength, agility, intelligence, resistance, flow, wisdom, presence, defense
// attributeBonus maps from equipment/essencias must use these same keys
data class Attributes(
    val strength: Int = 0,
    val agility: Int = 0,
    val intelligence: Int = 0,
    val resistance: Int = 0,
    val flow: Int = 0,
    val wisdom: Int = 0,
    val presence: Int = 0,
    val defense: Int = 0
)

data class EssenciaObtida(
    val essenciaId: String = "",
    val attributeBonusActive: Boolean = false,
    val unlockedSkillIds: List<String> = emptyList(),
    val nivel: Int = 1                     // 1 a 4, definido pelo mestre
)

/** Maestria de um grupo: categoria de arma ("espadas") ou Essência (essenciaId). Nível 1 a 4. */
data class MaestriaGrupo(
    val grupo: String = "",
    val tipoGrupo: String = "ARMA",          // "ARMA" | "ESSENCIA"
    val nivel: Int = 1,
    val usos: Int = 0,                       // usos acumulados (1 por batalha)
    val ultimoCombateUso: String? = null,    // combatId do último uso contado
    val usosNoCombate: Int = 0               // usos contados em ultimoCombateUso
)

/** Parte do PV/Fluxo máximo que vem da classe (derivado, recalculado com os vitais; o app mostra a composição). */
data class VitaisClasse(
    val pvPorNivel: Int = 0,
    val fluxoPorNivel: Int = 0,
    val pvBonus: Int = 0,
    val fluxoBonus: Int = 0
)

/** Capacidade de Essência e penalidade por excedente (derivado, recalculado com os atributos). */
data class EstadoEssencia(
    val capacidade: Int = 4,
    val uso: Int = 0,
    val excedente: Int = 0,
    val penalidadeRes: Int = 0,            // valor negativo aplicado ao RES efetivo
    val penalidadeFlx: Int = 0,            // valor negativo aplicado ao FLX efetivo
    val testeSabDescanso: Boolean = false  // excedente +3: teste de SAB a cada descanso longo
)

data class Slot(
    val id: String = "",
    val type: String = "",             // "class" | "free" | "human_bonus"
    val skillId: String? = null,
    val cooldownRemaining: Int = 0
)

data class WeaponEquip(
    val id: String = "",
    val name: String = "",
    val desc: String? = null,
    val icon: String? = null,
    val weaponType: String = "",
    val damageBase: Int = 0,
    val damageAttribute: String = "",
    val equilibrio: Int? = null,
    val properties: String? = null,
    val attributeBonus: Map<String, Int>? = null,
    val rarity: String? = null,
    val twoHanded: Boolean = false,
    val equipSlot: String? = null,     // slot original do item ("both" p/ qualquer mão); null = fixo na mão em que está
    val margemCritico: Int? = null     // mínimo no d20 de acerto para crítico (null = 20)
)

data class ArmorEquip(
    val id: String = "",
    val name: String = "",
    val desc: String? = null,
    val icon: String? = null,
    val damageReduction: Int = 0,
    val attributeBonus: Map<String, Int>? = null,
    val armorWeight: String? = null,
    val rarity: String? = null,
    val negaCritico: Boolean = false   // nega um acerto crítico por rodada (ex: Manto do Vento)
)

data class AccessoryEquip(
    val id: String = "",
    val name: String = "",
    val desc: String? = null,
    val icon: String? = null,
    val attributeBonus: Map<String, Int>? = null,
    val rarity: String? = null
)

data class Equipment(
    val mainHand: WeaponEquip? = null,
    val offHand: WeaponEquip? = null,
    val armor: ArmorEquip? = null,
    val amulet: AccessoryEquip? = null,
    val ring: AccessoryEquip? = null,
    val utility: AccessoryEquip? = null
)

data class AutoEffect(
    val trigger: String = "",
    val type: String = "",
    val value: Int? = null,
    val percentual: Int? = null,
    val attribute: String? = null,
    val dice: Dice? = null,
    val costType: String? = null
)

data class OnExpireEffect(
    val name: String = "",
    val desc: String = "",
    val durationTurns: Int = 1,
    val icon: String? = null,
    val color: String? = null,
    val instantHealHp: Int? = null,
    val instantHealFlow: Int? = null,
    val instantDamageHp: Int? = null,
    val attributeBonus: Map<String, Int>? = null,
    val effects: List<AutoEffect> = emptyList(),
    val hitBonus: Int? = null,
    val attackBonus: Int? = null,
    val damageBonus: Int? = null
)

data class StatusEffect(
    val id: String = "",
    val name: String = "",
    val desc: String = "",
    val icon: String? = null,
    val color: String? = null,
    val durationTurns: Int = 1,        // -1 = permanent until master removes
    val effects: List<AutoEffect> = emptyList(),
    val attributeBonus: Map<String, Int>? = null,
    val hitBonus: Int? = null,
    val attackBonus: Int? = null,
    val damageBonus: Int? = null,
    val onExpire: OnExpireEffect? = null,
    val sourceSkillId: String? = null  // rastreia a habilidade que criou este efeito
)

data class Item(
    val id: String = "",
    val catalogId: String? = null,     // referência ao ItemCatalog de origem; null = item avulso/sem catálogo
    val name: String = "",
    val desc: String = "",
    val qty: Int = 1,
    val type: String = "",             // "consumable" | "equipment" | "currency" | "weapon" | "armor" | "accessory"
    val icon: String? = null,
    // Weapon fields (type == "weapon")
    val weaponType: String? = null,
    val damageBase: Int? = null,
    val damageAttribute: String? = null,
    val equilibrio: Int? = null,
    val properties: String? = null,
    // Armor field (type == "armor")
    val damageReduction: Int? = null,
    val armorWeight: String? = null,   // "leve" | "média" | "pesada"
    // Shared equip field (type == "weapon" | "armor" | "accessory")
    val attributeBonus: Map<String, Int>? = null,
    val equipSlot: String? = null,     // "mainHand"|"offHand"|"armor"|"amulet"|"ring"|"utility"
    val rarity: String? = null,
    val twoHanded: Boolean? = null,
    val requirements: ItemRequirements? = null,
    val requisitoNarrativo: String? = null,  // só informativo (armas lendárias/históricas), sem validação
    val onUseEffect: OnUseEffect? = null,
    val goldValue: Int? = null,
    val margemCritico: Int? = null,
    val negaCritico: Boolean? = null
)

data class PendingRequest(
    val id: String = "",
    val type: String = "",             // "use_item"
    val itemId: String = "",
    val timestamp: String = ""
)

@Document(collection = "players")
data class Player(
    @Id val id: String,
    val code: String,
    val char: CharInfo = CharInfo(),
    val hp: Vital = Vital(),
    val escudo: Int = 0,                              // PV temporário por cima da vida (teto 25% do PV máx, zera no fim do dia)
    val flow: Vital = Vital(),
    val ether: Ether = Ether(),
    val pressao: Vital? = null,
    val exp: Exp = Exp(),
    val attributes: Attributes = Attributes(),
    val effectiveAttributes: Attributes? = null,
    val essenciasObtidas: List<EssenciaObtida> = emptyList(),
    val essencia: EstadoEssencia? = null,             // Capacidade/Uso/penalidade, recalculado com os vitais
    val vitaisClasse: VitaisClasse? = null,           // bônus de PV/Fluxo por nível da classe, recalculado com os vitais
    val proficiencias: List<String> = emptyList(),    // categorias de arma (ex: "espadas"), mantidas pelo mestre
    val maestrias: List<MaestriaGrupo> = emptyList(), // maestria por grupo (categoria de arma ou Essência)
    val nivelClasse: Int = 1,                         // 1 a 4, só o mestre altera; requisito das skills de classe
    val slots: List<Slot> = emptyList(),
    val equipment: Equipment = Equipment(),
    val statusEffects: List<StatusEffect> = emptyList(),
    val items: List<Item> = emptyList(),
    val pendingRequests: List<PendingRequest> = emptyList(),
    val gold: Int = 0,
    val desviosRestantes: Int = 4,
    val sorte: Int = 2,                               // d6 de sorte da campanha inteira; não volta, só o mestre concede
    val sobrecargaDesbloqueada: Boolean = false,
    val sobrecargaAtiva: Boolean = false,
    val sobrecargaNivel: Int? = null,
    val customBars: List<CustomBar> = emptyList(),
    val inventorySize: Int = 18,
    val perfil: Perfil = Perfil(),                   // história e traços (mestre) + anotações (jogador)
    val active: Boolean = true
)

/** Aba Personagem: o mestre escreve história, traços e detalhes; o jogador escreve as próprias anotações. */
data class Perfil(
    val historia: String = "",
    val tracos: List<String> = emptyList(),
    val idade: String = "",
    val altura: String = "",
    val origem: String = "",
    val faccao: String = "",
    val anotacoes: String = ""
)

data class CustomBar(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val color: String = "#a855f7",
    val current: Int = 0,
    val max: Int = 10
)
