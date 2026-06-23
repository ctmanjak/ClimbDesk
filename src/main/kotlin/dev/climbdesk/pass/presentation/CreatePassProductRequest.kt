package dev.climbdesk.pass.presentation

import dev.climbdesk.pass.application.CreatePassProductCommand
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class CreatePassProductRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,

    @field:Min(1)
    val totalCount: Int,

    @field:PositiveOrZero
    @field:Digits(integer = 12, fraction = 0)
    val price: BigDecimal?,

    @field:Min(1)
    val validDays: Int?,
) {
    fun toCommand(): CreatePassProductCommand =
        CreatePassProductCommand(
            name = name,
            totalCount = totalCount,
            price = price,
            validDays = validDays,
        )
}
