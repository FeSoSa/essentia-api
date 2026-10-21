package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.GameStateRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Service
class GameStateService(
    private val gameStateRepository: GameStateRepository,
    private val broadcaster: WebSocketBroadcaster
) {
    companion object {
        const val ROOT_MAP_ID = "essentia-root"
    }

    fun getOrCreate(): GameState {
        val state = gameStateRepository.findById("main").orElseGet { createDefault() }
        return ensureRootMap(state)
    }

    fun save(gameState: GameState): GameState =
        gameStateRepository.save(gameState)

    /** Rodada atual do combate (0 fora de combate). Uma rodada = todos da iniciativa agiram uma vez. */
    fun rodadaAtual(state: GameState = getOrCreate()): Int =
        if (state.initiative.isEmpty()) 0 else state.totalTurns / state.initiative.size

    /** Config de combate; uma tabela de tiers salva antes (com o Tier 5) volta para os tiers 1 a 4. */
    fun combateConfig(): CombateConfig = getOrCreate().combateConfig.let { c ->
        val tiers = c.tiers.filter { it.tier in 1..4 }
        if (tiers.map { it.tier }.sorted() == (1..4).toList()) c.copy(tiers = tiers) else c.copy(tiers = TIERS_PADRAO)
    }

    fun updateCombateConfig(cfg: CombateConfig): CombateConfig {
        if (cfg.critMultBase < 0 || cfg.critMultDado < 0)
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Multiplicadores de crítico não podem ser negativos")
        if (cfg.equilibrioSemProficiencia < 0.5 || cfg.equilibrioComProficiencia < 0.5 || cfg.desviosPorCombate < 0 || cfg.esPorAcertoBasico < 0)
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Valores de combate inválidos")
        if (cfg.tiers.map { it.tier }.sorted() != (1..4).toList() || cfg.tiers.any { it.equilibrio < 0.5 || it.custoBase < 0 })
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Tabela de tiers inválida: precisa dos tiers 1 a 4 com equilíbrio e custo válidos")
        save(getOrCreate().copy(combateConfig = cfg))
        broadcaster.broadcastCombateConfig(cfg)
        return cfg
    }

    fun achadoConfig(): AchadoConfig = getOrCreate().achadoConfig

    fun updateAchadoConfig(cfg: AchadoConfig): AchadoConfig {
        if (cfg.niveis.isEmpty())
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Precisa de pelo menos um nível de achado")
        cfg.niveis.forEach { n ->
            if (n.nome.isBlank() || n.pesos.values.any { it < 0 } || n.pesos.values.sum() <= 0)
                throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Nível \"${n.nome}\" precisa de nome e de alguma chance acima de zero")
        }
        if (cfg.pesosTipo.values.any { it < 0 } || cfg.pesosTipo.values.sum() <= 0)
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "As chances por tipo precisam somar mais que zero")
        if (cfg.pesoRegional < 1)
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "O peso dos itens da região precisa ser 1 ou mais")
        save(getOrCreate().copy(achadoConfig = cfg))
        return cfg
    }

    fun encontroConfig(): EncontroConfig = getOrCreate().encontro

    fun updateEncontroConfig(cfg: EncontroConfig): EncontroConfig {
        if (cfg.dificuldades.isEmpty() || cfg.dificuldades.any { it.nome.isBlank() || it.perigo !in 1..100 })
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Cada dificuldade precisa de nome e de perigo entre 1 e 100")
        if (cfg.hordaMax < 2)
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "A horda precisa permitir pelo menos 2 inimigos")
        save(getOrCreate().copy(encontro = cfg))
        return cfg
    }

    fun addLogEntry(playerId: String, text: String, type: String = "info"): GameState {
        val now = Instant.now()
        val entry = LogEntry(
            playerId = playerId,
            text = text,
            time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            timestamp = now.toString(),
            type = type
        )
        val state = getOrCreate()
        val updated = state.copy(log = state.log + entry)
        val saved = save(updated)
        broadcaster.broadcastLog(entry)
        return saved
    }

    fun getCollectiveBars(): List<CollectiveBar> = getOrCreate().collectiveBars

    fun addCollectiveBar(bar: CollectiveBar): List<CollectiveBar> {
        val state = getOrCreate()
        val updated = save(state.copy(collectiveBars = state.collectiveBars + bar))
        broadcaster.broadcastCollectiveBars(updated.collectiveBars)
        return updated.collectiveBars
    }

    fun updateCollectiveBar(barId: String, current: Int?, max: Int?): List<CollectiveBar> {
        val state = getOrCreate()
        val bars = state.collectiveBars.map { b ->
            if (b.id != barId) b
            else b.copy(
                current = current?.coerceIn(0, max ?: b.max) ?: b.current,
                max     = max ?: b.max
            )
        }
        val updated = save(state.copy(collectiveBars = bars))
        broadcaster.broadcastCollectiveBars(updated.collectiveBars)
        return updated.collectiveBars
    }

    fun removeCollectiveBar(barId: String): List<CollectiveBar> {
        val state = getOrCreate()
        val updated = save(state.copy(collectiveBars = state.collectiveBars.filter { it.id != barId }))
        broadcaster.broadcastCollectiveBars(updated.collectiveBars)
        return updated.collectiveBars
    }

    fun getNotes(): List<Note> = getOrCreate().notes

    fun addNote(note: Note): List<Note> {
        val state = getOrCreate()
        val newNote = note.copy(timestamp = Instant.now().toString())
        val updated = save(state.copy(notes = state.notes + newNote))
        broadcaster.broadcastNotes(updated.notes)
        return updated.notes
    }

    fun updateNote(noteId: String, text: String?, color: String?): List<Note> {
        val state = getOrCreate()
        val notes = state.notes.map { n ->
            if (n.id != noteId) n
            else n.copy(text = text ?: n.text, color = color ?: n.color)
        }
        val updated = save(state.copy(notes = notes))
        broadcaster.broadcastNotes(updated.notes)
        return updated.notes
    }

    fun removeNote(noteId: String): List<Note> {
        val state = getOrCreate()
        val updated = save(state.copy(notes = state.notes.filter { it.id != noteId }))
        broadcaster.broadcastNotes(updated.notes)
        return updated.notes
    }

    fun clearLog(): GameState {
        val state = getOrCreate()
        val updated = save(state.copy(log = emptyList()))
        broadcaster.broadcastLogCleared()
        return updated
    }

    private fun ensureRootMap(state: GameState): GameState {
        if (state.images.any { it.id == ROOT_MAP_ID }) return state
        val rootMap = GameImage(
            id = ROOT_MAP_ID,
            url = "",
            title = "Mapa",
            timestamp = Instant.now().toString(),
            active = false
        )
        return save(state.copy(images = listOf(rootMap) + state.images))
    }

    private fun createDefault() = GameState(
        id = "main",
        images = emptyList(),
        fastAction = FastAction(
            active = false,
            title = "",
            lockOnePerPlayer = false,
            lockedPlayers = emptyList(),
            options = emptyList(),
            answers = emptyMap()
        ),
        initiative = emptyList(),
        currentTurnIndex = 0,
        log = emptyList()
    )
}
