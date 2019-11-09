package com.digitalipvoice.cps.controller

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.somos.models.CadBulkRequest
import com.digitalipvoice.cps.client.somos.models.SomosResponse
import com.digitalipvoice.cps.client.somos.models.SomosResponseNew
import com.digitalipvoice.cps.client.somos.models.TimeoutResponse
import com.digitalipvoice.cps.components.CorrelationIDGen
import com.digitalipvoice.cps.components.DcmMessageManager
import com.digitalipvoice.cps.components.ProgressEventService
import com.digitalipvoice.cps.components.SMSRequestManager
import com.digitalipvoice.cps.exceptions.BadRequestException
import com.digitalipvoice.cps.model.*
import com.digitalipvoice.cps.persistance.model.Notification
import com.digitalipvoice.cps.persistance.model.Privilege
import com.digitalipvoice.cps.persistance.model.SMSMessage
import com.digitalipvoice.cps.service.NotificationService
import com.digitalipvoice.cps.service.ReservedNumberService
import com.digitalipvoice.cps.service.SMSMessageService
import com.digitalipvoice.cps.somos.*
import com.digitalipvoice.cps.somos.message.MgiMessage
import com.digitalipvoice.cps.somos.message.Mods
import com.digitalipvoice.cps.somos.message.blockValue
import com.digitalipvoice.cps.utils.logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import java.util.*
import kotlin.collections.ArrayList

@Suppress("MoveLambdaOutsideParentheses")
@Controller
@RequestMapping("/somos/")
@PreAuthorize("isMgiEnabled()")
class SomosController {
    private val log = logger(javaClass)

    @Autowired
    private lateinit var requestManager: SMSRequestManager

    @Autowired
    private lateinit var smsMessageService: SMSMessageService

    @Autowired
    private lateinit var dcmMessageManager: DcmMessageManager

    @Autowired
    private lateinit var reservedNumberService: ReservedNumberService

    @Autowired
    private lateinit var progressEventService: ProgressEventService

    @Autowired
    private lateinit var notificationService: NotificationService

    @Value("\${smsrequest.timeout}")
    private var defaultRequestTimeout: Long = 20

    /**
     * UPL Header CorrelationID Generator
     */
    @Autowired
    private lateinit var correlationIDGen: CorrelationIDGen

    //---------------------------Send Section-------------------------------------------//
    @PostMapping("cad/bulk")
    @ResponseBody
    fun sendBulk(@RequestBody r: CadBulkRequest, user: AppUser): ResponseEntity<Any> {
        if (r.id.isNullOrEmpty())
            throw BadRequestException("Message ID must be specified")
        if (r.ro.isNullOrEmpty())
            throw BadRequestException("Message RO must be specified")
        if (r.ed.isNullOrEmpty())
            throw BadRequestException("Message ED must be specified")
        if (r.iec.isNullOrEmpty())
            throw BadRequestException("Message IEC must be specified")
        if (r.iac.isNullOrEmpty())
            throw BadRequestException("Message IAC must be specified")
        if (r.so.isNullOrEmpty())
            throw BadRequestException("Message SO must be specified")
        if (r.ncon.isNullOrEmpty())
            throw BadRequestException("Message NCON must be specified")
        if (r.ctel.isNullOrEmpty())
            throw BadRequestException("Message CTEL must be specified")
        if (r.anet.isNullOrEmpty())
            throw BadRequestException("Message ANET must be specified")
        if (r.lns.isNullOrEmpty())
            throw BadRequestException("Message LNS must be specified")
        if (r.nums.isEmpty())
            throw BadRequestException("Number List must not empty")

        Thread {
            val totalCount = r.nums.size
            var failedCount = 0
            var sentCount = 0
            val failedNumbers = ArrayList<String>()
            progressEventService.push(user.id, ProgressEventCategory.somosCadBulk, 0.0f, "Sending bulk CAD")
            r.nums?.forEach {
                if (it.isNullOrEmpty()) return@forEach

                val smsIdRo = SomosIdRo(user.id, user.username, r.id, r.ro)

                val message = StringBuffer()
                message.append(":ID=${r.id}")
                message.append(",RO=${r.ro}")
                message.append(",AC=N")
                message.append(",NUM=\"$it\"")
                message.append(",ED=\"NOW\"")
                message.append(":IEC=\"CNT1=01,${r.iec}\"")
                message.append(":IAC=\"CNT2=01,${r.iac}\"")
                message.append(":SO=${r.so}")
                message.append(",NCON=\"${r.ncon}\"")
                message.append(",CTEL=${r.ctel}")
                message.append(":ANET=\"CNT6=01,${r.anet}\":CNT9=01")
                message.append(":TEL=\"$it\"")
                message.append(",LNS=${r.lns}")

                log.error("MESSAGE = $message")

                val result = sendInternal(
                        user, smsIdRo, "REQ", "CRC", message.toString(), 60,
                        quick = { requestId, data -> SomosResponseNew().requestId(requestId).message(data) }
                )

                sentCount++
                result.setResultHandler handle@{ r ->
                    progressEventService.push(user.id, ProgressEventCategory.somosCadBulk, 1.0f * sentCount / totalCount, "Sending bulk CAD")

                    val res = (r as? ResponseEntity<Any>) ?: return@handle
                    val resData = (res.body as? SomosResponseNew) ?: return@handle
                    val mgiMessage = MgiMessage(resData.message)
                    log.error("RESPONSE: ${resData.message}")
                    if (mgiMessage.status_termRept != "COMPLD") {
                        failedCount++
                        failedNumbers.add(it)
                    }

                    log.error("COUNT: $sentCount/$totalCount")
                    if (sentCount == totalCount) {
                        val notification = Notification().apply {
                            this.userId = user.id
                            this.section = Notification.SECTION_CUSTOMER
                            this.type = Notification.TYPE_INFO
                            val failedNumberMessage = if (failedCount > 0) "\n Failed Numbers: " + failedNumbers.run {
                                joinToString(",")
                            } else ""
                            this.message = "Sending Bulk CAD done.\n Success: ${totalCount - failedCount}, Failed: $failedCount of total $totalCount Numbers. $failedNumberMessage"
                            this.description = ""
                            this.category = NotificationCategory.cprGenLataNpaNxxReport2Done
                        }
                        progressEventService.removeEventCategory(user.id, ProgressEventCategory.somosCadBulk)
                        // Save notification will save notification to db and send to user subscribing to notification.
                        notificationService.save(notification)
                    }
                }
            }
        }.start()
        return ResponseEntity.ok("Bulk Sending started")
    }

    /**
     * New send method
     */
    @PostMapping("send_new")
    @ResponseBody
    @Transactional
    fun send_new(@RequestParam(value = "verb", defaultValue = "REQ") verb: String,
                 @RequestParam(value = "mod", required = true) mod: String,
                 @RequestParam(value = "message", required = true) message: String,
                 @RequestParam(value = "timeout", required = false) timeout: Long?,

            // Injected parameters
                 user: AppUser, idRo: SomosIdRo
    ): DeferredResult<ResponseEntity<*>> {
        return sendInternal(user, idRo, verb, mod, message, timeout,
                quick = { requestId, data ->
                    SomosResponseNew().requestId(requestId).message(data)
                })
    }

    /**
     * Send Message Utility function
     * @param verb
     * @param mod
     * @param message
     * @param timeout
     * @param resultFactory
     */
    private fun sendInternal(user: AppUser, idRo: SomosIdRo, verb: String, mod: String, message: String,
                             timeout: Long?,
                             quick: ((String, String) -> Any)? = null,
                             slow: ((String) -> Any)? = null,
                             timeOutResultFactory: (String) -> Any = { TimeoutResponse().requestId(it) }
    ): DeferredResult<ResponseEntity<*>> {
        // Create result object.
        val tmout = timeout ?: defaultRequestTimeout
        // Create deferred result
        val result = DeferredResult<ResponseEntity<*>>(tmout * 1000)    // This is milliseconds


        // Construct SMSMessage
        val m = SMSMessage.new()
        val correlationID = correlationIDGen.next()
        m.correlationId = correlationID
        m.verb = verb
        m.mod = mod
        // Fill date & time
        m.fillDateTime()
        m.data = message

        // Before saving message, do some validation & security check
        val validation = validateRequest(m, user, idRo)
        if (validation != null) {
            result.setResult(validation)
            return result
        }

        // Validation Succeed
        m.isClientMessage = true

        // Assign user id
        m.userId = user.id

        // request message id is the same is its id for request message
        m.requestMessageId = m.id


        val requestId = m.id
        // Send message
        dcmMessageManager.sendSMSMessage(m,
                quick?.let {
                    ({ data: String ->
                        result.setResult(ResponseEntity.ok(it(requestId, data)))
                    })
                }
                ,
                slow?.let {
                    ({ data: String ->
                        result.setResult(ResponseEntity.ok(it(requestId)))
                    })
                })

        // set timeout handler
        result.onTimeout {
            // Remove request done handler first
            dcmMessageManager.removeConext(correlationID)
            result.setErrorResult(ResponseEntity.status(HttpStatus.ACCEPTED).body(timeOutResultFactory(requestId)))
        }


        return result
    }

    //---------------------------Retrieve Section-------------------------------------------//

    /**
     * Retrieve response with request id
     */
    @GetMapping("retrieve/{id}")
    @ResponseBody
    fun retrieve(@PathVariable(name = "id") requestId: String): ResponseEntity<*> {
        val resp = smsMessageService.getSomosResponse(requestId) ?: return ResponseEntity.notFound().build<String>()
        return ResponseEntity.ok(resp)
    }

    /**
     * Retrieve response
     */
    @GetMapping("retrieve_new/{id}")
    @ResponseBody
    fun retrieve_new(@PathVariable(name = "id") requestId: String): ResponseEntity<*> {
        val resp = smsMessageService.getSomosResponseNew(requestId)
                ?: return ResponseEntity.notFound().build<String>()
        return ResponseEntity.ok(resp)
    }

    //----------------------------apis for stand alone apps----------------------------------------//
    @PostMapping("send_standalone")
    @ResponseBody
    @Transactional
    @PreAuthorize("isMgiEnabled() AND hasAuthority('${Privilege.TestBench}')")
    fun send_standalone(@RequestParam(value = "verb", required = true) verb: String,
                        @RequestParam(value = "mod", required = true) mod: String,
                        @RequestParam(value = "message", required = true) message: String,
                        @RequestParam(value = "timeout", required = false) timeout: Long?,
                        user: AppUser

    ): DeferredResult<ResponseEntity<*>> {
        val handler: (String) -> Any = h@{
            return@h smsMessageService.getSomosResponseStandalone(it)
        }
        return sendInternal(user, SomosIdRo(0, "", "", ""), verb, mod, message, timeout,
                slow = { handler(it) })
    }

    @GetMapping("retrieve_standalone/{id}")
    @ResponseBody
    @PreAuthorize("isMgiEnabled() AND hasAuthority('${Privilege.TestBench}')")
    fun retrieve_standalone(@PathVariable(name = "id") requestId: String): ResponseEntity<*> {
        return ResponseEntity.ok(smsMessageService.getSomosResponseStandalone(requestId))
    }

    /**
     * Reserved Number List
     */
    @PostMapping("reserved_numbers")
    @ResponseBody
    @PreAuthorize("isMgiEnabled() AND hasAuthority('${Privilege.ReservedNumberList}')")
    fun reservedNumbers(user: AppUser, somosIdRo: SomosIdRo, @RequestBody query: TableQuery): ResponseEntity<Any> {
        return ResponseEntity.ok(reservedNumberService.find(
                if (user.isSuperAdmin) null else somosIdRo,
                query
        ))
    }


    /**
     * Validate & Pre-Authorize request from client.
     */
    private fun validateRequest(message: SMSMessage, user: AppUser, smsIdRo: SomosIdRo): ResponseEntity<Any>? {

        // Check if this user is able to send test bench message
        if (user.hasPrivilege(Privilege.TestBench)) {
            // No validation required.
            return null
        }

        // Build mgi message
        val mgi = MgiMessage(message.toUplDataString())

        // 1. ID / RO Validation
        val id = mgi.blockValue("ID").firstOrNull() ?: ""
        val ro = mgi.blockValue("RO").firstOrNull() ?: ""

        val builder = ResponseEntity.status(HttpStatus.FORBIDDEN)

        if (smsIdRo.id != id || !smsIdRo.ro.contains(ro)) {
            return builder.body(BaseResponse("ID/RO doesn't match with your account"))
        }

        val forbiddenEntity = ResponseEntity.status(HttpStatus.FORBIDDEN)

        // Mod Check and privileges
        val mod = message.mod
        if (mod == Mods.NumSearchReserve) {     // Number Search Reserve Request.
            // Check Action Code
            val action = mgi.blockValue("AC").firstOrNull() ?: ""
            if (action == "S") {    // Search Number
                if (!user.hasPrivilege(Privilege.NumberSearch)) {
                    return forbiddenEntity.body(forbiddenMessage("Number Search & Reserve"))
                }
            } else if (action == "R") { // Reserve Number
                if (!user.hasPrivilege(Privilege.NumberSearch)) {
                    return forbiddenEntity.body(forbiddenMessage("Number Reservation"))
                }
            } else if (action == "Q") {  // Query Number
                if (!user.hasPrivilege(Privilege.NumberQueryUpdate)) {
                    return forbiddenEntity.body(forbiddenMessage("Number Query"))
                }
            } else {
                return ResponseEntity.badRequest().build()
            }
        } else if (mod == Mods.NumStatusChange) {    // Number Status Change.
            val action = mgi.blockValue("AC").firstOrNull() ?: ""
            if (action == "C" || action == "S" || action == "R") {
                if (!user.hasPrivilege(Privilege.NumberStatusChange) && !user.hasPrivilege(Privilege.NumberQueryUpdate)) {
                    return forbiddenEntity.body(forbiddenMessage("Number Status Change"))
                }
            } else {
                return ResponseEntity.badRequest().build()
            }
        } else if (mod == Mods.MutiDialNumQuery) {    // Multi Dial Number Query.
            if (!user.hasPrivilege(Privilege.MultiNumberQuery)) {
                return forbiddenEntity.body(forbiddenMessage("Request Multi Dial Number Query"))
            }
        } else if (mod == Mods.UpdateComplexRec) {    // Update Complex Record
            // Check if this request is related to template or not.
            val isPointer = mgi.blockValue("TMPLTPTR").firstOrNull() != null
            if (isPointer) {
                if (!user.hasPrivilege(Privilege.MultiConversionToPointerRecords) && !user.hasPrivilege(Privilege.PointerRecord)) {
                    return forbiddenEntity.body(forbiddenMessage("PAD"))
                }
            } else {
                if (!user.hasPrivilege(Privilege.CustomerRecord)) {
                    return forbiddenEntity.body(forbiddenMessage("CAD"))
                }
            }
        } else if (mod == Mods.TemplateRecLst) {    // TRL
            // Should have TAD or TRL privilege
            if (!user.hasPrivilege(Privilege.TemplateAdminData) && !user.hasPrivilege(Privilege.TemplateRecordList)) {
                return forbiddenEntity.body(BaseResponse("TRL"))
            }
        } else if (mod == Mods.UpdateTemplateRec) { // TRC
            // Should have TAD or TRL privilege
            if (!user.hasPrivilege(Privilege.TemplateAdminData) && !user.hasPrivilege(Privilege.TemplateRecordList)) {
                return forbiddenEntity.body(BaseResponse("TRC"))
            }
        } else if (mod == Mods.RecStatQuery || mod == Mods.RecQuery) {      // CRQ or CRV (Record Status Query, Record Query)
            val isTemplate = mgi.blockValue("TMPLTNM").firstOrNull() != null
            if (isTemplate) {
                if (!user.hasPrivilege(Privilege.TemplateAdmin)) {
                    return forbiddenEntity.body(forbiddenMessage("Template Record Query"))
                }
            } else {
                if (!user.hasPrivilege(Privilege.CustomerRecord) && !user.hasPrivilege(Privilege.PointerRecord)) {
                    return forbiddenEntity.body(forbiddenMessage("Customer Record Query"))
                }
            }
        } else if (mod == Mods.TroubleRefNumQuery) {    // TRN
            if (!user.hasPrivilege(Privilege.TroubleReferralNumberQuery)) {
                return forbiddenEntity.body(forbiddenMessage("Trouble Referral Number Query"))
            }
        } else if (mod == Mods.MultiDialNumROChange) {   // MRO
            if (!user.hasPrivilege(Privilege.MultiNumberChangeRespOrg)) {
                return forbiddenEntity.body(forbiddenMessage("Mulitple Number Change RO"))
            }
            val newRO = mgi.blockValue("NEWRO").firstOrNull() ?: ""
            if (newRO.isEmpty()) {
                return ResponseEntity.badRequest().body(BaseResponse("NEWRO parameter required"))
            }
        } else {
            return ResponseEntity.badRequest().body(BaseResponse("Operation not supported by system yet."))
        }
        return null
    }

    fun forbiddenMessage(priv: String) = BaseResponse("You are not granted access to ${priv}.")
}