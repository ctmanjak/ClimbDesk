package dev.climbdesk.classsession.infrastructure.persistence

import dev.climbdesk.classsession.domain.ClassSession
import dev.climbdesk.classsession.domain.ClassSessionPage
import dev.climbdesk.classsession.domain.ClassSessionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class ClassSessionPersistenceAdapter(
    private val classSessionJpaRepository: ClassSessionJpaRepository,
) : ClassSessionRepository {
    override fun findById(classSessionId: Long): ClassSession? =
        classSessionJpaRepository.findById(classSessionId)
            .map(ClassSessionJpaEntity::toDomain)
            .orElse(null)

    override fun findPage(page: Int, size: Int): ClassSessionPage {
        val classSessionPage =
            classSessionJpaRepository.findAllByOrderByStartsAtDescIdDesc(PageRequest.of(page, size))
        return ClassSessionPage(
            items = classSessionPage.content.map(ClassSessionJpaEntity::toDomain),
            page = classSessionPage.number,
            size = classSessionPage.size,
            totalElements = classSessionPage.totalElements,
        )
    }

    override fun save(classSession: ClassSession): ClassSession =
        classSessionJpaRepository.save(classSession.toJpaEntity()).toDomain()
}
