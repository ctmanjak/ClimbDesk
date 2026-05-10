package dev.climbdesk.common.error

open class ClimbDeskException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.defaultMessage,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

open class DomainException(
    errorCode: ErrorCode,
    message: String = errorCode.defaultMessage,
    cause: Throwable? = null,
) : ClimbDeskException(errorCode, message, cause)

open class ApplicationException(
    errorCode: ErrorCode,
    message: String = errorCode.defaultMessage,
    cause: Throwable? = null,
) : ClimbDeskException(errorCode, message, cause)
