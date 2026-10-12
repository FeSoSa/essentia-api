package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

data class GameImage(
    val id: String = "",
    val url: String = "",
    val title: String = "",
    val timestamp: String = "",
    val active: Boolean = false
)

data class GameDocument(
    val id: String = "",
    val url: String = "",
    val title: String = "",
    val timestamp: String = "",
    val active: Boolean = false
)

data class FastActionOption(
    val id: String = "",
    val text: String = "",
    val color: String = ""
)

data class FastAction(
    val active: Boolean = false,
    val title: String = "",
    val lockOnePerPlayer: Boolean = false,
    val lockedPlayers: List<String> = emptyList(),
    val options: List<FastActionOption> = emptyList(),
    val answers: Map<String, String> = emptyMap()   // playerId -> optionId
)

@Document(collection = "fast_action_templates")
data class FastActionTemplate(
    @Id val id: String = "",
    val name: String = "",
    val title: String = "",
    val options: List<FastActionOption> = emptyList()
)

data class InitiativeEntry(
    val playerId: String = "",
    val name: String = "",
    val value: Int = 0
)

data class LogEntry(
    val playerId: String = "",
    val text: String = "",
    val time: String = "",
    val timestamp: String = "",
    val type: String = "info"   // "skill"|"hp"|"flow"|"ether"|"attr"|"level"|"item"|"essencia"|"info"
)

data class CollectiveBar(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val color: String = "#3b82f6",
    val current: Int = 0,
    val max: Int = 10
)

data class Note(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String = "",
    val color: String = "#fbbf24",
    val timestamp: String = ""
)

@Document(collection = "game_state")
data class GameState(
    @Id val id: String = "main",
    val images: List<GameImage> = emptyList(),
    val documents: List<GameDocument> = emptyList(),
    val fastAction: FastAction = FastAction(),
    val initiative: List<InitiativeEntry> = emptyList(),
    val currentTurnIndex: Int = 0,
    val totalTurns: Int = 0,
    val log: List<LogEntry> = emptyList(),
    val collectiveBars: List<CollectiveBar> = emptyList(),
    val notes: List<Note> = emptyList(),
    val combatId: String? = null,                       // gerado no início do combate, null fora de combate
    val maestriaConfig: MaestriaConfig = MaestriaConfig(),
    val combateConfig: CombateConfig = CombateConfig(),
    val achadoConfig: AchadoConfig = AchadoConfig(),
    val encontro: EncontroConfig = EncontroConfig(),      // chave nova: a versão por razão (encontroConfig) foi trocada pela de perigo
    val migracoes: List<String> = emptyList()           // migrações de dados já aplicadas
)

/** Um nível de maestria de grupo (valores totais no nível). */
data class NivelMaestriaGrupo(
    val nivel: Int,
    val usos: Int,       // usos acumulados para o nível ficar disponível
    val danoPct: Int,    // + % de dano nas skills do grupo e no ataque com a arma
    val custoPct: Int    // - % de custo nas skills do grupo
)

/** Maestria por grupo (categoria de arma ou Essência), níveis 1 a 4 (editável no painel do mestre). */
data class MaestriaConfig(
    val niveis: List<NivelMaestriaGrupo> = listOf(
        NivelMaestriaGrupo(1, 0, 0, 0),
        NivelMaestriaGrupo(2, 5, 10, 10),
        NivelMaestriaGrupo(3, 12, 20, 15),
        NivelMaestriaGrupo(4, 22, 30, 20)
    )
) {
    fun nivel(n: Int): NivelMaestriaGrupo =
        niveis.firstOrNull { it.nivel == n } ?: niveis.maxByOrNull { it.nivel } ?: NivelMaestriaGrupo(n, 0, 0, 0)
    val nivelMax: Int get() = niveis.maxOfOrNull { it.nivel } ?: 4
}

/** Regras de combate editáveis no painel do mestre. */
data class CombateConfig(
    val critMultBase: Double = 1.5,                     // multiplicador da base no crítico
    val critMultDado: Double = 2.0,                     // multiplicador do d20 de dano no crítico
    val equilibrioSemProficiencia: Double = 6.0,        // ataque básico desarmado ou com arma sem proficiência
    val equilibrioComProficiencia: Double = 5.0,        // ataque básico com arma em que tem proficiência
    val esPorAcertoBasico: Int = 3,                     // ES recuperada a cada acerto de ataque básico
    val desviosPorCombate: Int = 3,
    val tiers: List<TierRegra> = TIERS_PADRAO           // equilíbrio e custo base por tier de skill
) {
    /** Regra do tier (1 a 5); fora da faixa usa o mais próximo. */
    fun tier(n: Int): TierRegra =
        tiers.firstOrNull { it.tier == n } ?: TIERS_PADRAO[(n.coerceIn(1, TIERS_PADRAO.size)) - 1]
}

/**
 * Nível de achado do sorteador de itens: a chance de cada raridade sair.
 * Pesos relativos (não precisam somar 100), chaves iguais às raridades do catálogo.
 */
data class NivelAchado(
    val id: String,
    val nome: String,
    val pesos: Map<String, Int>
)

/** Regras do sorteador de itens (editáveis no painel do mestre). */
data class AchadoConfig(
    val niveis: List<NivelAchado> = listOf(
        NivelAchado("pobre",   "Pobre",   mapOf("branco" to 70, "verde" to 25, "azul" to 5,  "roxo" to 0,  "amarelo" to 0,  "rosa" to 0)),
        NivelAchado("comum",   "Comum",   mapOf("branco" to 40, "verde" to 35, "azul" to 18, "roxo" to 6,  "amarelo" to 1,  "rosa" to 0)),
        NivelAchado("rico",    "Rico",    mapOf("branco" to 10, "verde" to 25, "azul" to 35, "roxo" to 22, "amarelo" to 7,  "rosa" to 1)),
        NivelAchado("tesouro", "Tesouro", mapOf("branco" to 0,  "verde" to 5,  "azul" to 25, "roxo" to 35, "amarelo" to 28, "rosa" to 7))
    ),
    // Quando o tipo é "qualquer": chance de cada tipo de item sair
    val pesosTipo: Map<String, Int> = mapOf(
        "arma" to 25, "armadura" to 20, "acessorio" to 15, "consumivel" to 30, "material" to 10
    )
)

/**
 * Dificuldade do gerador de encontros, medida por simulação da luta.
 * perigo = quanto do PV do grupo se perde em média (%), contando 100% quando o grupo todo cai.
 */
data class DificuldadeEncontro(
    val id: String,
    val nome: String,
    val perigo: Int
)

/** Regras do gerador de encontros (editáveis no painel do mestre). */
data class EncontroConfig(
    val dificuldades: List<DificuldadeEncontro> = listOf(
        DificuldadeEncontro("facil",   "Fácil",   15),
        DificuldadeEncontro("medio",   "Médio",   35),
        DificuldadeEncontro("dificil", "Difícil", 60),
        DificuldadeEncontro("mortal",  "Mortal",  85)
    ),
    val hordaMax: Int = 8                               // teto de inimigos numa horda
)
