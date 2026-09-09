package com.ricezhou.vsrqg.testmanagement.adapter

import com.ricezhou.vsrqg.testmanagement.application.AdvanceTestDeadlines
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@EnableScheduling
@ConditionalOnProperty(name=["vsrqg.demo.smoke.enabled"],havingValue="true")
@ConditionalOnProperty(name=["vsrqg.test.deadline-worker.enabled"],havingValue="true",matchIfMissing=true)
class TestDeadlineWorker(private val deadlines:AdvanceTestDeadlines) {
    @Scheduled(fixedDelayString="\${vsrqg.test.deadline-worker.interval-ms:1000}")
    fun tick() {
        var afterId=""
        while(true) {
            val active=deadlines.activeRuns(afterId)
            if(active.isEmpty()) return
            active.forEach(deadlines::advance)
            afterId=active.last()
        }
    }
}
