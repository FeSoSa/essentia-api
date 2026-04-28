package com.rpg.essentia.model

data class Dice(
    val quantity: Int = 1,
    val die: String = "d6"  // "d4" | "d6" | "d8" | "d10" | "d12" | "d20"
)
