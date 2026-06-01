package dev.climbdesk.classsession.application

import dev.climbdesk.classsession.domain.ClassSession
import dev.climbdesk.classsession.domain.ClassSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ClassSessionApplicationService(
    private val classSessionRepository: ClassSessionRepository,
) {
    @Transactional
    fun createClassSession(command: CreateClassSessionCommand): ClassSessionResult {
        val classSession = ClassSession.create(
            title = command.title,
            startsAt = command.startsAt,
            endsAt = command.endsAt,
            capacity = command.capacity,
        )

        return ClassSessionResult.from(classSessionRepository.save(classSession))
    }
}
