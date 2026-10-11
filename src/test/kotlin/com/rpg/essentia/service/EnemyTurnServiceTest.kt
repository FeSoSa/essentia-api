package com.rpg.essentia.service

import com.rpg.essentia.model.*
import com.rpg.essentia.repository.EnemyActionRepository
import com.rpg.essentia.repository.PlayerRepository
import com.rpg.essentia.websocket.WebSocketBroadcaster
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.random.Random

class EnemyTurnServiceTest {

    private lateinit var service: EnemyTurnService
    private lateinit var enemyService: EnemyService
    private lateinit var playerRepository: PlayerRepository
    private val actions = linkedMapOf<String, EnemyAction>()

    /** Random que devolve uma sequência fixa de d20. */
    private class Dados(vararg valores: Int) : Random() {
        private val fila = ArrayDeque(valores.toList())
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int) = fila.removeFirst()
    }

    private fun player(id: String, def: Int, hp: Int) = Player(
        id = id, code = id, char = CharInfo(name = id), hp = Vital(hp, 100),
        attributes = Attributes(defense = def), effectiveAttributes = Attributes(defense = def)
    )

    @BeforeEach
    fun setUp() {
        actions.clear()
        enemyService = mock(EnemyService::class.java)
        playerRepository = mock(PlayerRepository::class.java)
        val gameState = mock(GameStateService::class.java)
        val repo = mock(EnemyActionRepository::class.java)
        doAnswer { inv -> inv.getArgument<EnemyAction>(0).also { actions[it.id] = it } }.`when`(repo).save(any())
        `when`(gameState.getOrCreate()).thenReturn(GameState(combatId = "c1"))
        `when`(gameState.combateConfig()).thenReturn(CombateConfig())
        `when`(gameState.rodadaAtual(any() ?: GameState())).thenReturn(0)
        val jogadores = listOf(player("forte", 14, 50), player("fraco", 9, 80), player("fraco2", 9, 40))
        `when`(playerRepository.findAll()).thenReturn(jogadores)
        jogadores.forEach { p -> `when`(playerRepository.findById(p.id)).thenReturn(java.util.Optional.of(p)) }
        `when`(enemyService.findInstance("e1")).thenReturn(EnemyInstance(
            instanceId = "e1", name = "Javali",
            attributes = EnemyAttributes(strength = 16, agility = 10),
            esMax = 10,
            attacks = listOf(
                EnemyAttack(name = "Básico", damageBase = 10, damageAttribute = "FOR", equilibrio = 7),
                EnemyAttack(name = "Investida", damageBase = 20, damageAttribute = "FOR", equilibrio = 6, custoEs = 6),
                EnemyAttack(name = "Golpe Caro", damageBase = 40, damageAttribute = "FOR", equilibrio = 4, custoEs = 20),
                EnemyAttack(name = "Passiva")
            )
        ))
        service = EnemyTurnService(enemyService, mock(BossService::class.java), playerRepository,
            mock(PlayerService::class.java), gameState, repo, mock(WebSocketBroadcaster::class.java))
    }

    @Test
    fun `sugere alvo de menor DEF, desempate por menor PV, e o ataque mais forte que consegue pagar`() {
        service.random = Dados(12, 15)
        val a = service.propor("e1", "enemy", null, null)
        assertEquals("fraco2", a.alvoId)
        assertEquals("Investida", a.ataqueNome) // Golpe Caro custa 20 > 10 de ES
        assertEquals(12 + 2, a.totalAcerto)      // FOR 16 = mod +2
        assertTrue(a.acertou)                    // 14 >= DEF 9
        assertEquals(20 + 30 / 6, a.dano)        // d20 15 × mod 2 / 6
    }

    @Test
    fun `erro nao rola dano`() {
        service.random = Dados(3)
        val a = service.propor("e1", "enemy", "forte", 0)
        assertFalse(a.acertou)                  // 3 + 2 < 14
        assertEquals(null, a.dano)
    }
}
