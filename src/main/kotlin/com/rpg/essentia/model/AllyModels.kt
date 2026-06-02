package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

data class AllyAttack(
    val name: String = "",
    val damage: String = "",
    val damageBase: Int? = null,
    val damageAttribute: String? = null,
    val equilibrio: Int? = null,
    val skillId: String? = null,
    val skillName: String? = null
)
data class AllyAttributes(
    val strength: Int = 10,
    val agility: Int = 10,
    val intelligence: Int = 10,
    val defense: Int = 5
)

@Document(collection = "ally_templates")
data class AllyTemplate(
    @Id val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: String = "",
    val hpMax: Int = 20,
    val portraitUrl: String? = null,
    val attributes: AllyAttributes = AllyAttributes(),
    val attacks: List<AllyAttack> = emptyList(),
    val immunities: List<com.rpg.essentia.model.BossImmunity> = emptyList(),
    val desc: String = "",
    val notes: String = ""
)

@Document(collection = "combat_allies")
data class CombatAlly(
    @Id val id: String = UUID.randomUUID().toString(),
    val templateId: String? = null,
    val name: String = "",
    val type: String = "",
    val hpCurrent: Int = 20,
    val hpMax: Int = 20,
    val portraitUrl: String? = null,
    val attributes: AllyAttributes = AllyAttributes(),
    val attacks: List<AllyAttack> = emptyList(),
    val immunities: List<com.rpg.essentia.model.BossImmunity> = emptyList(),
    val desc: String = "",
    val notes: String = ""
)

data class AllyHpRequest(val delta: Int = 0)
data class AllyNotesRequest(val notes: String = "")
data class AddAllyRequest(val templateId: String? = null, val ally: CombatAlly)
