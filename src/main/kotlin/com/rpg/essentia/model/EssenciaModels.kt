package com.rpg.essentia.model

import java.util.UUID
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "essencias")
data class Essencia(
    @Id val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,              // "Elemental" (antes "Grande") | "Mitica" | "Derivada"
    val desc: String,
    val attributeBonus: Map<String, Int>,
    val skillIds: List<String>,
    val icon: String? = null,
    val color: String? = null,
    val parentId: String? = null,
    val nivelMaeNecessario: Int? = null,  // Derivada: liberada pela mãe com ela neste nível ou acima (null = só tendo a Derivada)
    val bonusPorNivel: List<Map<String, Int>> = emptyList()  // quanto cada nível (1 a 4) soma ao bônus; vazio = regra padrão de %
) {
    /** Essência Elemental (o nome antigo "Grande" ainda vale até a migração renomear). */
    val elemental: Boolean get() = type == "Elemental" || type == "Grande"

    /** Derivada acessível pela Essência-mãe, dado o nível da mãe no jogador (null = não tem a mãe). */
    fun liberadaPelaMae(nivelDaMae: Int?): Boolean =
        type == "Derivada" && parentId != null && nivelMaeNecessario != null && nivelDaMae != null && nivelDaMae >= nivelMaeNecessario
}
