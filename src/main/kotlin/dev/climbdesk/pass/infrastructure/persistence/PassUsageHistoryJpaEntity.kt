package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.pass.domain.PassUsageHistoryReason
import dev.climbdesk.pass.domain.PassUsageHistoryType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "pass_usage_histories")
class PassUsageHistoryJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "member_pass_id", nullable = false)
    val memberPassId: Long,

    @Column(name = "reservation_id", nullable = false)
    val reservationId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: PassUsageHistoryType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val reason: PassUsageHistoryReason,

    @Column(name = "changed_count", nullable = false)
    val changedCount: Int,

    @Column(name = "remaining_count_after", nullable = false)
    val remainingCountAfter: Int,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,
) {
    @PrePersist
    fun prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now()
        }
    }
}
