package com.github.jvsena42.echo.domain.model

enum class SrsGrade { Again, Hard, Good, Easy }

data class SrsState(
    val cardId: String,
    val dueAt: Long,
    val intervalDays: Int,
    val easeFactor: Double,
    val repetitions: Int,
    val lastGrade: SrsGrade?,
)
