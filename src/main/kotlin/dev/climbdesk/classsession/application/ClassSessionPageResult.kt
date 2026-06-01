package dev.climbdesk.classsession.application

import dev.climbdesk.classsession.domain.ClassSessionPage

data class ClassSessionPageResult(
    val items: List<ClassSessionResult>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(classSessionPage: ClassSessionPage): ClassSessionPageResult {
            require(classSessionPage.size > 0) { "ClassSessionPage size must be greater than 0." }

            return ClassSessionPageResult(
                items = classSessionPage.items.map(ClassSessionResult::from),
                page = classSessionPage.page,
                size = classSessionPage.size,
                totalElements = classSessionPage.totalElements,
                totalPages = if (classSessionPage.totalElements == 0L) {
                    0
                } else {
                    ((classSessionPage.totalElements - 1) / classSessionPage.size + 1).toInt()
                },
            )
        }
    }
}
