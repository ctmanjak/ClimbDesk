package dev.climbdesk.pass.application

import dev.climbdesk.pass.domain.MemberPassPage

data class MemberPassPageResult(
    val items: List<MemberPassResult>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(memberPassPage: MemberPassPage): MemberPassPageResult =
            MemberPassPageResult(
                items = memberPassPage.items.map(MemberPassResult::from),
                page = memberPassPage.page,
                size = memberPassPage.size,
                totalElements = memberPassPage.totalElements,
                totalPages = if (memberPassPage.totalElements == 0L) {
                    0
                } else {
                    ((memberPassPage.totalElements - 1) / memberPassPage.size + 1).toInt()
                },
            )
    }
}
