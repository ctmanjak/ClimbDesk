package dev.climbdesk.common.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ErrorCodeStatusMapperTest {
    @Test
    fun `member inactive maps to conflict`() {
        assertThat(ErrorCodeStatusMapper.statusOf(ErrorCode.MEMBER_INACTIVE))
            .isEqualTo(HttpStatus.CONFLICT)
    }
}
