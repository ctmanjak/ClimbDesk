package dev.climbdesk.pass.application

import java.math.BigDecimal

data class CreatePassProductCommand(
    val name: String,
    val totalCount: Int,
    val price: BigDecimal?,
    val validDays: Int?,
)
