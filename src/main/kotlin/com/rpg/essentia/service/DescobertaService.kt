package com.rpg.essentia.service

import com.rpg.essentia.model.BestiarioEntrada
import com.rpg.essentia.model.DegrauAplicado
import com.rpg.essentia.model.Descoberta
import com.rpg.essentia.model.Player
import com.rpg.essentia.repository.BossTemplateRepository
import com.rpg.essentia.repository.DescobertaRepository
import com.rpg.essentia.repository.EnemyTemplateRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Bestiário dos jogadores: o que o grupo já descobriu de cada criatura, acertando. */
@Service
class DescobertaService(
    private val repository: DescobertaRepository,
    private val enemyService: EnemyService,
    private val bossService: BossService,
    private val enemyTemplateRepository: EnemyTemplateRepository,
    private val bossTemplateRepository: BossTemplateRepository,
    private val broadcaster: WebSocketBroadcaster
) {
    /** Golpe aprovado num inimigo ou boss: registra o tipo que decidiu, se ainda ninguém tinha descoberto. */
    fun registrar(atacante: Player, targetType: String, targetId: String, degrau: DegrauAplicado) {
        val tipo = degrau.tipoDanoId ?: return
        val (chave, nome) = when (targetType) {
            "enemy" -> enemyService.findInstance(targetId)?.let { (it.templateId ?: it.name) to it.name }
            "boss" -> bossService.findInstance(targetId)?.let { (it.templateId ?: it.name) to it.name }
            else -> null
        } ?: return
        if (repository.existsByCriaturaChaveAndTipoDanoId(chave, tipo)) return
        repository.save(Descoberta(
            criaturaChave = chave, criaturaNome = nomeDoModelo(chave) ?: nome,
            tipoDanoId = tipo, tipoNome = degrau.tipoNome ?: "?", degrau = degrau.degrau,
            playerId = atacante.id ?: "", playerName = atacante.char.name
        ))
        avisar()
    }

    /** Para os jogadores: só o que o mestre já liberou. Para o mestre (todas = true): inclusive as pendentes. */
    fun listar(todas: Boolean = false): List<BestiarioEntrada> =
        repository.findAll().filter { todas || it.liberada }.groupBy { it.criaturaChave }.map { (chave, lista) ->
            val inimigo = enemyTemplateRepository.findById(chave).orElse(null)
            val boss = if (inimigo == null) bossTemplateRepository.findById(chave).orElse(null) else null
            BestiarioEntrada(
                criaturaChave = chave,
                nome = inimigo?.name ?: boss?.name ?: lista.first().criaturaNome,
                icon = inimigo?.icon ?: boss?.icon,
                imageUrl = inimigo?.imageUrl ?: boss?.imageUrl,
                descobertas = lista.sortedBy { it.descobertoEm }
            )
        }.sortedBy { it.nome }

    /** O mestre libera (ou volta a esconder) uma descoberta para os jogadores. */
    fun liberar(id: String, liberada: Boolean) {
        val d = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Descoberta não encontrada") }
        repository.save(d.copy(liberada = liberada))
        avisar()
    }

    /** O mestre descarta uma descoberta errada (ex.: aprovou um dano por engano). */
    fun apagar(id: String) {
        if (!repository.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Descoberta não encontrada")
        repository.deleteById(id)
        avisar()
    }

    private fun avisar() {
        broadcaster.broadcastDescobertas(listar())
        broadcaster.broadcastDescobertasMestre(listar(todas = true))
    }

    private fun nomeDoModelo(chave: String): String? =
        enemyTemplateRepository.findById(chave).orElse(null)?.name ?: bossTemplateRepository.findById(chave).orElse(null)?.name
}
