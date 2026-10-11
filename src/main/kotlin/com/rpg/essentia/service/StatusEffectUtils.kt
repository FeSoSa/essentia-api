package com.rpg.essentia.service

import com.rpg.essentia.model.StatusEffect

fun List<StatusEffect>.applyOrStack(new: StatusEffect): List<StatusEffect> {
    val existing = find { it.name == new.name }
    if (existing == null) return this + new
    if (existing.durationTurns == -1 || new.durationTurns == -1) {
        return map { if (it.name == new.name) it.copy(durationTurns = -1) else it }
    }
    return map { if (it.name == new.name) it.copy(durationTurns = it.durationTurns + new.durationTurns) else it }
}

fun List<StatusEffect>.applyOrStack(new: List<StatusEffect>): List<StatusEffect> =
    new.fold(this) { acc, e -> acc.applyOrStack(e) }
