package dev.climbdesk.reservation.presentation

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.common.error.GlobalExceptionHandler
import dev.climbdesk.reservation.application.CreateReservationCommand
import dev.climbdesk.reservation.application.ReservationApplicationService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@WebMvcTest(controllers = [ReservationController::class])
@AutoConfigureMockMvc(addFilters = false)
class ReservationFailureResponseControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var reservationApplicationService: ReservationApplicationService

    @Test
    fun `reservation creation member pass version conflict returns documented error response`() {
        doThrow(ApplicationException(ErrorCode.MEMBER_PASS_VERSION_CONFLICT))
            .`when`(reservationApplicationService).reserveClass(CreateReservationCommand(MEMBER_ID, CLASS_SESSION_ID))

        postReservation()
            .andExpect {
                expectReservationConflict(
                    path = "/api/v1/reservations",
                    code = ErrorCode.MEMBER_PASS_VERSION_CONFLICT,
                )
            }
    }

    @Test
    fun `reservation creation pessimistic lock failure returns concurrency conflict response`() {
        doThrow(PessimisticLockingFailureException("lock timeout"))
            .`when`(reservationApplicationService).reserveClass(CreateReservationCommand(MEMBER_ID, CLASS_SESSION_ID))

        postReservation()
            .andExpect {
                expectReservationConflict(
                    path = "/api/v1/reservations",
                    code = ErrorCode.CONCURRENCY_CONFLICT,
                )
            }
    }

    @Test
    fun `reservation cancellation member pass version conflict returns documented error response`() {
        doThrow(ApplicationException(ErrorCode.MEMBER_PASS_VERSION_CONFLICT))
            .`when`(reservationApplicationService).cancelReservation(RESERVATION_ID)

        cancelReservation()
            .andExpect {
                expectReservationConflict(
                    path = "/api/v1/reservations/$RESERVATION_ID/cancel",
                    code = ErrorCode.MEMBER_PASS_VERSION_CONFLICT,
                )
            }
    }

    @Test
    fun `reservation cancellation pessimistic lock failure returns concurrency conflict response`() {
        doThrow(PessimisticLockingFailureException("lock timeout"))
            .`when`(reservationApplicationService).cancelReservation(RESERVATION_ID)

        cancelReservation()
            .andExpect {
                expectReservationConflict(
                    path = "/api/v1/reservations/$RESERVATION_ID/cancel",
                    code = ErrorCode.CONCURRENCY_CONFLICT,
                )
            }
    }

    private fun postReservation() =
        mockMvc.post("/api/v1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            header(GlobalExceptionHandler.TRACE_ID_HEADER, TRACE_ID)
            content = """{"memberId":$MEMBER_ID,"classSessionId":$CLASS_SESSION_ID}"""
        }

    private fun cancelReservation() =
        mockMvc.patch("/api/v1/reservations/$RESERVATION_ID/cancel") {
            header(GlobalExceptionHandler.TRACE_ID_HEADER, TRACE_ID)
        }

    private fun org.springframework.test.web.servlet.MockMvcResultMatchersDsl.expectReservationConflict(
        path: String,
        code: ErrorCode,
    ) {
        status { isConflict() }
        jsonPath("$.timestamp") { exists() }
        jsonPath("$.status") { value(409) }
        jsonPath("$.code") { value(code.name) }
        jsonPath("$.message") { value(code.defaultMessage) }
        jsonPath("$.path") { value(path) }
        jsonPath("$.traceId") { value(TRACE_ID) }
        jsonPath("$.details") { doesNotExist() }
        jsonPath("$.stackTrace") { doesNotExist() }
    }

    private companion object {
        const val MEMBER_ID = 1L
        const val CLASS_SESSION_ID = 2L
        const val RESERVATION_ID = 3L
        const val TRACE_ID = "trace-reservation-conflict"
    }
}
