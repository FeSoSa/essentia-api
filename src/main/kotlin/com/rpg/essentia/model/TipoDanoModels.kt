package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

/** ESSENCIA não é cadastrada: cada Essência já é o seu tipo. */
enum class CategoriaDano { ESSENCIA, FISICO, OUTRO }

/** Multiplicador sobre o dano final. Neutro é o padrão e nunca fica salvo como traço. */
enum class Degrau(val mult: Double, val rotulo: String) {
    IMUNE(0.0, "Imune"),
    RESISTENTE(0.5, "Resistente"),
    NEUTRO(1.0, "Neutro"),
    FRACO(1.5, "Fraco")
}

/** Tipo de dano (Corte, Veneno...). Os de Essência são montados a partir da Essência, com o id dela. */
@Document(collection = "tipos_dano")
data class TipoDano(
    @Id val id: String = UUID.randomUUID().toString(),
    val nome: String = "",
    val categoria: CategoriaDano = CategoriaDano.FISICO,
    val essenciaId: String? = null
)

/** Fraqueza, resistência ou imunidade a um tipo de dano (raça, criatura ou armadura de corpo). */
data class TracoDano(
    val tipoDanoId: String = "",
    val degrau: Degrau = Degrau.FRACO
)

/** Degrau que valeu num golpe e o tipo que decidiu (null quando o ataque não tem tipo). */
data class DegrauAplicado(
    val degrau: Degrau = Degrau.NEUTRO,
    val tipoDanoId: String? = null,
    val tipoNome: String? = null,
    val origem: String? = null      // "raca" | "armadura" | null (Neutro por falta de traço)
) {
    /** Ex: "Fogo: Fraco x1,5". */
    val texto: String get() =
        "${tipoNome ?: "Sem tipo"}: ${degrau.rotulo} x${degrau.mult.toString().removeSuffix(".0").replace('.', ',')}"
}
