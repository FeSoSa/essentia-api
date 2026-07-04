package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "effect_presets")
data class EffectPreset(
    @Id val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val icon: String? = null,
    val color: String? = null,
    val desc: String? = null,
    val durationTurns: Int = 1,
    val effects: List<AutoEffect> = emptyList(),
    val hitBonus: Int? = null,
    val attackBonus: Int? = null
)
