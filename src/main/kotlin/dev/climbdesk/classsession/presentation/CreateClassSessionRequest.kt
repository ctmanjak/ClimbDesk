package dev.climbdesk.classsession.presentation

import dev.climbdesk.classsession.application.CreateClassSessionCommand
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateClassSessionRequest(
    @field:NotBlank
    @field:Size(max = 150)
    val title: String,

    val startsAt: Instant,

    val endsAt: Instant,

    @field:Min(1)
    val capacity: Int,
) {
    fun toCommand(): CreateClassSessionCommand =
        CreateClassSessionCommand(
            title = title,
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = capacity,
        )
}
