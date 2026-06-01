package dev.climbdesk.classsession.application

import dev.climbdesk.classsession.domain.ClassSession
import dev.climbdesk.classsession.domain.ClassSessionRepository
import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
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

    @Transactional(readOnly = true)
    fun listClassSessions(page: Int, size: Int): ClassSessionPageResult {
        if (page < 0) {
            throw ApplicationException(ErrorCode.VALIDATION_FAILED, "page must be greater than or equal to 0.")
        }
        if (size !in 1..MAX_CLASS_SESSION_PAGE_SIZE) {
            throw ApplicationException(
                ErrorCode.VALIDATION_FAILED,
                "size must be between 1 and $MAX_CLASS_SESSION_PAGE_SIZE.",
            )
        }

        return ClassSessionPageResult.from(classSessionRepository.findPage(page, size))
    }

    @Transactional(readOnly = true)
    fun getClassSession(classSessionId: Long): ClassSessionResult =
        classSessionRepository.findById(classSessionId)
            ?.let(ClassSessionResult::from)
            ?: throw ApplicationException(ErrorCode.CLASS_SESSION_NOT_FOUND)

    @Transactional
    fun cancelClassSession(command: CancelClassSessionCommand): ClassSessionResult {
        val classSession = classSessionRepository.findByIdForUpdate(command.classSessionId)
            ?: throw ApplicationException(ErrorCode.CLASS_SESSION_NOT_FOUND)

        return ClassSessionResult.from(
            classSessionRepository.save(classSession.cancel(command.reason)),
        )
    }
}
