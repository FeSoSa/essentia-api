package com.rpg.essentia.config

import com.rpg.essentia.model.CategoriaDano
import com.rpg.essentia.model.TipoDano
import com.rpg.essentia.repository.TipoDanoRepository
import com.rpg.essentia.service.DegrauRules
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/**
 * Tipos de dano iniciais com ids fixos, criados só com a coleção vazia (o que o mestre apagar não volta).
 * Os de Essência não ficam aqui: cada Essência já é o seu tipo. Impacto é a exceção do "não volta":
 * é o tipo do golpe desarmado, então o id fixo é recriado se faltar.
 */
@Component
class TiposDanoSeed(private val repository: TipoDanoRepository) : CommandLineRunner {

    override fun run(vararg args: String) {
        val impacto = TipoDano(DegrauRules.IMPACTO, "Impacto", CategoriaDano.FISICO)
        if (repository.count() == 0L) {
            repository.saveAll(listOf(
                TipoDano("corte", "Corte", CategoriaDano.FISICO),
                TipoDano("perfuracao", "Perfuração", CategoriaDano.FISICO),
                impacto,
                TipoDano("veneno", "Veneno", CategoriaDano.OUTRO),
            ))
        } else if (!repository.existsById(DegrauRules.IMPACTO)) {
            repository.save(impacto)
        }
    }
}
