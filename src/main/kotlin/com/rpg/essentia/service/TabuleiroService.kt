package com.rpg.essentia.service

import com.rpg.essentia.model.GameState
import com.rpg.essentia.model.MoverPecaRequest
import com.rpg.essentia.model.MovimentoTurno
import com.rpg.essentia.model.PecaMovida
import com.rpg.essentia.model.PecaTabuleiro
import com.rpg.essentia.model.Tabuleiro
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.repository.RaceRepository
import com.rpg.essentia.repository.TabuleiroRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class TabuleiroService(
    private val tabuleiroRepository: TabuleiroRepository,
    private val playerRepository: PlayerRepository,
    private val raceRepository: RaceRepository,
    private val gameStateService: GameStateService,
    private val broadcaster: WebSocketBroadcaster
) {
    fun get(): Tabuleiro = tabuleiroRepository.findById(Tabuleiro.ID).orElse(Tabuleiro())

    /**
     * O painel do mestre manda o tabuleiro inteiro. O deslocamento dos jogadores vem sempre
     * da raça (o painel pode estar com a lista de raças velha) e o gasto da vez é daqui:
     * o que vier do painel é ignorado. Se o mestre arrastou a peça da vez dentro do que
     * ela ainda pode andar, o custo entra no gasto; fora disso (Alt, empurrão) não conta.
     */
    fun salvar(novo: Tabuleiro): Tabuleiro {
        val atual = get()
        val estado = gameStateService.getOrCreate()
        val pecas = comDeslocamentoDaRaca(novo.pecas)
        var movimento = movimentoValido(atual, estado)
        val vez = vezDe(estado)
        val antes = atual.pecas.find { it.id == vez }
        val depois = pecas.find { it.id == vez }
        if (novo.regraMovimento && antes != null && depois != null && (antes.x != depois.x || antes.y != depois.y)) {
            val gasto = movimento?.gasto ?: 0
            val custo = TabuleiroRules.custoAte(novo, atual.pecas, antes.copy(lado = depois.lado), depois.x, depois.y, depois.deslocamento - gasto)
            if (custo != null) movimento = MovimentoTurno(vez!!, estado.totalTurns, gasto + custo)
        }
        val salvo = tabuleiroRepository.save(novo.copy(id = Tabuleiro.ID, pecas = pecas, movimento = movimento))
        broadcaster.broadcastTabuleiro(salvo)
        return salvo
    }

    /**
     * Jogador move a própria peça. Garante que é a peça dele, que o tabuleiro está liberado,
     * que em combate é a vez dele e que cabe na grade. Com a regra ligada, a API calcula o
     * custo do caminho (mesma regra do app) e soma no que ele já andou na vez: não passa do
     * deslocamento da raça, mesmo indo e voltando em vários passos. Fora de combate, cada
     * movimento vale até o deslocamento, sem acumular.
     */
    fun moverPeca(id: String, req: MoverPecaRequest): Tabuleiro {
        val atual = get()
        if (!atual.visivel) throw ResponseStatusException(HttpStatus.FORBIDDEN, "O tabuleiro não está liberado")
        if (req.playerId != id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Só dá para mover a própria peça")
        val estado = gameStateService.getOrCreate()
        val vez = vezDe(estado)
        if (vez != null && vez != id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Em combate você só anda na sua vez")
        val peca = atual.pecas.find { it.id == id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Peça não está no tabuleiro")
        if (req.x < 0 || req.y < 0 || req.x + peca.lado > atual.colunas || req.y + peca.lado > atual.linhas)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Fora do tabuleiro")

        var movimento = movimentoValido(atual, estado)
        if (atual.regraMovimento) {
            val gasto = if (vez != null && movimento?.id == id) movimento.gasto else 0
            val restante = peca.deslocamento - gasto
            val custo = TabuleiroRules.custoAte(atual, atual.pecas, peca, req.x, req.y, restante)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                    if (restante <= 0) "Você já usou todo o deslocamento desta vez" else "Não dá para chegar lá com $restante ${if (restante == 1) "casa" else "casas"}")
            if (vez != null) movimento = MovimentoTurno(id, estado.totalTurns, gasto + custo)
        }
        val salvo = tabuleiroRepository.save(atual.copy(
            pecas = atual.pecas.map { if (it.id == id) it.copy(x = req.x, y = req.y) else it },
            movimento = movimento,
        ))
        broadcaster.broadcastTabuleiro(salvo)
        broadcaster.broadcastPecaMovida(PecaMovida(id, req.x, req.y))
        return salvo
    }

    /** Começou outra vez (ou outro combate): o que a peça andou na vez anterior deixa de contar. */
    fun novaVez() {
        val atual = get()
        if (atual.movimento == null) return
        broadcaster.broadcastTabuleiro(tabuleiroRepository.save(atual.copy(movimento = null)))
    }

    private fun vezDe(estado: GameState): String? =
        if (estado.initiative.isEmpty()) null
        else estado.initiative[estado.currentTurnIndex % estado.initiative.size].playerId

    /** O gasto guardado só vale para a vez em que foi marcado. */
    private fun movimentoValido(t: Tabuleiro, estado: GameState): MovimentoTurno? =
        t.movimento?.takeIf { it.id == vezDe(estado) && it.turno == estado.totalTurns }

    private fun comDeslocamentoDaRaca(pecas: List<PecaTabuleiro>): List<PecaTabuleiro> {
        val ids = pecas.filter { it.tipo == "jogador" }.map { it.id }
        if (ids.isEmpty()) return pecas
        val racaDe = playerRepository.findAllById(ids).associate { it.id to it.char.race }
        val deslocamento = raceRepository.findAll().associate { it.name to it.deslocamento }
        return pecas.map { p ->
            val d = racaDe[p.id]?.let { deslocamento[it] }
            if (p.tipo == "jogador" && d != null) p.copy(deslocamento = d) else p
        }
    }
}
