package com.ricezhou.vsrqg.evidence

import com.ricezhou.vsrqg.evidence.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import java.nio.file.Files

class EvidenceDownloadIntegrationTest:EvidenceFixture() {
    private fun token(sensitive:Boolean=false)=jwt().jwt { it.issuer("https://idp.vsrqg.test").subject(user.subject).claim("principal_type","USER") }
        .authorities(*listOfNotNull(SimpleGrantedAuthority("SCOPE_evidence:read"),if(sensitive) SimpleGrantedAuthority("SCOPE_evidence:read:sensitive") else null).toTypedArray())
    @Test fun `fixed Evidence metadata GET checks project permission and persists integrity observations`() {
        start(); val (session,metadata)=available(); val id=metadata.path("evidenceId").asText()
        val path="/api/v1/evidence/$id"
        fun observations()=jdbc.sql("SELECT count(*) FROM evidence_integrity_observation WHERE evidence_id=:id")
            .param("id",id).query(Int::class.java).single()
        val before=observations()
        val verified=mvc.perform(get(path).with(token())).andReturn().response
        assertThat(verified.status).describedAs(verified.contentAsString).isEqualTo(200)
        val verifiedBody=mapper.readTree(verified.contentAsString)
        assertThat(verifiedBody.path("evidenceId").asText()).isEqualTo(id)
        assertThat(verifiedBody.path("integrity").asText()).isEqualTo("VERIFIED")
        assertThat(verifiedBody.has("url")).isFalse()
        assertThat(observations()).isEqualTo(before+1)
        assertThat(jdbc.sql("SELECT code FROM evidence_integrity_observation WHERE evidence_id=:id ORDER BY sequence_no DESC LIMIT 1")
            .param("id",id).query(String::class.java).single()).isEqualTo("VERIFIED")

        assertThat(mvc.perform(get(path).with(jwt().jwt { it.issuer("https://idp.vsrqg.test")
            .subject(user.subject).claim("principal_type","USER") })).andReturn().response.status).isEqualTo(403)
        jdbc.sql("DELETE FROM project_assignment WHERE principal_id=:id AND project_id=:project")
            .param("id",user.subject).param("project",project).update()
        assertThat(mvc.perform(get(path).with(token())).andReturn().response.status).isEqualTo(403)
        assertThat(observations()).isEqualTo(before+1)

        jdbc.sql("INSERT INTO project_assignment(project_id,principal_id,role,created_at) VALUES (:project,:id,'ENGINEER',now())")
            .param("project",project).param("id",user.subject).update()
        Files.write(storage.resolve("${session.path("uploadId").asText()}.payload"),"wrong".toByteArray())
        val corrupted=mvc.perform(get(path).with(token())).andReturn().response
        assertThat(corrupted.status).describedAs(corrupted.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(corrupted.contentAsString).path("integrity").asText()).isEqualTo("INTEGRITY_ERROR")
        assertThat(observations()).isEqualTo(before+2)
        assertThat(jdbc.sql("SELECT code FROM evidence_integrity_observation WHERE evidence_id=:id ORDER BY sequence_no DESC LIMIT 1")
            .param("id",id).query(String::class.java).single()).isEqualTo("INTEGRITY_ERROR")
    }
    @Test fun `download is owner bound authenticated no store and rejects Range expiry and stale project permission`() {
        start(); val (_,metadata)=available(); val id=metadata.path("evidenceId").asText()
        val grant=downloads.request(user,id,"diagnose","download","request",false)
        val url=grant.path("url").asText()
        assertThat(downloads.request(user,id,"diagnose","download","again",false)).isEqualTo(grant)
        val response=mvc.perform(get(url).with(token())).andReturn().response
        assertThat(response.status).isEqualTo(200); assertThat(response.contentAsByteArray).isEqualTo("hello".toByteArray())
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(response.getHeader("Location")).isNull()
        assertThat(mvc.perform(get(url).with(token()).header("Range","bytes=0-1")).andReturn().response.status).isEqualTo(416)
        assertThat(mvc.perform(get(url)).andReturn().response.status).isEqualTo(401)
        now=now.plusSeconds(60)
        assertThatThrownBy { downloads.request(user,id,"diagnose","download","expired",false) }.hasMessage("DOWNLOAD_GRANT_EXPIRED_NEW_KEY_REQUIRED")
        assertThat(mvc.perform(get(url).with(token())).andReturn().response.status).isEqualTo(409)
        val renewed=downloads.request(user,id,"diagnose","new-key","new",false)
        jdbc.sql("DELETE FROM project_assignment WHERE principal_id=:a").param("a",user.subject).update()
        assertThat(mvc.perform(get(renewed.path("url").asText()).with(token())).andReturn().response.status).isEqualTo(403)
    }
    @Test fun `copying URL to another permitted project user fails owner check`() {
        start(); val (_,metadata)=available(); val id=metadata.path("evidenceId").asText()
        val grant=downloads.request(user,id,"diagnose","download","request",false)
        val other="other_"+user.subject.removePrefix("usr_")
        jdbc.sql("INSERT INTO principal(id,issuer,subject,principal_type,created_at) VALUES (:id,'https://idp.vsrqg.test',:id,'USER',now())").param("id",other).update()
        jdbc.sql("INSERT INTO project_assignment(project_id,principal_id,role,created_at) VALUES (:p,:id,'VIEWER',now())").param("p",project).param("id",other).update()
        val otherToken=jwt().jwt { it.issuer("https://idp.vsrqg.test").subject(other).claim("principal_type","USER") }.authorities(SimpleGrantedAuthority("SCOPE_evidence:read"))
        assertThat(mvc.perform(get(grant.path("url").asText()).with(otherToken)).andReturn().response.status).isEqualTo(403)
    }
    @Test fun `retention denies expired content unless legal hold preserves it`() {
        start(); val (_,metadata)=available(); val id=metadata.path("evidenceId").asText()
        jdbc.sql("UPDATE evidence_upload_session SET retention_until=:t WHERE evidence_id=:e").param("t",java.sql.Timestamp.from(now)).param("e",id).update()
        assertThatThrownBy { downloads.request(user,id,"diagnose","download","request",false) }.hasMessage("EVIDENCE_RETENTION_EXPIRED")
        jdbc.sql("UPDATE evidence_upload_session SET legal_hold=true WHERE evidence_id=:e").param("e",id).update()
        assertThat(downloads.request(user,id,"diagnose","held","request",false).path("url").asText()).contains("grantId=")
    }
}

@org.springframework.test.context.TestPropertySource(properties=["vsrqg.demo.evidence.sensitivity=HIGH"])
class EvidenceSensitiveIntegrationTest:EvidenceFixture() {
    @Test fun `HIGH preserves sensitive scope and project role before returning or using a grant`() {
        start(); val (_,metadata)=available();val id=metadata.path("evidenceId").asText()
        assertThat(metadata.path("sensitivity").asText()).isEqualTo("HIGH")
        assertThatThrownBy { downloads.request(user,id,"diagnose","reader","request",false) }.isInstanceOf(org.springframework.security.access.AccessDeniedException::class.java)
        assertThatThrownBy { downloads.request(user,id,"diagnose","scope-only","request",true) }.isInstanceOf(org.springframework.security.access.AccessDeniedException::class.java)
        jdbc.sql("UPDATE project_assignment SET role='QUALITY_OWNER' WHERE principal_id=:a AND project_id=:p").param("a",user.subject).param("p",project).update()
        val grant=downloads.request(user,id,"diagnose","owner","request",true)
        val grantId=grant.path("url").asText().substringAfter("grantId=")
        downloads.open(user,id,grantId,"read",true).input.use { assertThat(it.readNBytes(6)).isEqualTo("hello".toByteArray()) }
        jdbc.sql("UPDATE project_assignment SET role='VIEWER' WHERE principal_id=:a AND project_id=:p").param("a",user.subject).param("p",project).update()
        assertThatThrownBy { downloads.open(user,id,grantId,"revoked",true) }.isInstanceOf(org.springframework.security.access.AccessDeniedException::class.java)
    }
}
