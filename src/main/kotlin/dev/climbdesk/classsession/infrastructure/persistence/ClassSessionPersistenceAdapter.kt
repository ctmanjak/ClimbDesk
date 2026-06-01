package dev.climbdesk.classsession.infrastructure.persistence

import dev.climbdesk.classsession.domain.ClassSession
import dev.climbdesk.classsession.domain.ClassSessionRepository
import org.springframework.stereotype.Repository

@Repository
class ClassSessionPersistenceAdapter(
    private val classSessionJpaRepository: ClassSessionJpaRepository,
) : ClassSessionRepository {
    override fun save(classSession: ClassSession): ClassSession =
        classSessionJpaRepository.save(classSession.toJpaEntity()).toDomain()
}
