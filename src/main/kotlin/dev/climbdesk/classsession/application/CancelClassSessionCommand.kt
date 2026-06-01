package dev.climbdesk.classsession.application

data class CancelClassSessionCommand(
    val classSessionId: Long,
    val reason: String,
)
