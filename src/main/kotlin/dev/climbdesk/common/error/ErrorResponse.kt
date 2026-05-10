package dev.climbdesk.common.error

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.OffsetDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val timestamp: OffsetDateTime,
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val traceId: String,
    val details: List<ValidationErrorDetail>? = null,
)

data class ValidationErrorDetail(
    val field: String,
    val reason: String,
)
