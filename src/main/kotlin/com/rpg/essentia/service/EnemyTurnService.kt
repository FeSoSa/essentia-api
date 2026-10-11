package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EnemyActionRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.random.Random

/**
 * Turno dos inimigos calculado pelo app. O mestre sempre decide (permitir, trocar, pular);
 * em caso de acerto o jogador alvo pode reagir com Desvio antes do dano entrar.
 */
@Service
class EnemyTurnService(
    private val enemyService: EnemyService,
    private val bossService: BossService,
    private val playerRepository: PlayerRepository,
    private val playerService: PlayerService,
    private val gameStateService: GameStateService,
    private val actionRepository: EnemyActionRepository,
    private val broadcaster: WebSocketBroadcaster
) {
    /** Fonte dos d20; substituível em testes. */
    internal var random: Random = Random.Default

    /** Visão comum de inimigo e boss para o cálculo. */
    private data class Atacante(
        val id: String,
        val kind: String,
        val nome: String,
        val attrs: Map<String, Int>,
        val ataques: List<EnemyAttack>,
        val modAcerto: Int,
        val esAtual: Int
    )

    private data class Formula(val base: Int, val atributo: String, val equilibrio: Int)

    fun propor(entityId: String, kind: String, alvoId: String?, ataqueIdx: Int?): EnemyAction {
        val atk = atacante(entityId, kind)
        val alvo = escolherAlvo(alvoId)
        val idx = ataqueIdx ?: escolherAtaque(atk)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${atk.nome} não tem ataque com dano que consiga pagar")
        val state = gameStateService.getOrCreate()
        val action = calcular(EnemyAction(combatId = state.combatId, rodada = gameStateService.rodadaAtual(state)), atk, alvo, idx)
        return salvar(action)
    }

    fun trocar(actionId: String, alvoId: String?, ataqueIdx: Int?): EnemyAction {
        val atual = load(actionId)
        if (atual.status != "PROPOSTA")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta ação já foi decidida")
        val atk = atacante(atual.entityId, atual.entityKind)
        val alvo = escolherAlvo(alvoId ?: atual.alvoId)
        val novo = calcular(atual.copy(trocas = atual.trocas + 1, decisaoMestre = "TROCOU"), atk, alvo, ataqueIdx ?: atual.ataqueIdx)
        return salvar(novo)
    }

    fun pular(actionId: String): EnemyAction {
        val atual = load(actionId)
        if (atual.status != "PROPOSTA")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta ação já foi decidida")
        gameStateService.addLogEntry("", "${atual.entityName} não agiu (turno pulado pelo mestre)", "info")
        return salvar(atual.copy(status = "PULADA", decisaoMestre = "PULOU"))
    }

    fun permitir(actionId: String): EnemyAction {
        val atual = load(actionId)
        if (atual.status != "PROPOSTA")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta ação já foi decidida")
        val decidida = atual.copy(decisaoMestre = if (atual.trocas > 0) "TROCOU" else "PERMITIU")
        gastarEs(decidida)
        if (!decidida.acertou) {
            gameStateService.addLogEntry(decidida.alvoId,
                "${decidida.entityName} usou ${decidida.ataqueNome} em ${decidida.alvoNome} e errou (${decidida.totalAcerto} vs DEF ${decidida.defAlvo})", "hp")
            return salvar(decidida.copy(status = "ERROU"))
        }
        val aguardando = salvar(decidida.copy(status = "AGUARDANDO_JOGADOR"))
        broadcaster.broadcastEnemyAttackToPlayer(aguardando.alvoId, aguardando)
        return aguardando
    }

    /** Resposta do jogador alvo: desviar (com a rolagem do d20) ou receber. */
    fun responder(actionId: String, playerId: String, desviar: Boolean, roll: Int?): EnemyAction {
        val atual = load(actionId)
        if (atual.status != "AGUARDANDO_JOGADOR")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Este ataque não aguarda resposta")
        if (atual.alvoId != playerId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Este ataque não é contra você")

        var acao = atual
        if (desviar) {
            val player = playerService.load(playerId)
            val peso = pesoArmadura(player)
            if (peso == "pesada")
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Armadura pesada não permite desvio")
            val d20 = roll?.takeIf { it in 1..20 }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o d20 do desvio")
            playerService.executeDesvio(playerId) // valida e consome um uso
            val attrs = player.effectiveAttributes ?: player.attributes
            val bonus = if (peso == "nenhuma") CombatRules.getModifier(attrs.agility) else 0
            val total = d20 + bonus
            acao = acao.copy(desviou = total >= CombatRules.CD_DESVIO, desvioRoll = d20, desvioTotal = total)
        } else {
            acao = acao.copy(desviou = false)
        }

        val danoAplicado = if (acao.desviou == true) 0 else {
            val alvo = playerService.load(playerId)
            applyDamageModifiers(acao.dano ?: 0, emptyList(), alvo.statusEffects).also {
                playerService.adjustHp(playerId, -it)
            }
        }
        val texto = if (acao.desviou == true)
            "${acao.alvoNome} desviou de ${acao.ataqueNome} (${acao.entityName})"
        else
            "${acao.entityName} acertou ${acao.alvoNome} com ${acao.ataqueNome}: $danoAplicado de dano"
        gameStateService.addLogEntry(playerId, texto, "hp")
        return salvar(acao.copy(status = "CONCLUIDA", danoAplicado = danoAplicado))
    }

    /** Números do combate para ajustar CD e dano dos inimigos. */
    fun relatorio(combatId: String?): Map<String, Any> {
        val id = combatId ?: gameStateService.getOrCreate().combatId
            ?: actionRepository.findAll().maxByOrNull { it.criadoEm }?.combatId
        val acoes = if (id == null) emptyList() else actionRepository.findByCombatId(id)
        val porInimigo = acoes.filter { it.status in setOf("CONCLUIDA", "ERROU") }.groupBy { it.entityName }.map { (nome, lista) ->
            val acertos = lista.filter { it.acertou }
            mapOf(
                "inimigo" to nome,
                "ataques" to lista.size,
                "acertos" to acertos.size,
                "taxaAcerto" to if (lista.isEmpty()) 0.0 else acertos.size.toDouble() / lista.size,
                "desvios" to lista.count { it.desviou == true },
                "danoMedioRolado" to (acertos.mapNotNull { it.dano }.average().takeIf { !it.isNaN() } ?: 0.0),
                "danoTotalAplicado" to lista.sumOf { it.danoAplicado ?: 0 },
                "esGasta" to lista.sumOf { it.custoEs }
            )
        }
        return mapOf("combatId" to (id ?: ""), "acoes" to acoes, "porInimigo" to porInimigo)
    }

    /* ── Cálculo ─────────────────────────────────────────────── */

    private fun calcular(base: EnemyAction, atk: Atacante, alvo: Player, idx: Int): EnemyAction {
        val ataque = atk.ataques.getOrNull(idx)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ataque inválido: $idx")
        val formula = formula(ataque)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${ataque.name} não tem fórmula de dano")
        if (ataque.custoEs > atk.esAtual)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${atk.nome} não tem ES para ${ataque.name} (${ataque.custoEs} > ${atk.esAtual})")

        val def = defesa(alvo)
        val d20 = CombatRules.rolarD20(random)
        val total = d20 + atk.modAcerto
        val acertou = total >= def
        val d20Dano = if (acertou) CombatRules.rolarD20(random) else null
        // Crítico de inimigos fica fora por enquanto
        val dano = d20Dano?.let {
            CombatRules.dano(formula.base, it, modDoAtributo(formula.atributo, atk.attrs), formula.equilibrio, false, gameStateService.combateConfig())
                .coerceAtLeast(0)
        }
        return base.copy(
            status = "PROPOSTA",
            entityId = atk.id, entityKind = atk.kind, entityName = atk.nome,
            alvoId = alvo.id, alvoNome = alvo.char.name,
            ataqueIdx = idx, ataqueNome = ataque.name,
            formula = "${formula.base} + d20×${formula.atributo}/${formula.equilibrio}",
            d20Acerto = d20, modAcerto = atk.modAcerto, totalAcerto = total, defAlvo = def,
            acertou = acertou, d20Dano = d20Dano, dano = dano,
            custoEs = ataque.custoEs,
            desviou = null, desvioRoll = null, desvioTotal = null, danoAplicado = null
        )
    }

    /** Alvo sugerido: jogador ativo e vivo com menor DEF; empate pelo menor PV atual. */
    private fun escolherAlvo(alvoId: String?): Player {
        if (alvoId != null)
            return playerRepository.findById(alvoId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Jogador alvo não encontrado") }
        val state = gameStateService.getOrCreate()
        val naIniciativa = state.initiative.map { it.playerId }.toSet()
        val vivos = playerRepository.findAll().filter { it.active && it.hp.current > 0 }
        val candidatos = vivos.filter { it.id in naIniciativa }.ifEmpty { vivos }
        return candidatos.minWithOrNull(compareBy<Player> { defesa(it) }.thenBy { it.hp.current })
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nenhum jogador vivo para atacar")
    }

    /** Ataque sugerido: maior dano esperado entre os que o inimigo consegue pagar. */
    private fun escolherAtaque(atk: Atacante): Int? =
        atk.ataques.withIndex()
            .filter { (_, a) -> a.custoEs <= atk.esAtual }
            .mapNotNull { (i, a) -> formula(a)?.let { f -> i to f.base + 10.5 * modDoAtributo(f.atributo, atk.attrs) / f.equilibrio } }
            .maxByOrNull { it.second }
            ?.first

    private fun formula(a: EnemyAttack): Formula? {
        if (a.damageBase != null && !a.damageAttribute.isNullOrBlank() && a.equilibrio != null && a.equilibrio > 0)
            return Formula(a.damageBase, a.damageAttribute, a.equilibrio)
        // Parse da string legada "16 + d20×FOR/4"
        val m = Regex("""(?:(\d+)\s*\+\s*)?d20\s*[x×]\s*([A-Za-z]+)\s*/\s*(\d+)""").find(a.damage) ?: return null
        val eq = m.groupValues[3].toInt().takeIf { it > 0 } ?: return null
        return Formula(m.groupValues[1].toIntOrNull() ?: 0, m.groupValues[2].uppercase(), eq)
    }

    private fun modDoAtributo(atributo: String, attrs: Map<String, Int>): Int = CombatRules.resolveAtributo(atributo, attrs)

    /** DEF do jogador: atributo efetivo (já inclui o bônus da armadura). */
    private fun defesa(p: Player): Int = (p.effectiveAttributes ?: p.attributes).defense

    private fun pesoArmadura(p: Player): String {
        val w = p.equipment.armor?.armorWeight?.lowercase() ?: return "nenhuma"
        return when {
            "pesada" in w || "heavy" in w -> "pesada"
            "média" in w || "media" in w || "medium" in w -> "media"
            "leve" in w || "light" in w -> "leve"
            else -> "nenhuma"
        }
    }

    private fun atacante(entityId: String, kind: String): Atacante = when (kind) {
        "enemy" -> {
            val e = enemyService.findInstance(entityId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Inimigo não encontrado")
            val a = e.attributes
            Atacante(e.instanceId, "enemy", e.name,
                mapOf("strength" to a.strength, "agility" to a.agility, "intelligence" to a.intelligence, "defense" to a.defense),
                e.attacks, e.modAcerto ?: CombatRules.getModifier(maxOf(a.strength, a.agility)), e.esCurrent ?: e.esMax)
        }
        "boss" -> {
            val b = bossService.findInstance(entityId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Boss não encontrado")
            val fase = b.phases.getOrNull(b.currentPhase)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Boss sem fase ativa")
            val a = fase.attributes
            Atacante(b.instanceId, "boss", b.name, a.toMap(),
                fase.attacks, fase.modAcerto ?: CombatRules.getModifier(maxOf(a.strength, a.agility)), b.esCurrent ?: fase.esMax)
        }
        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo inválido: $kind")
    }

    private fun gastarEs(acao: EnemyAction) {
        if (acao.custoEs <= 0) return
        when (acao.entityKind) {
            "enemy" -> enemyService.findInstance(acao.entityId)?.let { e ->
                enemyService.saveInstance(e.copy(esCurrent = ((e.esCurrent ?: e.esMax) - acao.custoEs).coerceAtLeast(0)))
            }
            "boss" -> bossService.findInstance(acao.entityId)?.let { b ->
                val max = b.phases.getOrNull(b.currentPhase)?.esMax ?: 0
                bossService.saveInstance(b.copy(esCurrent = ((b.esCurrent ?: max) - acao.custoEs).coerceAtLeast(0)))
            }
        }
    }

    private fun load(id: String): EnemyAction =
        actionRepository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Ação não encontrada") }

    private fun salvar(a: EnemyAction): EnemyAction {
        val saved = actionRepository.save(a)
        broadcaster.broadcastEnemyAction(saved)
        return saved
    }
}
