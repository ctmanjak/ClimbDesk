package dev.climbdesk.member.presentation

import dev.climbdesk.member.application.CreateMemberCommand
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateMemberRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,

    @field:NotBlank
    @field:Size(max = 30)
    val phone: String,

    @field:Email
    @field:Size(max = 255)
    val email: String?,
) {
    fun toCommand(): CreateMemberCommand =
        CreateMemberCommand(
            name = name,
            phone = phone,
            email = email,
        )
}
