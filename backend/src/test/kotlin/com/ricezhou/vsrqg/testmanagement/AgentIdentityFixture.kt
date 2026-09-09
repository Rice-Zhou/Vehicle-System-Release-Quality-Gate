package com.ricezhou.vsrqg.testmanagement

import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.HexFormat

class AgentIdentityFixture(private val jdbc: JdbcClient, certificate: X509Certificate) {
    private val fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
    private val suffix = fingerprint.take(12)
    val projectId = "ap_$suffix"
    val principalId = "as_$suffix"
    val deviceId = "ad_$suffix"
    val agentId = "aa_$suffix"

    init {
        jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :id, 'Synthetic Agent test', now()) ON CONFLICT (id) DO UPDATE SET archived = false").param("id", projectId).update()
        jdbc.sql("INSERT INTO principal(id, issuer, subject, principal_type, created_at) VALUES (:id, 'urn:vsrqg:test-agent', :id, 'SERVICE', now()) ON CONFLICT (id) DO UPDATE SET disabled = false, principal_type = 'SERVICE'").param("id", principalId).update()
        jdbc.sql("INSERT INTO project_assignment(project_id, principal_id, role, created_at) VALUES (:project, :principal, 'ENGINEER', now()) ON CONFLICT (project_id, principal_id) DO UPDATE SET role = 'ENGINEER'").param("project", projectId).param("principal", principalId).update()
        jdbc.sql("INSERT INTO device(id, project_id, disabled, created_at) VALUES (:id, :project, false, now()) ON CONFLICT (id) DO UPDATE SET disabled = false").param("id", deviceId).param("project", projectId).update()
        jdbc.sql("INSERT INTO agent(id, principal_id, project_id, device_id, certificate_sha256, revoked, created_at) VALUES (:id, :principal, :project, :device, :fingerprint, false, now()) ON CONFLICT (id) DO UPDATE SET revoked = false").param("id", agentId).param("principal", principalId).param("project", projectId).param("device", deviceId).param("fingerprint", fingerprint).update()
    }

    fun body(deviceRef: String = deviceId) = """{"messageType":"AGENT_REGISTRATION","protocolVersion":"1.0","agentVersion":"0.2.0","supportedProtocolVersions":["1.0"],"deviceRef":"$deviceRef","capabilities":["ADB","APK_INSTALL","LOG","SCREENSHOT"],"collectorVersions":{"LOG":"1.0","SCREENSHOT":"1.0"}}"""
}
