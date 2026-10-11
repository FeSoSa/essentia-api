package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "player_skills")
data class PlayerSkill(
    @Id val id: String,
    val playerId: String,
    val skillId: String,
    val used: Boolean = false,
    val equipped: Boolean = false,
    val slotId: String? = null,
    val blocked: Boolean = false
)

/** Visão calculada da maestria de um grupo, enviada para o mestre e para o jogador. */
data class MaestriaGrupoInfo(
    val grupo: String,
    val tipoGrupo: String,            // "ARMA" | "ESSENCIA"
    val nome: String,                 // nome legível (ex: "Espadas", "Fogo")
    val nivel: Int,
    val nivelMax: Int,
    val usos: Int,
    val proximoNivelUsos: Int?,       // null = nível máximo
    val disponivel: Boolean,          // usos já bastam para o próximo nível: aguardando o mestre confirmar
    val danoPct: Int,
    val custoPct: Int,
    val ativa: Boolean = true,        // arma sem proficiência: maestria não conta nem sobe
    val sobePorUso: Boolean = true    // Essência Elemental: não conta uso, o mestre define o nível
)
