package com.digitalipvoice.cps.controller

import com.digitalipvoice.cps.components.ProgressEventService
import com.digitalipvoice.cps.model.AppUser
import com.digitalipvoice.cps.model.ProgressEvent
import com.digitalipvoice.cps.utils.logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Flux

@Controller
@RequestMapping("/backpressure_events")
class ProgressEventController {
    @Autowired
    private lateinit var eventService: ProgressEventService


    private val log = logger(javaClass)
    /**
     * Flux subscriber
     */
    @GetMapping("/", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribe(user: AppUser): Flux<ArrayList<ProgressEvent>> {
        return eventService.eventFlux(user.id)
    }
}