package com.digitalipvoice.cps.persistance.model

import com.digitalipvoice.cps.configuration.AuditableBase
import com.digitalipvoice.cps.configuration.AuditableBaseId
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.util.*
import javax.persistence.*

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name="sms_message")
class SMSMessage : AuditableBase() {
    @Id
    var id = ""

    /**
     * UPL Header definitions
     */
    @Column(columnDefinition = "tinyint(1)")
    var confirmationFlag: Byte = 0

    @Column(length = 11)
    var correlationId = ""

    @Column(length = 13)
    var sourceNodeName = ""

    @Column(length = 4)
    var DRC = ""

    @Column(columnDefinition = "tinyint(1)")
    var errorCode: Byte = 0

    /**
     * UPL Data definitions
     */
    @Column(length = 10)
    var verb = ""

    @Column(name = "`mod`", length = 10)
    var mod = ""

    @Column(name = "`year`")
    var year = 0

    @Column(name = "`month`")
    var month = 0

    @Column(name = "`day`")
    var day = 0

    @Column(name = "`hour`")
    var hour = 0

    @Column(name = "`minute`")
    var minute = 0

    @Column(name = "`second`")
    var second = 0

    // message time zone CST, CDT, PDT, ...
    @Column(length = 5)
    var timezone = ""

    /**
     * MgiMessage Data Block byte Representation
     */
    @Column(columnDefinition = "MEDIUMTEXT CHARACTER SET utf8 COLLATE utf8_bin NULL DEFAULT NULL")
    var data = ""

    /** Response message field **/
    @Column(length = 10)
    var statusTermRept = ""

    @Column(length = 5)
    var statusErrorCode = ""

    /** Test message field **/
    @Column(length = 7)
    var sequence = ""

    @Column(columnDefinition = "text")
    var testMessage = ""

    /*Indicates if this message is client message or received from server*/
    var isClientMessage = true

    /** Indicates if this message is sent flushed to server */
    var isSentToServer = false

    @Column(length=20)
    var smsId = ""

    @Column(length=20)
    var ro = ""

    /** User id that initiated this message**/
    var userId = 0L

    /**
     * ID of Request SMS Message if this is Response Message
     */
    var requestMessageId = ""

    /**
     * ID of Response SMS Message if this is Request Message
     */
    var responseMessageId = ""

    @OneToOne(mappedBy = "smsMessage", fetch=FetchType.LAZY, optional = true)
    var dcmMessage:DcmMessage? = null

    /**
     * To String method
     */
    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("\n")
                .append("confirmationFlag => $confirmationFlag\n")
                .append("correlationId => $correlationId\n")
                .append("sourceNodeName => $sourceNodeName\n")
                .append("DRC => $DRC\n")
                .append("ErrorCode => $errorCode\n")
                .append("data => $data")
        return builder.toString()
    }

    companion object {
        fun new():SMSMessage {
            val m = SMSMessage()
            m.id = UUID.randomUUID().toString()
            return m
        }
    }
}