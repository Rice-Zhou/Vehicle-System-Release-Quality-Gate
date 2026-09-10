package com.ricezhou.vsrqg.agent

class LogCollector(private val device:SmokeDevice,private val steps:List<String>):FileCollector("LOG","text/plain","log.txt",1048576) {
    override fun bytes(window:CollectionWindow):ByteArray {
        val id=checkNotNull(context).attemptId
        ensure(steps.all {Regex("[A-Z][A-Z0-9_]{2,63}").matches(it)},"LOG_CODE_INVALID")
        val app=device.appLog();ensure(app.size<=1048576,"COLLECTOR_SIZE_LIMIT")
        // Preserve only this Attempt's fixed markers; device log prefixes may contain unrelated data.
        val marks=Regex("VSRQG_SMOKE_(?:NOT_)?READY:${Regex.escape(id)}(?![A-Za-z0-9_-])").findAll(app.toString(Charsets.UTF_8)).map {it.value}.toList()
        return (listOf("attemptId=$id","startedAt=${window.startedAt}","finishedAt=${window.finishedAt}")+steps+marks).joinToString("\n",postfix="\n").toByteArray()
    }
}
