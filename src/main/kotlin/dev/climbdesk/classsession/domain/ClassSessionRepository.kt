package dev.climbdesk.classsession.domain

interface ClassSessionRepository {
    fun save(classSession: ClassSession): ClassSession
}
