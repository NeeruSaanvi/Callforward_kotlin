package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.SMSMessage
import org.springframework.data.jpa.repository.JpaRepository

interface SMSMessageRepository : JpaRepository<SMSMessage, String> {
    /**
     * Find Mgi Request message for response in case of request.
     * By Correlation Id and client message should be true and order by ID Descending
     * And also message mod
     */
    fun findByCorrelationIdAndModAndIsClientMessageTrueOrderByIdDesc(correlationId:String, mod:String) : SMSMessage?

    fun findByIdAndIsClientMessageTrue(id:String): SMSMessage?

    fun findByRequestMessageIdAndIsClientMessageFalse(requestMessageId: String):SMSMessage?
}