package dev.climbdesk.classsession.domain

interface ClassSessionRepository {
    fun findById(classSessionId: Long): ClassSession?
    fun findPage(page: Int, size: Int): ClassSessionPage
    fun save(classSession: ClassSession): ClassSession
}

data class ClassSessionPage(
    val items: List<ClassSession>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
