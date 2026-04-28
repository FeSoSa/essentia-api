package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field

@Document(collection = "class_kits")
data class ClassKit(
    @Id val id: String,
    @Field("class") val skillClass: String,
    val starterSkillIds: List<String> = emptyList(),
    val starterSlots: List<Slot> = emptyList(),
    val starterEquipment: Equipment = Equipment(),
    val starterItems: List<Item> = emptyList(),
    val starterAttributes: Attributes = Attributes()
)
