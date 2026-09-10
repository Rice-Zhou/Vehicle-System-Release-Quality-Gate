package com.ricezhou.vsrqg.agent
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
object SmokeAssertions {
    const val PACKAGE="com.ricezhou.vsrqg.smoke"
    const val COMPONENT="$PACKAGE/.SmokeActivity"
    const val XML_LIMIT=1048576L
    fun attempt(value:String):String { ensure(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value),"ATTEMPT_INVALID");return value }
    fun mode(value:String):String { ensure(value in setOf("normal","assertion-failure"),"MODE_INVALID");return value }
    fun remoteXml(id:String)="/data/local/tmp/vsrqg-smoke-${attempt(id)}.xml"
    fun remotePng(id:String)="/data/local/tmp/vsrqg-smoke-${attempt(id)}.png"
    fun foreground(output:String):Boolean = output.lineSequence().any { line ->
        Regex("\\s*(?:mResumedActivity: |topResumedActivity(?:: |=)|ResumedActivity: )ActivityRecord\\{[^\\s{}]+ u[0-9]+ ${Regex.escape(COMPONENT)} t[0-9]+(?:\\s+[^{}]*)?}\\s*").matches(line)
    }
    fun launch(output:String):Boolean = output.lineSequence().any {it.trim()=="Status: ok"} &&
        output.lineSequence().any {it.trim()=="Activity: $COMPONENT" || it.trim()=="Activity: $PACKAGE/$PACKAGE.SmokeActivity"} &&
        !output.contains("Error:") && !output.contains("Exception")
    fun ready(xml:ByteArray,attemptId:String):Boolean {
        attempt(attemptId);ensure(xml.size<=XML_LIMIT && xml.isNotEmpty(),"UI_XML_LIMIT")
        val factory=DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            setFeature("http://xml.org/sax/features/external-general-entities",false)
            setFeature("http://xml.org/sax/features/external-parameter-entities",false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
            isXIncludeAware=false;isExpandEntityReferences=false
        }
        val builder=factory.newDocumentBuilder().apply { setErrorHandler(object:ErrorHandler {
            override fun warning(e:SAXParseException) { throw e };override fun error(e:SAXParseException) {throw e};override fun fatalError(e:SAXParseException) {throw e}
        }) }
        val document=try {builder.parse(ByteArrayInputStream(xml))} catch(_:org.xml.sax.SAXException) {throw AgentFailure("UI_XML_INVALID")}
        val nodes=document.getElementsByTagName("node")
        return (0 until nodes.length).any { index ->
            val attributes=nodes.item(index).attributes
            attributes.getNamedItem("package")?.nodeValue==PACKAGE &&
                attributes.getNamedItem("text")?.nodeValue?.lineSequence()?.any {it=="VSRQG_SMOKE_READY:$attemptId"}==true
        }
    }
}
