package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.shared.adapter.archive.DeploymentProperties
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("vsrqg.jira.pilot")
data class JiraCliPilotProperties(
    val enabled: Boolean = false,
    val cliPath: String = "",
    val project: String = "",
    val maxIssues: Int = MAX_ISSUES,
    val timeout: Duration = Duration.ofSeconds(15),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JiraCliPilotProperties::class, DeploymentProperties::class)
class JiraCliPilotConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "vsrqg.jira.pilot", name = ["enabled"], havingValue = "true")
    fun jiraProcessRunner(): JiraProcessRunner = DefaultJiraProcessRunner()

    @Bean
    @ConditionalOnProperty(prefix = "vsrqg.jira.pilot", name = ["enabled"], havingValue = "true")
    fun jiraCliPilotRuntimeFactory(
        properties: JiraCliPilotProperties,
        deployment: DeploymentProperties,
        processRunner: JiraProcessRunner,
    ): JiraCliPilotRuntimeFactory {
        validateJiraConfiguration(properties, deployment.mode)
        return JiraCliPilotRuntimeFactory(properties, processRunner)
    }
}

internal fun validateJiraConfiguration(properties: JiraCliPilotProperties, deploymentMode: DeploymentMode) {
    if (!properties.enabled) return
    val path = runCatching { Path.of(properties.cliPath) }.getOrNull()
    val valid = deploymentMode == DeploymentMode.PILOT &&
        path != null &&
        path.isAbsolute &&
        Files.isRegularFile(path) &&
        PROJECT_KEY.matches(properties.project) &&
        properties.maxIssues in 1..MAX_ISSUES &&
        properties.timeout > Duration.ZERO &&
        properties.timeout <= MAX_TIMEOUT
    if (!valid) throw IllegalArgumentException(CONFIGURATION_ERROR)
}

internal val PROJECT_KEY = Regex("^[A-Z][A-Z0-9_]{1,19}$")
internal val MAX_TIMEOUT: Duration = Duration.ofSeconds(60)
private const val CONFIGURATION_ERROR = "JIRA_PILOT_CONFIGURATION_INVALID"
