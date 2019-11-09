package com.digitalipvoice.cps.components

import com.digitalipvoice.cps.model.ProgressEvent
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.ReplayProcessor
import reactor.core.publisher.toFlux
import java.util.concurrent.Executors


@Component
class ProgressEventService {
    // Processor map per user id
    private val processorMap = mutableMapOf<Long, ReplayProcessor<ArrayList<ProgressEvent>>>()
    private val eventMap = mutableMapOf<Long, ArrayList<ProgressEvent>>()
    private val executor = Executors.newSingleThreadExecutor()

    fun push(userId: Long, category: String, progress: Float, description: String) {
        push(userId, ProgressEvent(category, progress, description))
    }

    fun removeEventCategory(userId: Long, category: String) {
        executor.submit {
            eventMap[userId]?.let { events ->
                events.removeIf{it.category == category}
                processorMap[userId]?.onNext(events)
            }
        }
    }

    fun push(userId: Long, event: ProgressEvent) {
        executor.submit {
            // eventMap
            val eventArray = eventMap[userId]?: arrayListOf()
            var isExist = false

            eventArray.find { it.category == event.category }?.let {
                it.progress = event.progress
                it.description = event.description
                isExist = true
            }

            if (!isExist)
                eventArray.add(event)

            eventMap[userId] = eventArray

            // processorMap
            val replayProcessor = processorMap[userId]?:ReplayProcessor.cacheLast<ArrayList<ProgressEvent>>()
            processorMap[userId] = replayProcessor
            replayProcessor.onNext(eventArray)
        }
    }

    /**
     * Subscribe for user id
     */
    fun eventFlux(userId: Long): Flux<ArrayList<ProgressEvent>> {
        val processor = executor.run {
            val replayProcessor = processorMap[userId]?:ReplayProcessor.cacheLast<ArrayList<ProgressEvent>>()
            processorMap[userId] = replayProcessor
            return@run replayProcessor
        }
        return processor.toFlux()
    }
}