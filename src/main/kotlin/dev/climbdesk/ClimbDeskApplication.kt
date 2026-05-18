package dev.climbdesk

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ClimbDeskApplication

fun main() {
    runApplication<ClimbDeskApplication>()
}
