package dev.climbdesk.auth.domain

data class AdminUser(
    val id: Long,
    val email: String,
    val passwordHash: String,
    val role: AdminUserRole,
    val status: AdminUserStatus,
) {
    fun isActive(): Boolean = status == AdminUserStatus.ACTIVE
}
