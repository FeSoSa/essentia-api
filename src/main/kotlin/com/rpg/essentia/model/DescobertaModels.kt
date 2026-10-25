package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

/**
 * Fraqueza ou resistência que o grupo descobriu acertando uma criatura. Vale para o grupo todo;
 * só a primeira descoberta de cada (criatura, tipo) fica registrada. Nasce pendente: os jogadores
 * só veem depois que o mestre libera (evita revelar algo por um dano aprovado por engano).
 */
@Document(collection = "descobertas")
data class Descoberta(
    @Id val id: String = UUID.randomUUID().toString(),
    val criaturaChave: String = "",        // templateId da criatura (ou o nome, se ela foi criada na hora)
    val criaturaNome: String = "",
    val tipoDanoId: String = "",
    val tipoNome: String = "",
    val degrau: Degrau = Degrau.NEUTRO,
    val playerId: String = "",
    val playerName: String = "",
    val descobertoEm: String = java.time.Instant.now().toString(),
    val liberada: Boolean = false
)

/** Uma criatura no Bestiário dos jogadores, com o que já se sabe dela. */
data class BestiarioEntrada(
    val criaturaChave: String,
    val nome: String,
    val icon: String? = null,
    val imageUrl: String? = null,
    val descobertas: List<Descoberta>
)
