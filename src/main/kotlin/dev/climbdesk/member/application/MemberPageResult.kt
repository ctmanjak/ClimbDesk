package dev.climbdesk.member.application

import dev.climbdesk.member.domain.MemberPage

data class MemberPageResult(
    val items: List<MemberResult>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(memberPage: MemberPage): MemberPageResult =
            MemberPageResult(
                items = memberPage.items.map(MemberResult::from),
                page = memberPage.page,
                size = memberPage.size,
                totalElements = memberPage.totalElements,
                totalPages = if (memberPage.totalElements == 0L) {
                    0
                } else {
                    ((memberPage.totalElements - 1) / memberPage.size + 1).toInt()
                },
            )
    }
}
