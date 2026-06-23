package dev.climbdesk.common.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpStatus

class ErrorCodeStatusMapperTest {
    @Test
    fun `member inactive maps to conflict`() {
        assertThat(ErrorCodeStatusMapper.statusOf(ErrorCode.MEMBER_INACTIVE))
            .isEqualTo(HttpStatus.CONFLICT)
    }

    @ParameterizedTest
    @EnumSource(
        value = ErrorCode::class,
        names = ["CLASS_SESSION_NOT_OPEN", "CLASS_SESSION_FULL", "CLASS_SESSION_ALREADY_CANCELED"],
    )
    fun `class session conflicts map to conflict`(errorCode: ErrorCode) {
        assertThat(ErrorCodeStatusMapper.statusOf(errorCode))
            .isEqualTo(HttpStatus.CONFLICT)
    }

    @ParameterizedTest
    @EnumSource(
        value = ErrorCode::class,
        names = ["DUPLICATE_RESERVATION", "RESERVATION_ALREADY_CANCELED", "MEMBER_PASS_VERSION_CONFLICT"],
    )
    fun `reservation concurrency conflicts map to conflict`(errorCode: ErrorCode) {
        assertThat(ErrorCodeStatusMapper.statusOf(errorCode))
            .isEqualTo(HttpStatus.CONFLICT)
    }
}
