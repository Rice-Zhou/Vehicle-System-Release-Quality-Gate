package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.testmanagement.adapter.JdbcAgentAccess
import com.ricezhou.vsrqg.testmanagement.application.AgentAccess
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.springframework.aop.framework.ProxyFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import javax.sql.DataSource

@Timeout(60)
class AgentIdentityTransactionTest {
    private val connection=mock(Connection::class.java).also { `when`(it.autoCommit).thenReturn(true) }
    private val dataSource=mock(DataSource::class.java).also { `when`(it.connection).thenReturn(connection) }
    private val manager=DataSourceTransactionManager(dataSource)
    private val jdbc=mock(JdbcClient::class.java)
    private val target=JdbcAgentAccess(jdbc,mock(ProjectAuthorizer::class.java))
    private val access=ProxyFactory(target).apply {
        addAdvice(TransactionInterceptor().also {
            it.transactionManager=manager
            it.transactionAttributeSource=AnnotationTransactionAttributeSource()
            it.afterPropertiesSet()
        })
    }.proxy as AgentAccess

    @Test fun `expected identity rejection can be handled without poisoning the outer transaction`() {
        var committed=false
        assertThatCode {
            TransactionTemplate(manager).executeWithoutResult {
                try { access.requireAgent("invalid-fingerprint","agent:execute") }
                catch(_:AccessDeniedException) { /* Worker records the explicit identity failure. */ }
                TransactionSynchronizationManager.registerSynchronization(object:TransactionSynchronization {
                    override fun afterCommit() { committed=true }
                })
            }
        }.doesNotThrowAnyException()
        assertThat(committed).isTrue()
    }

    @Test fun `uncaught identity rejection still rolls back the outer mutation transaction`() {
        var committed=false
        var completion:Int?=null
        assertThatThrownBy {
            TransactionTemplate(manager).executeWithoutResult {
                TransactionSynchronizationManager.registerSynchronization(object:TransactionSynchronization {
                    override fun afterCommit() { committed=true }
                    override fun afterCompletion(status:Int) { completion=status }
                })
                access.requireAgent("invalid-fingerprint","agent:execute")
            }
        }.isInstanceOf(AccessDeniedException::class.java)
        assertThat(committed).isFalse()
        assertThat(completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK)
    }

    @Test fun `database failures still poison a joined transaction even when accidentally caught`() {
        `when`(jdbc.sql(anyString())).thenThrow(DataAccessResourceFailureException("Database unavailable"))
        assertThatThrownBy {
            TransactionTemplate(manager).executeWithoutResult {
                try { access.requireAgent("a".repeat(64),"agent:execute") }
                catch(_:DataAccessResourceFailureException) { /* Demonstrate that database errors cannot commit. */ }
            }
        }.isInstanceOf(UnexpectedRollbackException::class.java)
    }
}
