package com.ricezhou.vsrqg.quality.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.quality.application.QualityRepository
import com.ricezhou.vsrqg.quality.application.QualityRuleSetRecord
import com.ricezhou.vsrqg.quality.application.QualityRuleVersionRecord
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcQualityRepository(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
) : QualityRepository {
    override fun insert(set: QualityRuleSetRecord, rules: List<QualityRuleVersionRecord>) {
        jdbc.sql(
            """
            INSERT INTO quality_rule_set_versions (
              id, project_id, rule_set_id, rule_set_version, row_version, state,
              definition, catalog_version, engine_version, required_issue_refs,
              selected_case_refs, content_digest, author_id, created_at
            ) VALUES (
              :id, :project, :ruleSetId, :version, :rowVersion, :state,
              CAST(:definition AS jsonb), :catalogVersion, :engineVersion,
              CAST(:issues AS jsonb), CAST(:cases AS jsonb), :digest, :author, :createdAt
            ) ON CONFLICT (rule_set_id) WHERE state = 'DRAFT' DO NOTHING
            """.trimIndent(),
        ).param("id", set.id).param("project", set.projectId).param("ruleSetId", set.ruleSetId)
            .param("version", set.version).param("rowVersion", set.rowVersion).param("state", set.state)
            .param("definition", set.definition.toString())
            .param("catalogVersion", set.definition["catalogVersion"].intValue())
            .param("engineVersion", set.definition["engineVersion"].textValue())
            .param("issues", set.definition["requiredIssueRefs"].toString())
            .param("cases", set.definition["selectedCaseRefs"].toString())
            .param("digest", set.contentDigest).param("author", set.authorId)
            .param("createdAt", set.createdAt.toJdbcTimestamp()).update()
            .also { affected ->
                if (affected != 1) {
                    throw ResourceConflict("RULE_SET_VERSION_CONFLICT", "Rule Set conflict", "Rule Set version already exists")
                }
            }
        rules.forEach { rule ->
            jdbc.sql(
                """
                INSERT INTO quality_rule_versions (
                  id, rule_set_version_id, ordinal, rule_id, rule_version, source_yaml,
                  validated_ast, source_path, source_commit, source_digest, golden_fixture, golden_digest
                ) VALUES (
                  :id, :setId, :ordinal, :ruleId, :version, :yaml,
                  CAST(:ast AS jsonb), :path, :commit, :digest, :goldenFixture, :goldenDigest
                )
                """.trimIndent(),
            ).param("id", rule.id).param("setId", rule.setVersionId).param("ordinal", rule.ordinal)
                .param("ruleId", rule.ruleId).param("version", rule.version).param("yaml", rule.sourceYaml)
                .param("ast", rule.validatedAst.toString()).param("path", rule.sourcePath)
                .param("commit", rule.sourceCommit).param("digest", rule.sourceDigest)
                .param("goldenFixture", rule.goldenFixture).param("goldenDigest", rule.goldenDigest)
                .update()
        }
    }

    override fun findByRuleSetId(ruleSetId: String): QualityRuleSetRecord? = find(ruleSetId, false)
    override fun lockByRuleSetId(ruleSetId: String): QualityRuleSetRecord? = find(ruleSetId, true)

    private fun find(id: String, lock: Boolean): QualityRuleSetRecord? {
        val suffix = if (lock) " FOR UPDATE" else ""
        return jdbc.sql(
            """
            SELECT id, project_id, rule_set_id, rule_set_version, row_version, state,
                   definition::text AS definition, content_digest, author_id, reviewer_id, created_at
            FROM quality_rule_set_versions WHERE rule_set_id = :id
            ORDER BY rule_set_version DESC LIMIT 1$suffix
            """.trimIndent(),
        ).param("id", id).query(::mapSet).optional().orElse(null)
    }

    override fun rules(setVersionId: String): List<QualityRuleVersionRecord> = jdbc.sql(
        """
        SELECT id, rule_set_version_id, ordinal, rule_id, rule_version, source_yaml,
               validated_ast::text AS validated_ast, source_path, source_commit,
               source_digest, golden_fixture, golden_digest
        FROM quality_rule_versions WHERE rule_set_version_id = :id ORDER BY ordinal
        """.trimIndent(),
    ).param("id", setVersionId).query { rs, _ ->
        QualityRuleVersionRecord(
            rs.getString("id"), rs.getString("rule_set_version_id"), rs.getInt("ordinal"),
            rs.getString("rule_id"), rs.getLong("rule_version"), rs.getString("source_yaml"),
            mapper.readTree(rs.getString("validated_ast")), rs.getString("source_path"),
            rs.getString("source_commit"), rs.getString("source_digest"),
            rs.getString("golden_fixture"), rs.getString("golden_digest"),
        )
    }.list()

    override fun publish(setVersionId: String, expectedVersion: Long, reviewerId: String,
                         reason: String, at: Instant): Boolean = jdbc.sql(
        """
        UPDATE quality_rule_set_versions
        SET state='PUBLISHED', row_version=row_version+1, reviewer_id=:reviewer,
            review_reason=:reason, published_at=:publishedAt
        WHERE id=:id AND state='DRAFT' AND row_version=:expectedVersion
        """.trimIndent(),
    ).param("reviewer", reviewerId).param("reason", reason).param("publishedAt", at.toJdbcTimestamp())
        .param("id", setVersionId).param("expectedVersion", expectedVersion).update() == 1

    private fun mapSet(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = QualityRuleSetRecord(
        rs.getString("id"), rs.getString("project_id"), rs.getString("rule_set_id"),
        rs.getLong("rule_set_version"), rs.getLong("row_version"), rs.getString("state"),
        mapper.readTree(rs.getString("definition")), rs.getString("content_digest"),
        rs.getString("author_id"), rs.getString("reviewer_id"), rs.getTimestamp("created_at").toInstant(),
    )
}
