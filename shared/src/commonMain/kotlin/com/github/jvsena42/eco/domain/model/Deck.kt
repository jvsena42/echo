package com.github.jvsena42.eco.domain.model

data class Deck(
    val id: String,
    val pubkyUri: PubkyUri?,
    val author: PubkyIdentity,
    val title: String,
    val description: String?,
    val coverImagePath: String?,
    val tags: List<Tag>,
    val cardCount: Int,
    val createdAt: Long,
    val lastStudiedAt: Long?,
)

@JvmInline
value class PubkyUri(val value: String)
