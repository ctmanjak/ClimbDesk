package dev.climbdesk.pass.infrastructure.persistence

import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.pass.domain.PassProductType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "member_passes")
class MemberPassJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "pass_product_id", nullable = false)
    val passProductId: Long,

    @Column(name = "product_name_snapshot", nullable = false, length = 100)
    val productNameSnapshot: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "pass_type_snapshot", nullable = false, length = 30)
    val passTypeSnapshot: PassProductType,

    @Column(name = "total_count", nullable = false)
    val totalCount: Int,

    @Column(name = "remaining_count", nullable = false)
    val remainingCount: Int,

    @Column(name = "price_snapshot", precision = 12, scale = 0)
    val priceSnapshot: BigDecimal?,

    @Column(name = "valid_days_snapshot")
    val validDaysSnapshot: Int?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: MemberPassStatus,

    @Column(name = "issued_at", nullable = false)
    val issuedAt: Instant,

    @Column(name = "expires_at")
    val expiresAt: Instant?,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,
) {
    @PrePersist
    fun prePersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
