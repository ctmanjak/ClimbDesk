package dev.climbdesk.pass.presentation

import dev.climbdesk.pass.application.MAX_PASS_PRODUCT_PAGE_SIZE
import dev.climbdesk.pass.application.PassProductApplicationService
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
@RequestMapping("/api/v1/pass-products")
@Validated
class PassProductController(
    private val passProductApplicationService: PassProductApplicationService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun createPassProduct(
        @Valid @RequestBody request: CreatePassProductRequest,
    ): PassProductResponse =
        passProductApplicationService.createPassProduct(request.toCommand()).toResponse()

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun listPassProducts(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PASS_PRODUCT_PAGE_SIZE.toLong()) size: Int,
    ): PassProductListResponse =
        passProductApplicationService.listPassProducts(page, size).toResponse()

    @GetMapping("/{passProductId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'STAFF')")
    fun getPassProduct(
        @PathVariable passProductId: Long,
    ): PassProductResponse =
        passProductApplicationService.getPassProduct(passProductId).toResponse()
}
