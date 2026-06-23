package dev.climbdesk.auth.application

import dev.climbdesk.auth.domain.AdminUser

interface AccessTokenIssuer {
    fun issue(adminUser: AdminUser): IssuedAccessToken
}
