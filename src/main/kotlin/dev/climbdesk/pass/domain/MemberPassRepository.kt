package dev.climbdesk.pass.domain

interface MemberPassRepository {
    fun save(memberPass: MemberPass): MemberPass
}
