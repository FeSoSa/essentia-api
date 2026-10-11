package com.rpg.essentia.service

import com.rpg.essentia.model.*

/**
 * Maestria por grupo (categoria de arma ou Essência) e requisitos de skill por origem e tier.
 * Espelhado em essentia-player/lib/rules.ts.
 */
object MaestriaRules {
    const val ARMA = "ARMA"
    const val ESSENCIA = "ESSENCIA"

    private val NOME_CATEGORIA = mapOf(
        "espadas" to "Espadas", "rapieiras" to "Rapieiras", "alabardas" to "Alabardas", "lancas" to "Lanças",
        "machados-de-guerra" to "Machados de guerra", "machadinhas" to "Machadinhas", "martelos" to "Martelos",
        "armas-colossais" to "Armas colossais", "adagas" to "Adagas", "garras" to "Garras", "cajado" to "Cajado",
        "grimorio" to "Grimório", "orbe" to "Orbe", "arcanos" to "Arcanos", "arcos" to "Arcos", "bestas" to "Bestas",
        "pistolas" to "Pistolas", "luvas-manoplas" to "Luvas & manoplas", "escudos" to "Escudos"
    )

    fun nomeGrupo(grupo: String, tipo: String, essencias: List<Essencia>): String =
        if (tipo == ESSENCIA) essencias.firstOrNull { it.id == grupo }?.name ?: grupo
        else NOME_CATEGORIA[grupo] ?: grupo.replaceFirstChar { it.uppercase() }

    // ── Grupo da skill ──────────────────────────────────────────────────

    /**
     * Essência que vale para o jogador: a própria, se ele a tem; se for Derivada liberada pela mãe
     * (mãe no nível configurado na Derivada), a mãe.
     */
    fun essenciaEfetiva(essenciaId: String?, player: Player, essencias: List<Essencia>): String? {
        if (essenciaId == null) return null
        if (player.essenciasObtidas.any { it.essenciaId == essenciaId }) return essenciaId
        val ess = essencias.firstOrNull { it.id == essenciaId } ?: return essenciaId
        return ess.parentId?.takeIf { ess.liberadaPelaMae(nivelEssencia(player, it)) } ?: essenciaId
    }

    /** Nível do jogador na Essência (null = não tem). */
    fun nivelEssencia(player: Player, essenciaId: String): Int? =
        player.essenciasObtidas.firstOrNull { it.essenciaId == essenciaId }?.nivel

    /** Skills da Essência aparecem para o jogador: ele tem a Essência, ou é Derivada liberada pela mãe. */
    fun essenciaAcessivel(essenciaId: String?, player: Player, essencias: List<Essencia>): Boolean {
        if (essenciaId == null) return false
        if (player.essenciasObtidas.any { it.essenciaId == essenciaId }) return true
        val ess = essencias.firstOrNull { it.id == essenciaId } ?: return false
        return ess.parentId != null && ess.liberadaPelaMae(nivelEssencia(player, ess.parentId))
    }

    /** Categoria principal (grupo de maestria) de uma skill de arma. */
    fun categoriaPrincipal(skill: Skill): String? =
        skill.grupoMaestria?.lowercase() ?: skill.categoriasArma().firstOrNull()

    /** Arma que a skill usa: a do slot (dano pela arma) ou a primeira equipada de uma categoria da skill. */
    fun armaDaSkill(skill: Skill, equipment: Equipment): WeaponEquip? {
        if (skill.damageSource == "weapon")
            return if (skill.weaponSlot == "offHand") equipment.offHand else equipment.mainHand
        val cats = skill.categoriasArma()
        return listOfNotNull(equipment.mainHand, equipment.offHand).firstOrNull { it.weaponType.lowercase() in cats }
    }

    /**
     * Grupo de maestria da skill para o jogador (grupo, tipo), ou null se não tem grupo.
     *   arma: categoria principal; classe com dano de arma conta como ataque com a arma equipada.
     *   essência: a Essência efetiva (Derivada sem posse direta usa a mãe).
     */
    fun grupoDaSkill(skill: Skill, player: Player, essencias: List<Essencia>): Pair<String, String>? = when {
        skill.type == "weapon" -> categoriaPrincipal(skill)?.let { it to ARMA }
        skill.type == "essencia" -> essenciaEfetiva(skill.essenciaId, player, essencias)?.let { it to ESSENCIA }
        skill.damageSource == "weapon" -> armaDaSkill(skill, player.equipment)?.weaponType?.lowercase()?.takeIf { it.isNotBlank() }?.let { it to ARMA }
        else -> null
    }

    // ── Maestria ────────────────────────────────────────────────────────

    fun maestria(player: Player, grupo: String): MaestriaGrupo? = player.maestrias.firstOrNull { it.grupo == grupo }

    /** Maestria de arma só vale (conta uso, sobe, dá bônus) com proficiência na categoria. */
    fun ativa(player: Player, m: MaestriaGrupo): Boolean =
        m.tipoGrupo != ARMA || ProficienciaRules.tem(player.proficiencias, m.grupo)

    /** Maestria que sobe por uso: todas, menos as de Essência Elemental (o mestre define o nível). */
    fun sobePorUso(m: MaestriaGrupo, essencias: List<Essencia>): Boolean =
        m.tipoGrupo != ESSENCIA || essencias.firstOrNull { it.id == m.grupo }?.elemental != true

    /** Usos já bastam para o próximo nível (o mestre confirma). */
    fun disponivel(m: MaestriaGrupo, cfg: MaestriaConfig): Boolean =
        m.nivel < cfg.nivelMax && m.usos >= cfg.nivel(m.nivel + 1).usos

    /** Bônus da maestria do grupo para o jogador: (dano %, custo %). Zero se não tem ou está inativa. */
    fun bonus(player: Player, grupo: String?, cfg: MaestriaConfig): Pair<Int, Int> {
        val m = grupo?.let { maestria(player, it) }?.takeIf { ativa(player, it) } ?: return 0 to 0
        val n = cfg.nivel(m.nivel)
        return n.danoPct to n.custoPct
    }

    /**
     * Conta 1 uso no grupo a cada uso válido em combate (todos contam). Retorna a lista nova, ou null se não contou
     * (fora de combate, sem maestria no grupo, arma sem proficiência ou Essência Elemental).
     */
    fun registrarUso(player: Player, grupo: String, combatId: String?, essencias: List<Essencia> = emptyList()): List<MaestriaGrupo>? {
        if (combatId == null) return null
        val m = maestria(player, grupo) ?: return null
        if (!ativa(player, m) || !sobePorUso(m, essencias)) return null
        val novo = m.copy(usos = m.usos + 1, ultimoCombateUso = combatId)
        return player.maestrias.map { if (it.grupo == grupo) novo else it }
    }

    /** Mestre confirma o nível disponível: sobe 1, nunca acima do máximo. */
    fun confirmarNivel(m: MaestriaGrupo, cfg: MaestriaConfig): MaestriaGrupo? =
        if (disponivel(m, cfg)) m.copy(nivel = (m.nivel + 1).coerceAtMost(cfg.nivelMax)) else null

    /** Garante maestria nível 1 para cada proficiência (arma) e cada Essência obtida. Não remove nada. */
    fun garantir(player: Player): Player {
        val existentes = player.maestrias.map { it.grupo }.toSet()
        val novas = player.proficiencias.map { it.lowercase() }.filter { it !in existentes }.distinct()
            .map { MaestriaGrupo(grupo = it, tipoGrupo = ARMA) } +
            player.essenciasObtidas.map { it.essenciaId }.filter { it !in existentes }.distinct()
                .map { MaestriaGrupo(grupo = it, tipoGrupo = ESSENCIA) }
        return if (novas.isEmpty()) player else player.copy(maestrias = player.maestrias + novas)
    }

    fun info(player: Player, m: MaestriaGrupo, cfg: MaestriaConfig, essencias: List<Essencia>): MaestriaGrupoInfo {
        val n = cfg.nivel(m.nivel)
        val porUso = sobePorUso(m, essencias)
        val proximo = if (porUso && m.nivel < cfg.nivelMax) cfg.nivel(m.nivel + 1).usos else null
        return MaestriaGrupoInfo(
            grupo = m.grupo, tipoGrupo = m.tipoGrupo, nome = nomeGrupo(m.grupo, m.tipoGrupo, essencias),
            nivel = m.nivel, nivelMax = cfg.nivelMax, usos = m.usos, proximoNivelUsos = proximo,
            disponivel = porUso && disponivel(m, cfg), danoPct = n.danoPct, custoPct = n.custoPct, ativa = ativa(player, m),
            sobePorUso = porUso
        )
    }

    // ── Requisito por origem e tier ─────────────────────────────────────

    /**
     * O que falta para o jogador ter/usar a skill, ou null se cumpre. O tier (1 a 4) pede:
     *   classe: nível de classe >= tier
     *   essência: nível da Essência (efetiva) >= tier
     *   arma: maestria >= tier na categoria principal e na categoria da arma equipada (com proficiência)
     */
    fun requisito(skill: Skill, player: Player, essencias: List<Essencia>): String? {
        val tier = skill.tier ?: return null
        return when (skill.type) {
            "class" -> if (player.nivelClasse < tier) "Requer nível de classe $tier (atual ${player.nivelClasse})" else null
            "essencia" -> {
                val ess = essenciaEfetiva(skill.essenciaId, player, essencias)
                val nivel = player.essenciasObtidas.firstOrNull { it.essenciaId == ess }?.nivel ?: 0
                if (nivel < tier) "Requer ${nomeGrupo(ess ?: "?", ESSENCIA, essencias)} nível $tier (atual $nivel)" else null
            }
            "weapon" -> {
                val principal = categoriaPrincipal(skill) ?: return null
                val usada = armaDaSkill(skill, player.equipment)?.weaponType?.lowercase()
                listOfNotNull(principal, usada).distinct().firstNotNullOfOrNull { cat ->
                    val m = maestria(player, cat)?.takeIf { ativa(player, it) }
                    val nivel = m?.nivel ?: 0
                    if (nivel < tier) "Requer maestria de ${nomeGrupo(cat, ARMA, essencias)} $tier (atual $nivel)" else null
                }
            }
            else -> null
        }
    }
}
