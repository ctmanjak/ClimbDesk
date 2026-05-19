package dev.climbdesk.member.domain

interface MemberRepository {
    fun existsByPhone(phone: String): Boolean
    fun save(member: Member): Member
}
