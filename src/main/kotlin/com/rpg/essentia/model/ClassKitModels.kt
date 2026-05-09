package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field

data class ClassPerks(
    val hasPressureBar: Boolean = false        // habilita a barra de pressão (ex: Intenso)
)

@Document(collection = "class_kits")
data class ClassKit(
    @Id val id: String,
    @Field("class") val skillClass: String,
    val starterSkillIds: List<String> = emptyList(),
    val starterSlots: List<Slot> = emptyList(),
    val starterEquipment: Equipment = Equipment(),
    val starterItems: List<Item> = emptyList(),
    val starterAttributes: Attributes = Attributes(),
    val perks: ClassPerks = ClassPerks()
)
