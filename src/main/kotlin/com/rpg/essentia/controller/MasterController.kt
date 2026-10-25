package com.rpg.essentia.controller

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.PlayerSkillRepository
import com.rpg.essentia.service.ClassKitService
import com.rpg.essentia.service.EssenciaService
import com.rpg.essentia.service.GameStateService
import com.rpg.essentia.service.MasterService
import com.rpg.essentia.service.PlayerCreationService
import com.rpg.essentia.service.PlayerService
import com.rpg.essentia.service.SkillTreeService
import com.rpg.essentia.service.DamageService
import com.rpg.essentia.service.EffectService
import com.rpg.essentia.service.applyDamageModifiers
import com.rpg.essentia.service.RaceService
import com.rpg.essentia.service.SobrecargaService
import com.rpg.essentia.service.TurnService
import com.rpg.essentia.service.MaestriaService
import com.rpg.essentia.service.EnemyTurnService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/master")
class MasterController(
    private val masterService: MasterService,
    private val turnService: TurnService,
    private val classKitService: ClassKitService,
    private val playerCreationService: PlayerCreationService,
    private val playerService: PlayerService,
    private val essenciaService: EssenciaService,
    private val gameStateService: GameStateService,
    private val skillTreeService: SkillTreeService,
    private val playerSkillRepository: PlayerSkillRepository,
    private val sobrecargaService: SobrecargaService,
    private val damageService: DamageService,
    private val effectService: EffectService,
    private val raceService: RaceService,
    private val maestriaService: MaestriaService,
    private val enemyTurnService: EnemyTurnService,
    private val aliadoEfeitoService: com.rpg.essentia.service.AliadoEfeitoService
) {
    @GetMapping("/players")
    fun getPlayers(): List<Player> = masterService.getPlayers()

    @GetMapping("/players/{id}/skill-tree")
    fun getMasterSkillTree(@PathVariable id: String): List<MasterSkillTreeEntry> =
        skillTreeService.getMasterSkillTree(id)

    @PostMapping("/players/{playerId}/grant-skill")
    fun grantSkill(@PathVariable playerId: String, @RequestBody req: Map<String, String>): ResponseEntity<Void> {
        val skillId = req["skillId"] ?: return ResponseEntity.badRequest().build()
        skillTreeService.grantMasterSkill(playerId, skillId)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/players/{playerId}/skill/{skillId}")
    fun resetSkill(@PathVariable playerId: String, @PathVariable skillId: String): ResponseEntity<Void> {
        val ps = playerSkillRepository.findByPlayerIdAndSkillId(playerId, skillId)
            ?: return ResponseEntity.noContent().build()
        playerSkillRepository.delete(ps)
        // Limpa o slot que tinha essa skill e notifica o jogador
        masterService.clearSkillFromSlots(playerId, skillId)
        return ResponseEntity.noContent().build()
    }

    // Raças
    @GetMapping("/races")
    fun listRaces(): List<Race> = raceService.listAll()

    @PostMapping("/races")
    fun createRace(@RequestBody race: Race): ResponseEntity<Race> =
        ResponseEntity.status(HttpStatus.CREATED).body(raceService.create(race))

    @PutMapping("/races/{id}")
    fun updateRace(@PathVariable id: String, @RequestBody race: Race): Race =
        raceService.update(id, race)

    @DeleteMapping("/races/{id}")
    fun deleteRace(@PathVariable id: String): ResponseEntity<Void> {
        raceService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/kits")
    fun listKits(): List<ClassKit> = classKitService.listAll()

    @PostMapping("/kits")
    fun createKit(@RequestBody kit: ClassKit): ResponseEntity<ClassKit> =
        ResponseEntity.status(HttpStatus.CREATED).body(classKitService.create(kit))

    @PutMapping("/kits/{id}")
    fun updateKit(@PathVariable id: String, @RequestBody kit: ClassKit): ClassKit =
        classKitService.update(id, kit)

    @DeleteMapping("/kits/{id}")
    fun deleteKit(@PathVariable id: String): ResponseEntity<Void> {
        classKitService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/kits/{className}")
    fun getKit(@PathVariable className: String): ClassKit = classKitService.getByClass(className)

    @PostMapping("/players")
    fun createPlayer(@RequestBody req: CreatePlayerRequest): ResponseEntity<Player> {
        val player = playerCreationService.createPlayer(req)
        return ResponseEntity.status(HttpStatus.CREATED).body(player)
    }

    @PutMapping("/players/{id}")
    fun updatePlayer(@PathVariable id: String, @RequestBody req: UpdatePlayerRequest): Player =
        playerCreationService.updatePlayer(id, req)

    @DeleteMapping("/players/{id}")
    fun deletePlayer(@PathVariable id: String): ResponseEntity<Void> {
        playerCreationService.deletePlayer(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/players/{id}/toggle-active")
    fun toggleActive(@PathVariable id: String): Player =
        masterService.toggleActive(id)

    @PostMapping("/approve-item")
    fun approveItem(@RequestBody req: ApproveRejectItemRequest): Player =
        masterService.approveItem(req.playerId, req.requestId)

    @PostMapping("/reject-item")
    fun rejectItem(@RequestBody req: ApproveRejectItemRequest): Player =
        masterService.rejectItem(req.playerId, req.requestId)

    @PutMapping("/exp")
    fun grantExp(@RequestBody req: ExpRequest): Player =
        masterService.grantExp(req.playerId, req.amount)

    @PostMapping("/next-turn")
    fun nextTurn(): TurnUpdate = turnService.nextTurn()

    @PostMapping("/players/{id}/recalculate")
    fun recalculate(@PathVariable id: String): Player = playerService.recalculate(id)

    @PostMapping("/players/{id}/reset-attributes")
    fun resetAttributes(@PathVariable id: String): Player = playerCreationService.resetAttributes(id)

    @PostMapping("/reset-skills")
    fun resetSkills(@RequestBody req: ResetSkillsRequest) =
        masterService.resetSkills(req.playerId)

    @GetMapping("/initiative")
    fun getInitiative(): List<InitiativeEntry> = gameStateService.getOrCreate().initiative

    @GetMapping("/turn-state")
    fun getTurnState(): Map<String, Any> {
        val state = gameStateService.getOrCreate()
        return mapOf(
            "initiative"        to state.initiative,
            "currentTurnIndex"  to state.currentTurnIndex,
            "totalTurns"        to state.totalTurns
        )
    }

    @PostMapping("/initiative/entrar")
    fun entrarNaLuta(@RequestBody req: EntrarNaLutaRequest): List<InitiativeEntry> =
        masterService.entrarNaLuta(req.playerId, req.valor)

    @PostMapping("/initiative/sair")
    fun sairDaLuta(@RequestBody req: SairDaLutaRequest): List<InitiativeEntry> =
        masterService.sairDaLuta(req.playerId)

    @PutMapping("/initiative")
    fun setInitiative(@RequestBody entries: List<InitiativeEntry>) =
        masterService.setInitiative(entries)

    @PostMapping("/status-effect")
    fun addStatusEffect(@RequestBody req: StatusEffectRequest): Player =
        masterService.addStatusEffect(req.playerId, req.effect)

    @DeleteMapping("/status-effect/{effectId}")
    fun removeStatusEffect(
        @PathVariable effectId: String,
        @RequestBody req: StatusEffectDeleteRequest
    ): Player = masterService.removeStatusEffect(req.playerId, effectId)

    @DeleteMapping("/players/{playerId}/status-effects")
    fun removeAllStatusEffects(@PathVariable playerId: String): Player =
        masterService.removeAllStatusEffects(playerId)

    @PostMapping("/players/{id}/items")
    fun addItem(
        @PathVariable id: String,
        @RequestBody req: AddItemRequest
    ): Player = masterService.addItem(id, req)

    @DeleteMapping("/players/{id}/items/{itemId}")
    fun removeItem(
        @PathVariable id: String,
        @PathVariable itemId: String
    ): Player = masterService.removeItem(id, itemId)

    @PutMapping("/players/{id}/items/{itemId}")
    fun adjustItemQty(
        @PathVariable id: String,
        @PathVariable itemId: String,
        @RequestBody req: AdjustItemQtyRequest
    ): Player = masterService.adjustItemQty(id, itemId, req.delta)

    /** Maestrias do jogador já calculadas (nome, bônus, nível disponível). */
    @GetMapping("/players/{id}/maestrias")
    fun maestrias(@PathVariable id: String): List<MaestriaGrupoInfo> =
        maestriaService.infos(playerService.load(id))

    @PutMapping("/players/{id}/maestrias/{grupo}/usos")
    fun ajustarUsosMaestria(
        @PathVariable id: String,
        @PathVariable grupo: String,
        @RequestBody req: MaestriaUsesRequest
    ): Player = maestriaService.ajustarUsos(id, grupo, req.uses)

    @PutMapping("/players/{id}/maestrias/{grupo}/nivel")
    fun definirNivelMaestria(@PathVariable id: String, @PathVariable grupo: String, @RequestBody req: NivelClasseRequest): Player =
        maestriaService.definirNivel(id, grupo, req.nivel)

    @PostMapping("/players/{id}/maestrias/{grupo}/confirmar")
    fun confirmarNivelMaestria(@PathVariable id: String, @PathVariable grupo: String): Player =
        maestriaService.confirmarNivel(id, grupo)

    /** Dar Escudo (PV temporário) a um jogador: skill em aliado ou decisão do mestre. */
    @PostMapping("/players/{id}/escudo")
    fun darEscudo(@PathVariable id: String, @RequestBody req: Map<String, Int>): Player =
        playerService.darEscudo(id, req["valor"] ?: 0)

    /** Ajuste manual do Escudo (delta positivo dá até o teto, negativo tira até 0). */
    @PutMapping("/players/{id}/escudo")
    fun ajustarEscudo(@PathVariable id: String, @RequestBody req: DeltaRequest): Player =
        playerService.ajustarEscudo(id, req.delta)

    /** Fim do dia: zera o Escudo de todos os jogadores. */
    @PostMapping("/fim-do-dia")
    fun fimDoDia(): List<Player> = playerService.fimDoDia()

    /** Dá (delta positivo) ou tira sorte de um jogador. */
    @PutMapping("/players/{id}/sorte")
    fun ajustarSorte(@PathVariable id: String, @RequestBody req: DeltaRequest): Player =
        playerService.ajustarSorte(id, req.delta)

    @PutMapping("/players/{id}/nivel-classe")
    fun setNivelClasse(@PathVariable id: String, @RequestBody req: NivelClasseRequest): Player =
        playerService.setNivelClasse(id, req.nivel)

    /* ── Configurações editáveis ─────────────────────────────── */

    @PutMapping("/maestria-config")
    fun updateMaestriaConfig(@RequestBody cfg: MaestriaConfig): MaestriaConfig = maestriaService.updateConfig(cfg)

    @PutMapping("/combate-config")
    fun updateCombateConfig(@RequestBody cfg: CombateConfig): CombateConfig = gameStateService.updateCombateConfig(cfg)

    @PutMapping("/achado-config")
    fun updateAchadoConfig(@RequestBody cfg: AchadoConfig): AchadoConfig = gameStateService.updateAchadoConfig(cfg)

    @PutMapping("/encontro-config")
    fun updateEncontroConfig(@RequestBody cfg: EncontroConfig): EncontroConfig = gameStateService.updateEncontroConfig(cfg)

    /* ── Turno automático dos inimigos ───────────────────────── */

    @PostMapping("/enemy-turn/propose")
    fun proposeEnemyTurn(@RequestBody req: EnemyTurnProposeRequest): EnemyAction =
        enemyTurnService.propor(req.entityId, req.entityKind, req.alvoId, req.ataqueIdx)

    @PostMapping("/enemy-turn/{actionId}/swap")
    fun swapEnemyTurn(@PathVariable actionId: String, @RequestBody req: EnemyTurnSwapRequest): EnemyAction =
        enemyTurnService.trocar(actionId, req.alvoId, req.ataqueIdx)

    @PostMapping("/enemy-turn/{actionId}/allow")
    fun allowEnemyTurn(@PathVariable actionId: String): EnemyAction = enemyTurnService.permitir(actionId)

    @PostMapping("/enemy-turn/{actionId}/contra-ataque")
    fun counterAttack(@PathVariable actionId: String, @RequestBody req: Map<String, Boolean>): EnemyAction =
        enemyTurnService.contraAtaque(actionId, req["liberar"] ?: true)

    @PostMapping("/enemy-turn/{actionId}/skip")
    fun skipEnemyTurn(@PathVariable actionId: String): EnemyAction = enemyTurnService.pular(actionId)

    @GetMapping("/combat-log")
    fun combatLog(@RequestParam(required = false) combatId: String?): Map<String, Any> = enemyTurnService.relatorio(combatId)

    @PutMapping("/players/{id}/skill/{skillId}/block")
    fun setSkillBlocked(
        @PathVariable id: String,
        @PathVariable skillId: String,
        @RequestBody req: SetSkillBlockedRequest
    ): PlayerSkill = masterService.setSkillBlocked(id, skillId, req.blocked)

    // Equipamento
    @PutMapping("/players/{id}/equipment/{slot}")
    fun setEquipment(
        @PathVariable id: String,
        @PathVariable slot: String,
        @RequestBody req: SetEquipmentRequest
    ): Player = masterService.setEquipment(id, slot, req)

    @PutMapping("/players/{id}/gold")
    fun adjustGold(
        @PathVariable id: String,
        @RequestBody req: DeltaRequest
    ): Player = masterService.adjustGold(id, req.delta)

    @DeleteMapping("/players/{id}/equipment/{slot}")
    fun clearEquipment(
        @PathVariable id: String,
        @PathVariable slot: String
    ): Player = masterService.clearEquipment(id, slot)

    // Essências — catálogo
    @GetMapping("/essencias")
    fun listEssencias(): List<Essencia> = essenciaService.listAll()

    @PostMapping("/essencias")
    fun createEssencia(@RequestBody essencia: Essencia): ResponseEntity<Essencia> =
        ResponseEntity.status(HttpStatus.CREATED).body(essenciaService.create(essencia))

    @PutMapping("/essencias/{id}")
    fun updateEssencia(@PathVariable id: String, @RequestBody essencia: Essencia): Essencia =
        essenciaService.update(id, essencia)

    @DeleteMapping("/essencias/{id}")
    fun deleteEssencia(@PathVariable id: String): ResponseEntity<Void> {
        essenciaService.delete(id)
        return ResponseEntity.noContent().build()
    }

    // Barras coletivas
    @GetMapping("/collective-bars")
    fun getCollectiveBars(): List<CollectiveBar> = gameStateService.getCollectiveBars()

    @PostMapping("/collective-bars")
    fun addCollectiveBar(@RequestBody bar: CollectiveBar): List<CollectiveBar> =
        gameStateService.addCollectiveBar(bar)

    @PutMapping("/collective-bars/{barId}")
    fun updateCollectiveBar(
        @PathVariable barId: String,
        @RequestBody req: Map<String, Int>
    ): List<CollectiveBar> = gameStateService.updateCollectiveBar(barId, req["current"], req["max"])

    @DeleteMapping("/collective-bars/{barId}")
    fun removeCollectiveBar(@PathVariable barId: String): List<CollectiveBar> =
        gameStateService.removeCollectiveBar(barId)

    // Barras customizadas
    @PostMapping("/players/{id}/custom-bars")
    fun addCustomBar(@PathVariable id: String, @RequestBody bar: CustomBar): Player =
        masterService.addCustomBar(id, bar)

    @PutMapping("/players/{id}/custom-bars/{barId}")
    fun updateCustomBar(
        @PathVariable id: String,
        @PathVariable barId: String,
        @RequestBody req: Map<String, Int>
    ): Player = masterService.updateCustomBar(id, barId, req["current"], req["max"])

    @DeleteMapping("/players/{id}/custom-bars/{barId}")
    fun removeCustomBar(@PathVariable id: String, @PathVariable barId: String): Player =
        masterService.removeCustomBar(id, barId)

    @PostMapping("/enemy-attack")
    fun enemyAttack(@RequestBody req: EnemyAttackRequest): Player {
        val targetEffects = playerService.load(req.targetPlayerId).statusEffects
        val finalDamage = applyDamageModifiers(req.damage, emptyList(), targetEffects)
        return playerService.adjustHp(req.targetPlayerId, -finalDamage)
    }

    @PostMapping("/ally-effect/approve")
    fun approveAllyEffect(@RequestBody req: AllyEffectApproveBody) = aliadoEfeitoService.aprovar(req)

    @PostMapping("/ally-effect/reject")
    fun rejectAllyEffect(@RequestBody req: Map<String, String>) =
        aliadoEfeitoService.rejeitar(req["requestId"] ?: "", req["playerId"] ?: "")

    @PostMapping("/damage/approve")
    fun approveDamage(@RequestBody req: DamageApproveBody) = damageService.approveDamage(req)

    @PostMapping("/damage/reject")
    fun rejectDamage(@RequestBody req: Map<String, String>) =
        damageService.rejectDamage(req["requestId"] ?: "", req["playerId"] ?: "")

    @PostMapping("/effect/approve")
    fun approveEffect(@RequestBody req: EffectApproveBody) = effectService.approveEffect(req)

    @PostMapping("/effect/reject")
    fun rejectEffect(@RequestBody req: Map<String, String>) =
        effectService.rejectEffect(req["requestId"] ?: "")

    @PostMapping("/players/{id}/sobrecarga/approve")
    fun approveSobrecarga(@PathVariable id: String): Player =
        sobrecargaService.approveSobrecarga(id)

    @PostMapping("/players/{id}/sobrecarga/reject")
    fun rejectSobrecarga(@PathVariable id: String, @RequestBody req: SobrecargaRejectRequest): Player =
        sobrecargaService.rejectSobrecarga(id, req.roll, req.danoDado)

    @PostMapping("/players/{id}/essencias")
    fun grantEssencia(
        @PathVariable id: String,
        @RequestBody req: GrantEssenciaRequest
    ): Player = essenciaService.grantEssencia(id, req.essenciaId)

    @DeleteMapping("/players/{id}/essencias/{essenciaId}")
    fun removeEssencia(
        @PathVariable id: String,
        @PathVariable essenciaId: String
    ): Player = essenciaService.removeEssencia(id, essenciaId)

    @PutMapping("/players/{id}/essencias/{essenciaId}/nivel")
    fun setNivelEssencia(
        @PathVariable id: String,
        @PathVariable essenciaId: String,
        @RequestBody req: NivelEssenciaRequest
    ): Player = essenciaService.setNivel(id, essenciaId, req.nivel)

    @PutMapping("/players/{id}/proficiencias")
    fun setProficiencias(
        @PathVariable id: String,
        @RequestBody req: ProficienciasRequest
    ): Player = playerService.setProficiencias(id, req.proficiencias)

    // Notas fixas
    @GetMapping("/notes")
    fun getNotes(): List<Note> = gameStateService.getNotes()

    @PostMapping("/notes")
    fun addNote(@RequestBody note: Note): List<Note> = gameStateService.addNote(note)

    @PutMapping("/notes/{noteId}")
    fun updateNote(@PathVariable noteId: String, @RequestBody req: Map<String, String>): List<Note> =
        gameStateService.updateNote(noteId, req["text"], req["color"])

    @DeleteMapping("/notes/{noteId}")
    fun removeNote(@PathVariable noteId: String): List<Note> = gameStateService.removeNote(noteId)
}
