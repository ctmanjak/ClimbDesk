package dev.climbdesk.member.domain

interface MemberRepository {
    fun existsByPhone(phone: String): Boolean
    fun findById(memberId: Long): Member?
    fun findPage(page: Int, size: Int): MemberPage
    fun save(member: Member): Member
}

data class MemberPage(
    val items: List<Member>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
