package dev.climbdesk.classsession.presentation

import dev.climbdesk.classsession.application.CancelClassSessionCommand
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CancelClassSessionRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
) {
    fun toCommand(classSessionId: Long): CancelClassSessionCommand =
        CancelClassSessionCommand(
            classSessionId = classSessionId,
            reason = reason,
        )
}
