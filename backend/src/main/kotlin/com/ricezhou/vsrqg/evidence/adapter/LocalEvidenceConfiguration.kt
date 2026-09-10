package com.ricezhou.vsrqg.evidence.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.ricezhou.vsrqg.evidence.application.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path

@Configuration
@ConditionalOnProperty(name=["vsrqg.demo.evidence.enabled"],havingValue="true")
class LocalEvidenceConfiguration {
    @Bean fun evidenceConnectorLimits()=org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory> { factory->
        factory.addConnectorCustomizers({ connector->
            connector.setProperty("connectionTimeout","30000")
            connector.setProperty("disableUploadTimeout","false")
            connector.setProperty("connectionUploadTimeout","30000")
            connector.setProperty("maxSwallowSize","0")
        })
    }
    @Bean fun localPayloadStore(@Value("\${vsrqg.demo.evidence.root}") location:String):PayloadStore {
        val root=Path.of(location)
        require(root.isAbsolute) { "PAYLOAD_ROOT_MUST_BE_ABSOLUTE" }
        val normalized=root.normalize()
        var parent:Path?=normalized
        while(parent!=null) {
            require(!Files.exists(parent.resolve(".git")) && !Files.exists(parent.resolve(".openai/hosting.json"))) { "PAYLOAD_ROOT_INSIDE_REPOSITORY" }
            require(parent.fileName?.toString() !in setOf("static","public","wwwroot")) { "PAYLOAD_ROOT_PUBLIC" }
            parent=parent.parent
        }
        require(Files.isDirectory(normalized)) { "PAYLOAD_ROOT_MUST_EXIST" }
        return ControlledPayloadStore(normalized)
    }
    @Bean fun evidenceInputValidator(mapper:ObjectMapper):EvidenceInputValidator {
        val registry=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val protocol=requireNotNull(javaClass.getResourceAsStream("/contracts/agent-protocol.schema.json")).use(mapper::readTree)
        val schemas=listOf("createUploadRequest","completeUploadRequest").map { name->
            val node=protocol.path("\$defs").path(name).deepCopy<ObjectNode>(); node.set<JsonNode>("\$defs",protocol.path("\$defs"))
            registry.getSchema(node.toString())
        }
        return object:EvidenceInputValidator {
            override fun validate(body:JsonNode,complete:Boolean) {
                if(schemas[if(complete) 1 else 0].validate(body.toString(),InputFormat.JSON).isNotEmpty()) throw EvidenceConflict("INVALID_REQUEST")
            }
        }
    }
}
