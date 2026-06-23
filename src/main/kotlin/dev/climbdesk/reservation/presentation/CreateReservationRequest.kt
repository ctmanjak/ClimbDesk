package dev.climbdesk.reservation.presentation

import dev.climbdesk.reservation.application.CreateReservationCommand
import jakarta.validation.constraints.Positive

data class CreateReservationRequest(
    @field:Positive
    val memberId: Long,

    @field:Positive
    val classSessionId: Long,
) {
    fun toCommand(): CreateReservationCommand =
        CreateReservationCommand(
            memberId = memberId,
            classSessionId = classSessionId,
        )
}
