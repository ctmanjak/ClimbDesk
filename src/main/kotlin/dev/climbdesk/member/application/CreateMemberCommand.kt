package dev.climbdesk.member.application

data class CreateMemberCommand(
    val name: String,
    val phone: String,
    val email: String?,
)
