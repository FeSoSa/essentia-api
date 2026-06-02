package com.rpg.essentia.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID

@Document(collection = "shops")
data class Shop(
    @Id val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val items: List<ShopItem> = emptyList(),
    val active: Boolean = false
)

data class ShopItem(
    val itemId: String = "",
    val stockLimit: Int? = null
)

data class ShopPurchaseRequest(
    val playerId: String = "",
    val itemId: String = "",
    val qty: Int = 1
)
