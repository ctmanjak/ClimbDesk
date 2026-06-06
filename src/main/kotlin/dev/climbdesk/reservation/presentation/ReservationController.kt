package dev.climbdesk.reservation.presentation

import dev.climbdesk.reservation.application.ReservationApplicationService
import dev.climbdesk.reservation.application.MAX_RESERVATION_PAGE_SIZE
import dev.climbdesk.reservation.domain.ReservationStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/reservations")
@Validated
class ReservationController(
    private val reservationApplicationService: ReservationApplicationService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun createReservation(
        @Valid @RequestBody request: CreateReservationRequest,
    ): ReservationResponse =
        reservationApplicationService.reserveClass(request.toCommand()).toResponse()

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun listReservations(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_RESERVATION_PAGE_SIZE.toLong()) size: Int,
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) classSessionId: Long?,
        @RequestParam(required = false) status: ReservationStatus?,
    ): ReservationListResponse =
        reservationApplicationService.listReservations(
            page = page,
            size = size,
            memberId = memberId,
            classSessionId = classSessionId,
            status = status,
        ).toResponse()

    @GetMapping("/{reservationId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun getReservation(
        @PathVariable reservationId: Long,
    ): ReservationResponse =
        reservationApplicationService.getReservation(reservationId).toResponse()
}
