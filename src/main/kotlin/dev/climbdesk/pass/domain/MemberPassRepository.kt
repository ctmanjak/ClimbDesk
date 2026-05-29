package dev.climbdesk.pass.domain

import java.time.Instant

interface MemberPassRepository {
    fun findPageByMemberId(memberId: Long, page: Int, size: Int): MemberPassPage
    fun findAvailablePassForUse(memberId: Long, now: Instant): MemberPass?
    fun save(memberPass: MemberPass): MemberPass
}

data class MemberPassPage(
    val items: List<MemberPass>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
