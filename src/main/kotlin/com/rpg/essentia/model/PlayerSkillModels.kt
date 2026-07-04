package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

data class MaestriaUpgrade(
    val level: Int = 1,
    val path: String = "",             // "aumento" | "otimizacao"
    val effect: Map<String, Any> = emptyMap()  // audit trail only
)

data class MaestriaComputed(
    val bonusDano: Double = 0.0,      // ex: 0.32 = +32% no dano
    val custoAumento: Double = 0.0,   // ex: 0.24 = +24% no custo (de escolhas aumento)
    val reducaoCusto: Double = 0.0,   // ex: 0.16 = -16% no custo (de escolhas otimizacao)
)

data class Maestria(
    val level: Int = 1,                // 1 to 5
    val totalUses: Int = 0,
    val nextLevelUses: Int = 3,        // thresholds: 3 | 8 | 16 | 28
    val upgrades: List<MaestriaUpgrade> = emptyList(),
    val computed: MaestriaComputed = MaestriaComputed()
)

@Document(collection = "player_skills")
data class PlayerSkill(
    @Id val id: String,
    val playerId: String,
    val skillId: String,
    val used: Boolean = false,
    val equipped: Boolean = false,
    val slotId: String? = null,
    val maestria: Maestria = Maestria(),
    val blocked: Boolean = false
)
