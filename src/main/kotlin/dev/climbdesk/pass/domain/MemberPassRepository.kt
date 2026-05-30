package dev.climbdesk.pass.domain

import java.time.Instant

interface MemberPassRepository {
    fun existsById(memberPassId: Long): Boolean
    fun findPageByMemberId(memberId: Long, page: Int, size: Int): MemberPassPage
    fun findUsageHistoryPageByMemberPassId(memberPassId: Long, page: Int, size: Int): PassUsageHistoryPage
    fun findAvailablePassForUse(memberId: Long, now: Instant): MemberPass?
    fun save(memberPass: MemberPass): MemberPass
    fun saveUsageResult(usageResult: MemberPassUsageResult): MemberPassUsageResult
}

data class MemberPassPage(
    val items: List<MemberPass>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

data class PassUsageHistoryPage(
    val items: List<PassUsageHistory>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
