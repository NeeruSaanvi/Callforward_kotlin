package com.digitalipvoice.cps.components

import com.digitalipvoice.cps.persistance.model.DcmMessage
import com.digitalipvoice.cps.persistance.model.SMSMessage
import com.digitalipvoice.cps.service.DcmMessageService
import com.digitalipvoice.cps.service.SMSMessageService
import com.digitalipvoice.cps.somos.SomosMessageSendContext
import com.digitalipvoice.cps.somos.message.*
import com.digitalipvoice.cps.somos.toY1DcmMsg
import com.digitalipvoice.cps.somos.updateValues
import com.digitalipvoice.cps.utils.logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque

/**
 * Manages DcmMessageManager
 * Sends Y1DcmMsg over sms client, or processes Y1DcmMsg from server
 * This service internally have blocking queue
 */
@Component
class DcmMessageManager {
    /**
     * UPL Header CorrelationID Generator
     */
    @Autowired
    private lateinit var correlationIDGen: CorrelationIDGen

    /**
     * SMS Transport Header MessageTag(Message ID) generator
     */
    @Autowired
    private lateinit var messageIDGen: CorrelationIDGen

    /**
     * MGI Message repository (UPL + Header)
     */
    @Autowired
    private lateinit var smsMessageService: SMSMessageService

    /**
     * Y1DcmMessage Header repository
     */
    @Autowired
    private lateinit var dcmMessageService: DcmMessageService

    @Autowired
    private lateinit var connectionsManager: SMSConnectionManager

    @Autowired
    lateinit var requestManager: SMSRequestManager

    /**
     * SMS Response message processor. Currently update reserved tollfree number tables.
     */
    @Autowired
    lateinit var messageProcessor:SMSResponseProcessor

    /**
     * Queue of SMSMessage Entity Ids to send
     */
    private val messageQueue =  LinkedBlockingDeque<SomosMessageSendContext>()

    /**
     * Logger
     */
    private val log = logger(javaClass)

    /**
     * Request Map
     * CorrlationID: SomosMessageSendContext map
     */
    private val correlationIdMessageMap = ConcurrentHashMap<String, SomosMessageSendContext>()

    private val dbOperationExecutor = Executors.newFixedThreadPool(1)

    /**
     * Default initialization code
     */
    init {
        // Start new thread for peeking message
        Thread{
            while (true) {
                // Peek a new message
                val ctx = messageQueue.take()
                val msg = ctx.message
                val correlationId = msg.correlationId
                log.debug("Picked message to send, id : ${msg.id}")
                var sentDcmMessage: Y1DcmMsg? = null
                try {
                    val client = connectionsManager.getActiveClient() ?: throw Exception("Could not find active client to send message")

                    // Create Y1DcmMsg skeleton from sms message object
                    val y1dcmMsg = msg.toY1DcmMsg(client.sourceNodeName).apply {
                        t1iHdr.messageId = messageIDGen.next()
                    }

                    // When sending message, un-filled values like tliHeader's srcNodeName and destNodeName
                    correlationIdMessageMap[correlationId] = ctx
                    client.sendMessage(y1dcmMsg)
                    sentDcmMessage = y1dcmMsg
                }catch(ex: Exception){
                    log.error("Error Occurred - ${ex.localizedMessage}")

                    // Remove again.
                    correlationIdMessageMap.remove(correlationId)
                    ex.printStackTrace()

                    // Sleep for 1 seconds and try again
                    Thread.sleep(1000)

                    // Schedule sending message again
                    messageQueue.putFirst(ctx)
                }

                if (sentDcmMessage != null) {
                    // Call sent handler
                    ctx.sentHandler(sentDcmMessage)
                }
            }
        }.start()
    }

    /**
     * When New Message Arrived
     */
    fun onNewMessageArrived(y1dcmMsg: Y1DcmMsg) {
        val hdr = y1dcmMsg.t1iHdr
        val upl = y1dcmMsg.data
        // Check if error code is true
        if (hdr.errorCode == ErrorCode.NO_ERROR && hdr.messageCode == MessageCode.DATA && upl != null) {

            val key = upl.header.correlationID
            correlationIdMessageMap[key]?.receiveHandler1?.let {
                it(String(upl.data))
                // Remove the ctx because response is sent.

                // receiveHandler1 and receiveHandler2 should set exclusively, so when handler exists, just remove this
                correlationIdMessageMap.remove(key)
            }

            // Do some database operations after message is received.
            dbOperationExecutor.submit{
                // Create DCMMsg instance
                val dcmMsg = DcmMessage().apply { updateValues(y1dcmMsg) }

                // Create SMS Message
                val msg = SMSMessage.new().apply {updateValues(upl)}
                msg.isClientMessage = false     // This message is arrived from server

                smsMessageService.save(msg, false)     // save message first.

                // Assign response message id with the same id of message (For faster search)
                msg.responseMessageId = msg.id

                // Match relationship between dcmMsg and msg
                dcmMsg.smsMessageId = msg.id

                // Save DCMMsg (Y1TliHdr part)
                dcmMessageService.save(dcmMsg)

                // Find out relevant request message
                val requestSMSMessage = smsMessageService.findRequestMessage(msg.correlationId, msg.mod)
                if (requestSMSMessage?.id != null) {
                    // Assign request_message_id and response_message_id
                    msg.requestMessageId = requestSMSMessage.id
                    requestSMSMessage.responseMessageId = msg.id
                    smsMessageService.save(msg)
                    smsMessageService.save(requestSMSMessage)

                    // Call receive2 handler
                    correlationIdMessageMap[key]?.receiveHandler2?.let {
                        it(requestSMSMessage.id)
                        // Remove the ctx because response is sent.

                        // receiveHandler1 and receiveHandler2 should set exclusively, so when handler exists, just remove this
                        correlationIdMessageMap.remove(key)
                    }
                }

                // Process Received SMS Message
                messageProcessor.processMessage(requestSMSMessage?.id, msg.id)
            }
        }
    }

    /**
     * Send SMS Message to server, and push the Id of SMSMessage to message queue
     * Before calling this message, request_id of this message should be set
     * *Do not pass already saved instance of message.*
     * Just pass newly created message.
     * This should be called on session thread to identify which user sent it.
     * @param message: SMS Message entity to send. (request_id should be set for this)
     */
    fun sendSMSMessage(msg:SMSMessage, immediateHandler:((String) -> Any)?, afterDbHandler:((String) -> Any)?) {
        // Update upl header information
        with(msg) {
            confirmationFlag = UplHeader.FLAG_APPLICATION_APPLICATION
            DRC = RouteID.get("$verb-$mod")
            errorCode = UplErrorCode.Default
            isClientMessage = true      // Set client message flag to true
        }

        // Create message send context
        val ctx = SomosMessageSendContext(
                msg,
                sentHandler = { y1dcmMsg ->
                    // When message is sent via packet, save to db
                    dbOperationExecutor.submit{
                        try {
                            // Save and flush message
                            // Set sent to server to true
                            msg.isSentToServer = true
                            msg.requestMessageId = msg.id

                            smsMessageService.save(msg)

                            // Create Y1DcmMsg header entity and save it to db after sent
                            val dcmMsg = DcmMessage()
                            dcmMsg.updateValues(y1dcmMsg)
                            dcmMsg.smsMessageId = msg.id

                            dcmMessageService.save(dcmMsg)

                        }catch(ex: Exception) {
                            log.error("Error occurred updating sent message in database - ${ex.localizedMessage}")
                            ex.printStackTrace()
                        }
                    }
                },
                receiveHandler1 = immediateHandler,
                receiveHandler2 = afterDbHandler
        )

        // Add message id to queue to signal queue reading thread
        messageQueue.add(ctx)
    }


    // Remove Context for correlationId, used for timeout
    fun removeConext(correlationId:String) {
        // Just remove the context from map
        correlationIdMessageMap.remove(correlationId)
    }
}