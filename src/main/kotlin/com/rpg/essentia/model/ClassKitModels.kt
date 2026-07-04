package com.rpg.essentia.model

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field

data class UnarmedAttack(
    @Field("dano_base") val damageBase: Int = 0,
    val attribute: String = ""
)

data class ClassPerks(
    val hasPressureBar: Boolean = false,
    @Field("ataque_desarmado") val unarmedAttack: UnarmedAttack? = null
)

@Document(collection = "class_kits")
data class ClassKit(
    @Id val id: String = java.util.UUID.randomUUID().toString(),
    @Field("class") @JsonProperty("skillClass") val skillClass: String = "",
    val starterSkillIds: List<String> = emptyList(),
    val starterSlots: List<Slot> = emptyList(),
    val starterEquipment: Equipment = Equipment(),
    val starterItems: List<Item> = emptyList(),
    val starterAttributes: Attributes? = null,
    val perks: ClassPerks = ClassPerks(),
    val allowedWeaponTypes: List<String> = emptyList()
)
