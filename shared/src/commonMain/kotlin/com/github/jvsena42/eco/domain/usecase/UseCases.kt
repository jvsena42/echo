package com.github.jvsena42.eco.domain.usecase

import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.ColumnMapping
import com.github.jvsena42.eco.domain.model.Deck
import com.github.jvsena42.eco.domain.model.ImportDraft
import com.github.jvsena42.eco.domain.model.PubkyIdentity
import com.github.jvsena42.eco.domain.model.Separator
import com.github.jvsena42.eco.domain.model.SrsGrade
import com.github.jvsena42.eco.domain.model.Tag

interface ParsePasteUseCase {
    operator fun invoke(
        rawText: String,
        separator: Separator = Separator.Auto,
        mapping: ColumnMapping? = null,
    ): Result<ImportDraft>
}

interface TriageCardsUseCase {
    suspend operator fun invoke(
        draft: ImportDraft,
        decisions: List<TriageDecision>,
    ): Result<List<Card>>
}

data class TriageDecision(
    val rowIndex: Int,
    val keep: Boolean,
    val editedFront: String? = null,
    val editedBack: String? = null,
    val editedTags: List<Tag>? = null,
)

interface PublishDeckUseCase {
    suspend operator fun invoke(deck: Deck, cards: List<Card>): Result<Deck>
}

interface ReviewCardUseCase {
    suspend operator fun invoke(cardId: String, grade: SrsGrade): Result<Unit>
}

interface SignInWithRingUseCase {
    suspend operator fun invoke(): Result<Unit>
}

interface SignOutUseCase {
    suspend operator fun invoke(): Result<Unit>
}

interface FollowUserUseCase {
    suspend operator fun invoke(target: PubkyIdentity): Result<Unit>
}

interface UnfollowUserUseCase {
    suspend operator fun invoke(target: PubkyIdentity): Result<Unit>
}
