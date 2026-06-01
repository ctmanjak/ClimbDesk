package dev.climbdesk.classsession.application

import java.time.Instant

data class CreateClassSessionCommand(
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
)
