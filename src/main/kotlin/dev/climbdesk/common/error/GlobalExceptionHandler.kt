package dev.climbdesk.common.error

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.MDC
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ClimbDeskException::class)
    fun handleClimbDeskException(
        exception: ClimbDeskException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val status = ErrorCodeStatusMapper.statusOf(exception.errorCode)
        return errorResponse(
            status = status,
            errorCode = exception.errorCode,
            message = exception.message,
            request = request,
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val details = exception.bindingResult.fieldErrors
            .map { it.toValidationErrorDetail() }
            .sortedWith(compareBy<ValidationErrorDetail> { it.field }.thenBy { it.reason })

        return errorResponse(
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCode.VALIDATION_FAILED,
            message = ErrorCode.VALIDATION_FAILED.defaultMessage,
            request = request,
            details = details,
        )
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val details = exception.constraintViolations
            .map {
                ValidationErrorDetail(
                    field = it.propertyPath.toString().substringAfterLast('.'),
                    reason = it.message,
                )
            }
            .sortedWith(compareBy<ValidationErrorDetail> { it.field }.thenBy { it.reason })

        return errorResponse(
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCode.VALIDATION_FAILED,
            message = ErrorCode.VALIDATION_FAILED.defaultMessage,
            request = request,
            details = details,
        )
    }

    @ExceptionHandler(
        MissingServletRequestParameterException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleBadRequest(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        errorResponse(
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCode.VALIDATION_FAILED,
            message = ErrorCode.VALIDATION_FAILED.defaultMessage,
            request = request,
        )

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(
        exception: AccessDeniedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        errorResponse(
            status = HttpStatus.FORBIDDEN,
            errorCode = ErrorCode.FORBIDDEN,
            message = ErrorCode.FORBIDDEN.defaultMessage,
            request = request,
        )

    @ExceptionHandler(PessimisticLockingFailureException::class)
    fun handlePessimisticLockingFailure(
        exception: PessimisticLockingFailureException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        errorResponse(
            status = HttpStatus.CONFLICT,
            errorCode = ErrorCode.CONCURRENCY_CONFLICT,
            message = ErrorCode.CONCURRENCY_CONFLICT.defaultMessage,
            request = request,
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        errorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            errorCode = ErrorCode.INTERNAL_SERVER_ERROR,
            message = ErrorCode.INTERNAL_SERVER_ERROR.defaultMessage,
            request = request,
        )

    private fun errorResponse(
        status: HttpStatus,
        errorCode: ErrorCode,
        message: String,
        request: HttpServletRequest,
        details: List<ValidationErrorDetail>? = null,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(
            ErrorResponse(
                timestamp = OffsetDateTime.now(ZoneOffset.UTC),
                status = status.value(),
                code = errorCode.name,
                message = message,
                path = request.requestURI,
                traceId = traceId(request),
                details = details?.takeIf { it.isNotEmpty() },
            ),
        )

    private fun traceId(request: HttpServletRequest): String =
        request.getHeader(TRACE_ID_HEADER)
            ?: MDC.get(TRACE_ID_MDC_KEY)
            ?: UUID.randomUUID().toString()

    private fun FieldError.toValidationErrorDetail(): ValidationErrorDetail =
        ValidationErrorDetail(
            field = field,
            reason = defaultMessage ?: code ?: "invalid",
        )

    companion object {
        const val TRACE_ID_HEADER = "X-Trace-Id"
        private const val TRACE_ID_MDC_KEY = "traceId"
    }
}
