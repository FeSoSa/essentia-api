package com.rpg.essentia.service

import com.rpg.essentia.model.StatusEffect

/** Soma os bônus/penalidades de um AutoEffect.type específico entre os status effects ativos. Passivo: ignora trigger. */
fun List<StatusEffect>.activeAutoEffectsOfType(type: String) =
    filter { it.durationTurns != 0 }.flatMap { it.effects }.filter { it.type == type }

fun List<StatusEffect>.isSkillBlocked(): Boolean =
    activeAutoEffectsOfType("block_skill").isNotEmpty()

/** Soma percentuais (em fração, ex: 20% -> 0.20) de modify_damage_dealt/modify_damage_received ativos. */
fun List<StatusEffect>.damageModifierPercent(type: String): Double =
    activeAutoEffectsOfType(type).sumOf { (it.percentual ?: 0) / 100.0 }

fun applyDamageModifiers(baseDamage: Int, attackerEffects: List<StatusEffect>, targetEffects: List<StatusEffect>): Int {
    val dealtMod = attackerEffects.damageModifierPercent("modify_damage_dealt")
    val receivedMod = targetEffects.damageModifierPercent("modify_damage_received")
    val finalDamage = baseDamage * (1.0 + dealtMod + receivedMod)
    return Math.round(finalDamage).toInt().coerceAtLeast(0)
}

/** Soma value/percentual de modify_attribute ativos para um atributo específico. Percentual aplicado sobre o valor base do atributo. */
fun List<StatusEffect>.attributeModifier(attribute: String, baseValue: Int): Int =
    activeAutoEffectsOfType("modify_attribute")
        .filter { it.attribute == attribute }
        .sumOf { eff ->
            val flat = eff.value ?: 0
            val pct = eff.percentual?.let { (baseValue * it) / 100 } ?: 0
            flat + pct
        }
