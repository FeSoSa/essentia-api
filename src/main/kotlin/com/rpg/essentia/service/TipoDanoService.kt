package com.rpg.essentia.service

import com.rpg.essentia.model.CategoriaDano
import com.rpg.essentia.model.Essencia
import com.rpg.essentia.model.TipoDano
import com.rpg.essentia.repository.EssenciaRepository
import com.rpg.essentia.repository.TipoDanoRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Tipos de dano. Cada Essência já é um tipo (id = id da Essência, mesmo nome), sem cadastro: assim a skill
 * de Essência e a arma comum de Fogo usam o mesmo "Fogo". O mestre cadastra só os demais (Físico e Outros).
 * Apagar um tipo não mexe em quem aponta para ele: a referência órfã sai Neutro.
 */
@Service
class TipoDanoService(
    private val repository: TipoDanoRepository,
    private val essenciaRepository: EssenciaRepository
) {
    fun list(): List<TipoDano> =
        essenciaRepository.findAll().map(::deEssencia).sortedBy { it.nome } +
            repository.findAll().sortedWith(compareBy({ it.categoria }, { it.nome }))

    /** Nome do tipo pelo id, seja cadastrado ou de Essência (null = órfão). */
    fun nome(id: String): String? =
        repository.findById(id).orElse(null)?.nome ?: essenciaRepository.findById(id).orElse(null)?.name

    fun create(tipo: TipoDano): TipoDano = repository.save(validar(tipo.copy(id = UUID.randomUUID().toString())))

    fun update(id: String, tipo: TipoDano): TipoDano {
        if (!repository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo de dano não encontrado (os de Essência se editam na Essência)")
        return repository.save(validar(tipo.copy(id = id)))
    }

    fun delete(id: String) {
        if (!repository.existsById(id))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo de dano não encontrado (os de Essência saem junto com a Essência)")
        repository.deleteById(id)
    }

    private fun validar(tipo: TipoDano): TipoDano {
        val nome = tipo.nome.trim()
        if (nome.isBlank())
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o nome do tipo de dano")
        if (tipo.categoria == CategoriaDano.ESSENCIA)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipos de Essência vêm das Essências: escolha Físico ou Outros")
        if (essenciaRepository.findAll().any { it.name.equals(nome, ignoreCase = true) })
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$nome já é uma Essência e já conta como tipo de dano")
        if (repository.findAll().any { it.id != tipo.id && it.nome.equals(nome, ignoreCase = true) })
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Já existe um tipo de dano chamado $nome")
        return tipo.copy(nome = nome, essenciaId = null)
    }

    private fun deEssencia(e: Essencia) = TipoDano(e.id, e.name, CategoriaDano.ESSENCIA, e.id)
}
