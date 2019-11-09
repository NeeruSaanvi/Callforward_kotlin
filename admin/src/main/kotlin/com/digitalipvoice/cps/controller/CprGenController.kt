package com.digitalipvoice.cps.controller

import com.digitalipvoice.cps.client.admin.models.*
import com.digitalipvoice.cps.components.AppState
import com.digitalipvoice.cps.components.ProgressEventService
import com.digitalipvoice.cps.configuration.FileStorageProperties
import com.digitalipvoice.cps.exceptions.BadRequestException
import com.digitalipvoice.cps.model.AppUser
import com.digitalipvoice.cps.model.BaseResponse
import com.digitalipvoice.cps.model.NotificationCategory
import com.digitalipvoice.cps.model.ProgressEventCategory
import com.digitalipvoice.cps.persistance.model.*
import com.digitalipvoice.cps.service.*
import com.digitalipvoice.cps.utils.alexFormat
import com.digitalipvoice.cps.utils.escapeStringForMySQL
import com.digitalipvoice.cps.utils.isNumeric
import com.digitalipvoice.cps.utils.logger
import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReader
import com.opencsv.CSVReaderBuilder
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.LineIterator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.FileReader
import java.io.OutputStreamWriter
import java.util.*
import javax.servlet.http.HttpServletResponse
import kotlin.collections.ArrayList
import kotlin.math.ceil

@Controller
@RequestMapping("/cprgen")
class CprGenController {
    private val log = logger(javaClass)

    @Autowired
    private lateinit var appState: AppState

    @Autowired
    private lateinit var storageProperties: FileStorageProperties

    @Autowired
    private lateinit var notificationService: NotificationService

    @Autowired
    private lateinit var progressEventService: ProgressEventService

    @Autowired
    private lateinit var lergImportService: LergImportService

    @Autowired
    private lateinit var rateDeckService: RateDeckService

    @Autowired
    private lateinit var cdrDataService: CdrDataService

    @Autowired
    private lateinit var lcrReportService: LcrReportService

    @Autowired
    private lateinit var cprReportService: CprReportService

    @Autowired
    private lateinit var lataNpanxxReport1Service: LataNpanxxReport1Service

    @Autowired
    private lateinit var lataNpanxxReport2Service: LataNpanxxReport2Service

    @Autowired
    private lateinit var ladService: LadService

    @Autowired
    private lateinit var newCprReportService: NewCprReportService

    @Value("\${spring.jpa.properties.hibernate.jdbc.batch_size}")
    private var batchInsertSize = 0

    @Value("\${rawinserts.batch_size}")
    private var jdbcBatchInsertSize = 0

    private val progressCount = 20000

    private fun getRowCount(file: File): Int {
        val lineIterator = LineIterator(FileReader(file))
        var count = 0
        try {
            while (lineIterator.hasNext()) {
                lineIterator.nextLine()
                count++
            }
        } catch (ex: java.lang.Exception) {

        } finally {
            lineIterator.close()
        }
        return count
    }


    /**
     * Upload Lerg File
     */
    @PostMapping("/lerg/upload")
    @PreAuthorize("hasAuthority('${Privilege.LergImport}')")
    @ResponseBody
    fun uploadLerg(@RequestParam("file") file: MultipartFile, @RequestParam("delimiter") delimiter: String): ResponseEntity<Any> {
        if (appState.isLergImportInProgress)
            throw BadRequestException("RGLE Import is in Progress")

        val originalFileName = file.originalFilename
        val ext = FilenameUtils.getExtension(originalFileName)

        val uploadDir = storageProperties.uploadDir ?: ""
        if (uploadDir.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BaseResponse("Upload directory is invalid."))
        }

        var reader: CSVReader? = null
        try {
            // Make directory
            File(uploadDir).mkdirs()
            // Generate file name
            val filename = "lerg_${UUID.randomUUID()}.$ext"

            val save2File = File(uploadDir + File.separator + filename)
            file.transferTo(save2File)
            val separator = when (delimiter) {
                InsertLergRequest.DelimiterEnum.COMMA.value -> ','
                InsertLergRequest.DelimiterEnum.PIPE.value -> '|'
                InsertLergRequest.DelimiterEnum.SEMICOLON.value -> ';'
                InsertLergRequest.DelimiterEnum.TAB.value -> '\t'
                else -> throw BadRequestException("Delimiter not specified")
            }

            // Try open file
            reader = CSVReaderBuilder(FileReader(save2File))
                    .withCSVParser(
                            CSVParserBuilder()
                                    .withSeparator(separator)

                                    .build())
                    .build()

            val row = reader.readNext()

            val columns = mutableListOf<String>()

            // Support 20 columns
            for (i in 0..20) {
                columns.add(if (row.size > i) row[i] else "")
            }

            return ResponseEntity.ok(UploadLergResponse().filename(filename).message("").columns(columns))
        } catch (ex: Exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(BaseResponse("Upload failed."))
        } finally {
            reader?.close()
        }
    }

    /**
     * Insert Lerg
     */
    @PostMapping("/lerg/insert")
    @PreAuthorize("hasAuthority('${Privilege.LergImport}')")
    @ResponseBody
    fun insertLerg(@RequestBody r: InsertLergRequest, user: AppUser): ResponseEntity<Any> {
        val uploadDir = storageProperties.uploadDir ?: ""
        val filePath = uploadDir + File.separator + r.filename

        // Check column indices

        val file = File(filePath)

        if (!file.exists() || !file.isFile || r.delimiter == null)
            return ResponseEntity.badRequest()
                    .body(BaseResponse("Uploaded file not found"))

        val separator = when (r.delimiter) {
            InsertLergRequest.DelimiterEnum.COMMA -> ','
            InsertLergRequest.DelimiterEnum.PIPE -> '|'
            InsertLergRequest.DelimiterEnum.SEMICOLON -> ';'
            InsertLergRequest.DelimiterEnum.TAB -> '\t'
            else -> throw BadRequestException("Delimiter not specified")
        }


        // Create a new thread to perform lerg import
        var reader: CSVReader? = null
        Thread {
            appState.isLergImportInProgress = true

            var wholeCount = 0
            try {

                // Build reader
                if (r.insertType == InsertLergRequest.InsertTypeEnum.OVERWRITE) {
                    lergImportService.deleteAll()
                }


                val rowCount = getRowCount(file)

                reader = CSVReaderBuilder(FileReader(file))
                        .withCSVParser(
                                CSVParserBuilder()
                                        .withSeparator(separator)

                                        .build())
                        .build()


                progressEventService.push(user.id, ProgressEventCategory.lergImport, 0.01f, "Importing RGLE Data...")
                val batchSize = jdbcBatchInsertSize
                val values = ArrayList<String>(batchSize)
                for (row in reader!!) {
                    // Skip first row
                    if (r.isHasColumnHeader) {
                        r.isHasColumnHeader = false
                        continue
                    }

                    val state = (if (row.size > r.state) row[r.state].trim() else "").escapeStringForMySQL()
                    val npa = (if (row.size > r.npa) row[r.npa].trim() else "").escapeStringForMySQL()
                    val nxx = (if (row.size > r.nxx) row[r.nxx].trim() else "").escapeStringForMySQL()

                    val npaNxx = npa + nxx

                    val x = (if (row.size > r.x) row[r.x].trim() else "").escapeStringForMySQL()
                    val lata = (if (row.size > r.lata) row[r.lata].trim() else "").escapeStringForMySQL()
                    val carrier = (if (row.size > r.carrier) row[r.carrier].trim() else "").escapeStringForMySQL()
                    val acna = (if (row.size > r.acna) row[r.acna].trim() else "").escapeStringForMySQL()
                    val cic = (if (row.size > r.cic) row[r.cic].trim() else "").escapeStringForMySQL()
                    val acnaCic = ("$acna-$cic").escapeStringForMySQL()

                    // if non numeric npanxx found, just ignore
                    if (!npaNxx.isNumeric()) continue

                    values.add("('$npaNxx','$npa','$nxx','$state','$lata','$carrier','$acna','$cic','$acnaCic')")

                    // When exceeded batch size,
                    if (values.size >= batchSize) {
                        wholeCount += lergImportService.insertBatch(values)
                        if (wholeCount % progressCount == 0)
                            progressEventService.push(user.id, ProgressEventCategory.lergImport, wholeCount.toFloat() / rowCount, "Importing RGLE Data...")
                        values.clear()
                    }
                }

                // If any padding (remaining) lergs...
                if (values.size > 0) {
                    wholeCount += lergImportService.insertBatch(values)
                    values.clear()
                }
                progressEventService.push(user.id, ProgressEventCategory.lergImport, 1.0f, "Completed Importing RGLE Data.")
            } catch (ex: Exception) {
                ex.printStackTrace()
            } finally {
                // Close reader
                reader?.close()
                // Notification
                val notification = Notification().apply {
                    userId = user.id
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = "RGLE Import $wholeCount Record(s) done"
                    description = ""
                    category = NotificationCategory.cprGenRGLEImportDone
                }
                appState.isLergImportInProgress = false
                progressEventService.removeEventCategory(user.id, ProgressEventCategory.lergImport)
                // Save notification will save notification to db and send to user subscribing to notification.
                notificationService.save(notification)
            }
        }.start()
        return ResponseEntity.ok(BaseResponse("RGLE Data Import started"))
    }

    /**
     *  Search Lerg
     * */
    @PostMapping("/lerg/search")
    @PreAuthorize("hasAnyAuthority('${Privilege.LergImport}', '${Privilege.ViewLerg}')")
    @ResponseBody
    fun searchLerg(@RequestBody r: TableQuery): ResponseEntity<TableResult> {
        if (appState.isLergImportInProgress)
            throw BadRequestException("RGLE Import is in Progress")

        return ResponseEntity.ok(lergImportService.searchLerg(r))
    }

    /**
     * Upload Rate File
     */
    @PostMapping("/rate/upload")
    @PreAuthorize("hasAuthority('${Privilege.RateImport}')")
    @ResponseBody
    fun uploadRate(@RequestParam("file") file: MultipartFile, @RequestParam("delimiter") delimiter: String): ResponseEntity<Any> {
        val originalFileName = file.originalFilename
        val ext = FilenameUtils.getExtension(originalFileName)

        val uploadDir = storageProperties.uploadDir ?: ""
        if (uploadDir.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BaseResponse("Upload directory is invalid."))
        }

        var reader: CSVReader? = null
        try {
            // Make directory
            File(uploadDir).mkdirs()
            // Generate file name
            val filename = "rate_${UUID.randomUUID()}.$ext"

            val save2File = File(uploadDir + File.separator + filename)
            file.transferTo(save2File)
            val separator = when (delimiter) {
                InsertRateRequest.DelimiterEnum.COMMA.value -> ','
                InsertRateRequest.DelimiterEnum.PIPE.value -> '|'
                InsertRateRequest.DelimiterEnum.SEMICOLON.value -> ';'
                InsertRateRequest.DelimiterEnum.TAB.value -> '\t'
                else -> throw BadRequestException("Delimiter not specified")
            }

            // Try open file
            reader = CSVReaderBuilder(FileReader(save2File))
                    .withCSVParser(
                            CSVParserBuilder()
                                    .withSeparator(separator)

                                    .build())
                    .build()

            val row = reader.readNext()

            val columns = mutableListOf<String>()

            // Support 20 columns
            for (i in 0..20) {
                columns.add(if (row.size > i) row[i] else "")
            }

            return ResponseEntity.ok(UploadRateResponse().filename(filename).message("").columns(columns))
        } catch (ex: Exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(BaseResponse("Upload failed."))
        } finally {
            reader?.close()
        }
    }

    /**
     * Insert Rate Decks
     */
    @PostMapping("/rate/insert")
    @PreAuthorize("hasAuthority('${Privilege.RateImport}')")
    @ResponseBody
    fun insertRateDeck(@RequestBody r: InsertRateRequest, user: AppUser): ResponseEntity<Any> {
        val uploadDir = storageProperties.uploadDir ?: ""
        val filePath = uploadDir + File.separator + r.filename

        if (r.rateName?.trim()?.isNotEmpty() != true || r.rateName?.trim()?.isNotEmpty() != true) {
            throw BadRequestException("Please specify RateName and CIC")
        }

        // Check column indices

        val file = File(filePath)

        if (!file.exists() || !file.isFile || r.delimiter == null)
            return ResponseEntity.badRequest()
                    .body(BaseResponse("Uploaded file not found"))

        val separator = when (r.delimiter) {
            InsertRateRequest.DelimiterEnum.COMMA -> ','
            InsertRateRequest.DelimiterEnum.PIPE -> '|'
            InsertRateRequest.DelimiterEnum.SEMICOLON -> ';'
            InsertRateRequest.DelimiterEnum.TAB -> '\t'
            else -> throw BadRequestException("Delimiter not specified")
        }

        // Check carrier name, validate (3 character, uppercase), insert new carrier if user hasn't this carrier
        val carrierName = r.carrierName?.trim()?.toUpperCase() ?: ""
        if (carrierName.length != 3) {
            throw BadRequestException("Carrier Name must contain 3 characters")
        }

        val rateName = if (r.rateName.isNotEmpty()) r.rateName else ""
        if (rateName.length < 0) {
            throw BadRequestException("RateDeck Name must be specified")
        }
        val defaultRate = r.defaultRate ?: throw BadRequestException("Default Rate for this Rate Deck must be set")
        // if posted rate deck name exist, reject
        if (rateDeckService.findRateDeckItemByUserIdAndRateName(user.id, rateName) != null) {
            throw BadRequestException("RateDeck '$rateName' already exists")
        }


        val userId: Long = user.id

        // Create a new thread to perform rate import
        var reader: CSVReader? = null
        Thread {

            var wholeCount = 0
            try {
                appState.setRateDeckInProgress(userId, true)

                // update rate deck list
                val rateDeckItem = rateDeckService.createRateDeckItemIfNotFound(rateName, carrierName, userId, defaultRate)
                rateDeckService.saveRateDeckItem(rateDeckItem)

                val rowCount = getRowCount(file)

                reader = CSVReaderBuilder(FileReader(file))
                        .withCSVParser(
                                CSVParserBuilder()
                                        .withSeparator(separator)

                                        .build())
                        .build()

                val values = ArrayList<String>()
                val batchSize = jdbcBatchInsertSize
                progressEventService.push(user.id, ProgressEventCategory.rateDeckImport, 0.01f, "Importing RateDeck Data...")

                for (row in reader!!) {
                    // Skip first row
                    if (r.isHasColumnHeader) {
                        r.isHasColumnHeader = false
                        continue
                    }

                    val effDate = if (r.effDate.isNotEmpty()) r.effDate else ""
                    val incrementDuration = r.incrementDuration?.let { if (r.incrementDuration.isNotEmpty()) r.incrementDuration else null }
                            ?: ""
                    val initDuration = r.initDuration?.let { if (r.initDuration.isNotEmpty()) r.initDuration else null }?.toFloatOrNull()
                            ?: ""
                            ?: ""
                    val interRate = r.interRate?.let { if (row.size > it) row[r.interRate].trim() else null }?.toFloatOrNull()
                            ?: 0.0f
                    val intraRate = r.intraRate?.let { if (row.size > it) row[r.intraRate].trim() else null }?.toFloatOrNull()
                            ?: 0.0f


                    val lata = r.lata?.let { if (row.size > it) row[r.lata].trim() else null } ?: ""
                    val npa = r.npa?.let { if (row.size > it) row[r.npa].trim() else null } ?: ""
                    val npaNxx = r.npanxx?.let {
                        if (row.size > it) {
                            row[r.npanxx].trim()
                        } else null
                    } ?: ""
                    val nxx = r.nxx?.let { if (row.size > it) row[r.nxx].trim() else null } ?: ""
                    val ocn = r.ocn?.let { if (row.size > it) row[r.ocn].trim() else null } ?: ""

                    // If non numeric npanxx found, just ignore
                    if (!npaNxx.isNumeric()) continue

                    values.add("('$npaNxx',${rateDeckItem.id}, '$effDate', '$incrementDuration','$initDuration',$interRate,$intraRate,'$lata','$npa','$nxx','$ocn')")

                    if (values.size >= batchSize) {
                        wholeCount += rateDeckService.insertBatch(values)
                        values.clear()
                        if (wholeCount % progressCount == 0)
                            progressEventService.push(user.id, ProgressEventCategory.rateDeckImport, wholeCount.toFloat() / rowCount, "Importing RateDeck Data...")
                    }
                }

                // If any padding (remaining) rates...
                if (values.size > 0) {
                    wholeCount += rateDeckService.insertBatch(values)
                    values.clear()
                }
                progressEventService.push(user.id, ProgressEventCategory.rateDeckImport, 1.0f, "Completed Importing RateDeck Data.")
            } catch (ex: Exception) {
                ex.printStackTrace()
            } finally {
                // Close reader
                reader?.close()
                // Notification
                val notification = Notification().apply {
                    this.userId = userId
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = "Rate Import $wholeCount Record(s) done"
                    description = ""
                    category = NotificationCategory.cprGenRateDeckImportDone
                }
                appState.setRateDeckInProgress(userId, false)
                progressEventService.removeEventCategory(user.id, ProgressEventCategory.rateDeckImport)
                // Save notification will save notification to db and send to user subscribing to notification.
                notificationService.save(notification)
            }
        }.start()
        return ResponseEntity.ok(BaseResponse("Rate Decks Import Started"))
    }

    @GetMapping("/rate/list")
    @PreAuthorize("hasAnyAuthority('${Privilege.RateImport}', '${Privilege.ViewRate}', '${Privilege.LCRReport}')")
    @ResponseBody
    fun getRateDeckList(user: AppUser): ResponseEntity<List<RateDeckDTO>> {
        return ResponseEntity.ok(rateDeckService.getRateDeckByUserId(user.id).map {
            RateDeckDTO().apply {
                id = it.id
                name = it.name
                carrier = it.carrier
            }
        })
    }

    @PutMapping("/rate/list/rename/{id}")
    @PreAuthorize("hasAuthority('${Privilege.RateImport}')")
    @ResponseBody
    fun renameRateDeck(@PathVariable("id") rateDeckId: Long, @RequestParam("newName") newName: String, user: AppUser): ResponseEntity<Any> {
        // Check if role is able to rename
        val rateDeckItem = rateDeckService.findRateDeckItemById(rateDeckId)
        if (rateDeckItem == null || rateDeckItem.userId != user.id) {
            return ResponseEntity.badRequest().body(BaseResponse("Selected RateDeck($rateDeckId) doesn't exist or isn't yours"))
        }
        if (rateDeckService.renameRateDeckItemById(rateDeckId, newName) == 0)
            return ResponseEntity.badRequest().body(BaseResponse("Failed to rename"))
        return ResponseEntity.ok(BaseResponse("Rename RateDeck Success"))
    }

    @DeleteMapping("/rate/list/delete/{id}")
    @PreAuthorize("hasAuthority('${Privilege.RateImport}')")
    @ResponseBody
    fun deleteRateDeck(@PathVariable("id") rateDeckId: Long, user: AppUser): ResponseEntity<Any> {
        // Check if role is deletable
        val rateDeckItem = rateDeckService.findRateDeckItemById(rateDeckId)
        if (rateDeckItem == null || rateDeckItem.userId != user.id) {
            return ResponseEntity.badRequest().body(BaseResponse("Selected RateDeck($rateDeckId) doesn't exist or isn't yours"))
        }
        try {
            rateDeckService.deleteRateDeck(rateDeckItem)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return ResponseEntity.ok(BaseResponse("Rate Decks Delete Success"))
    }


    @PostMapping("/rate/search")
    @PreAuthorize("hasAnyAuthority('${Privilege.RateImport}', '${Privilege.ViewRate}')")
    @ResponseBody
    fun searchRate(@RequestBody r: TableQuery): ResponseEntity<TableResult> {
        return ResponseEntity.ok(rateDeckService.searchRateDeckData(r))
    }

    /**
     * Get ALL LCR REPORT LIST
     */
    @GetMapping("/lcr_report/list")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun getLcrReportList(user: AppUser): ResponseEntity<List<LcrReportDTO>> {
        return ResponseEntity.ok(lcrReportService.findLcrReportsByUserId(user.id).map {
            LcrReportDTO().apply {
                id = it.id
                name = it.name
            }
        })
    }

    /**
     * LCR Report List Search
     */
    @PostMapping("/lcr_report/search")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLcrReportData(@RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lcrReportService.searchLcrReportByUserId(r, user.id))
    }


    /**
     * Create a new LCR Report & Report Data
     */
    @PostMapping("/lcr_report")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun generateLCRReport(@RequestBody r: LCRReportRequest, user: AppUser): ResponseEntity<Any> {
        if (appState.isRateDeckInProgress(user.id))
            throw BadRequestException("Rate Deck Import in progress, Please wait a moment")

        if (r.rateNames == null || r.rateNames.size < 1) {
            throw BadRequestException("Please select more than 1 rate deck(s) to generate LCR report")
        }

        if (r.name?.isNotEmpty() != true) {
            throw BadRequestException("Please set name for new LCR Report")
        }

        if (lcrReportService.findLcrReport(user.id, r.name) != null) {
            throw BadRequestException("The report with name '${r.name}' exists. Please try with anther name")
        }

        for (rateName in r.rateNames) {
            if (rateDeckService.findRateDeckItemByUserIdAndRateName(user.id, rateName) == null)
                return ResponseEntity.badRequest().body(BaseResponse("No RateDeck named '($rateName)' found"))
        }

        val userId = user.id
        Thread {
            var isFailed = false
            var description = ""

            val rateDecks = r.rateNames.mapNotNull { rateDeckService.findRateDeckItemByUserIdAndRateName(userId, it) }
            val newLcrReportItem = LcrReport(r.name, userId)
            newLcrReportItem.rateDecks = HashSet(rateDecks)
            lcrReportService.saveLcrReport(newLcrReportItem)

            try {
                progressEventService.push(user.id, ProgressEventCategory.lcrReport, 0.1f, "Generating LCR Report...")
                lcrReportService.generateLcrReportByRateNamesNew(userId, r.rateNames, newLcrReportItem.id)
                progressEventService.push(user.id, ProgressEventCategory.lcrReport, 1.0f, "Completed Generating LCR Report.")

            } catch (ex: Exception) {
                ex.printStackTrace()
                description = ex.message ?: ""
                isFailed = true
                newLcrReportItem.isDeleted = true
                lcrReportService.saveLcrReport(newLcrReportItem)
            } finally {
                // Notification
                val notification = Notification().apply {
                    this.userId = user.id
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = if (!isFailed) "LcrReport '${r.name}' Generation was done" else "LcrReport Generation was failed"
                    this.description = description
                    category = NotificationCategory.cprGenLcrReportDone
                }
                progressEventService.removeEventCategory(userId, ProgressEventCategory.lcrReport)
                // Save notification will save notification to db and send to user subscribing to notification.
                notificationService.save(notification)
            }
        }.start()
        return ResponseEntity.ok(BaseResponse("LcrReport Generation started"))
    }

    /**
     * LCR Report Data view by id
     */
    @PostMapping("/lcr_report/{id}")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLcrReportDataById(@PathVariable("id") reportId: Long, @RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lcrReportService.searchLcrReportDataByReportId(r, reportId))
    }

    @DeleteMapping("/lcr_report/{id}")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun deleteLcrReportById(@PathVariable("id") reportId: Long, user: AppUser): ResponseEntity<Any> {
        lcrReportService.deleteLcrReportDataByReportId(reportId)
        return ResponseEntity.ok(BaseResponse("Deleted"))
    }

    @PostMapping("/lcr_report/{id}/download")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    fun downloadLcrReportDataById(@PathVariable("id") reportId: Long, user: AppUser, response: HttpServletResponse) {

        response.contentType = "text/csv"
        response.setHeader("Content-Disposition", "attachment; filename=\"LCR_Report.csv\"")

        val writer = OutputStreamWriter(response.outputStream)

        try {
            writer.write("STATE,LATA,NPANXX,Min Carrier,MIN Rae,Carrier 1,Carrier 2,Carrier 3,Carrier 4,Carrier 5\n")
            fun writeRows(rows: List<LcrReportData>) {
                for (i in 0 until rows.count()) {
                    with(rows[i]) {

                        /*                      // display candidates in its position column
                                                val carrier5 = if (carrier_5 == carrier_4) "" else carrier_5
                                                val carrier4 = if (carrier_4 == carrier_3) "" else carrier_4
                                                val carrier3 = if (carrier_3 == carrier_2) "" else carrier_3
                                                val carrier2 = if (carrier_1 == carrier_2) "" else carrier_2

                                                writer.run {
                                                    write("${lata ?: ""},${state ?: ""},$npaNxx,${minCarrier ?: ""},${String.format("%.5f", minRate)},${carrier_1 ?: ""},${carrier2 ?: ""},${carrier3 ?: ""},${carrier4 ?: ""},${carrier5 ?: ""}\n")
                                                }
                        */
                        val minCarriers = ArrayList<String>()
                        minCarriers.add(carrier_1 ?: "")
                        minCarriers.add(carrier_2 ?: "")
                        minCarriers.add(carrier_3 ?: "")
                        minCarriers.add(carrier_4 ?: "")
                        minCarriers.add(carrier_5 ?: "")
                        writer.run {
                            write("${state ?: ""},${lata ?: ""},$npaNxx,${minCarrier
                                    ?: ""},${String.format("%.5f", minRate)},${minCarriers.distinct().joinToString(",")}\n")
                        }
                    }
                }
            }

            val (firstItems, totalPages) = lcrReportService.getLcrData(reportId)
            writeRows(firstItems)
            // first Item
            if (totalPages >= 0) {
                for (i in 1 until totalPages) {
                    writeRows(lcrReportService.getLcrData(reportId, i).first)
                }
            }
            // statistics
            val lcrReport = lcrReportService.findLcrReportById(reportId)
            if (lcrReport?.isDeleted == false) {
                with(lcrReport) {
                    writer.write(",,,,,,,,,\n")
                    writer.write(",,Report Name           ,$name\n")
                    writer.write(",,Default Carrier       ,$defaultCarrier\n")
                    writer.write(",,MinCarrier Composition,$description\n")
                    writer.write(",,Average Rate,$averageRate\n")
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            writer.flush()
            writer.close()
        }
    }


    @PostMapping("/cpr_report")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun generateCprReport(@RequestBody r: CreateCprReportRequest, user: AppUser): ResponseEntity<Any> {
        if (appState.isLergImportInProgress) {
            throw BadRequestException("LergImport in progress, Please wait a moment")
        }
        if (appState.isRateDeckInProgress(user.id))
            throw BadRequestException("Rate Deck Import in progress, Please wait a moment")
        if (appState.isCdrImportInProgress(user.id))
            throw BadRequestException("CDR Import in progress, Please wait a moment")

        if (r.name?.isEmpty() != false) {
            throw BadRequestException("Please set name for new CPR Report")
        }

        if (r.defaultRate == null) {
            throw BadRequestException("Please specify default rate")
        }

        if (cprReportService.findCprReportByName(r.name, user.id) != null) {
            throw BadRequestException("The report with name '${r.name}' exists. Please try with anther name")
        }

        val lcrReport = lcrReportService.findLcrReportById(r.lcrReportId)
                ?: throw BadRequestException("No lcr report with id '${r.lcrReportId}' found")
        val userId = user.id
        var count = 0

        val newCprReportItem = CprReport(r.name, userId)
        newCprReportItem.lcrReport = lcrReport
        cprReportService.saveCprReportItem(newCprReportItem)

        Thread {
            try {
                progressEventService.push(userId, ProgressEventCategory.cprReport, 0.0f, "CPR Report generating...")
                count = cprReportService.generateCprReport(lcrReport.id, r.name, userId, newCprReportItem.id, r.defaultRate)
                progressEventService.push(userId, ProgressEventCategory.cprReport, 1.0f, "CPR Report done.")

            } catch (ex: Exception) {
                ex.printStackTrace()
            } finally {
                // Notification
                val notification = Notification().apply {
                    this.userId = userId
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = "CprReport Generation ($count records) was done"
                    description = ""
                }
                progressEventService.removeEventCategory(userId, ProgressEventCategory.cprReport)
                // Save notification will save notification to db and send to user subscribing to notification.
                notificationService.save(notification)
            }
        }.start()
        return ResponseEntity.ok(BaseResponse("CprReport Generation started"))
    }

    @PostMapping("/cpr_report/list")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun getCprReports(@RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(cprReportService.searchCprReportsByUserId(r, user.id))
    }

    @PostMapping("/cpr_report/{id}")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun getCprReportDataById(@PathVariable id: Long, @RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(cprReportService.searchReportDataByReportId(r, id, user.id))
    }

    @DeleteMapping("/cpr_report/{id}")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun deletCprReportById(@PathVariable id: Long, user: AppUser): ResponseEntity<Any> {
        cprReportService.deleteCprReportById(id, user)
        return ResponseEntity.ok(BaseResponse("Deleted"))
    }

    @PostMapping("/cpr_report/{id}/download")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    fun downloadCprReportDataById(@PathVariable("id") reportId: Long, user: AppUser, response: HttpServletResponse) {

        response.contentType = "text/csv"
        response.setHeader("Content-Disposition", "attachment; filename=\"cpr_report.csv\"")

        val writer = OutputStreamWriter(response.outputStream)

        try {
            writer.write("Ani,Cost,Duration,Rate,LRN,ReRate,CostSavings,Carrier\n")
            fun writeRows(rows: List<CprReportData>) {
                for (i in 0 until rows.count()) {
                    with(rows[i]) {
                        writer.write("$rowAni,$cost,$duration,$rate,$lrn,${String.format("%.5f", reRate)},${String.format("%.5f", costSavings)},$carrier\n")
                    }
                }
            }

            val (firstItems, totalPages) = cprReportService.getCprData(reportId)
            writeRows(firstItems)
            // first Item
            if (totalPages >= 0) {
                for (i in 1 until totalPages) {
                    writeRows(cprReportService.getCprData(reportId, i).first)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            writer.flush()
            writer.close()
        }
    }

    @PostMapping("cpr_report/{id}/download_npanxx")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    fun downloadCprReportNpaNxxDataById(@PathVariable("id") reportId: Long, user: AppUser, response: HttpServletResponse) {
        response.contentType = "text/csv"
        response.setHeader("Content-Disposition", "attachment; filename=\"cpr_report_npanxx.csv\"")

        val writer = OutputStreamWriter(response.outputStream)

        try {
            writer.write("Lata,LRN/6digits,LCRVendor,LCRVendor/Rate\n")
            fun writeRows(rows: List<CprNpanxxReportData>) {
                for (i in 0 until rows.count()) {
                    with(rows[i]) {
                        writer.write("$lata,$npaNxx,$carrier rate,${String.format("%.5f", rate)}\n")
                    }
                }
            }

            val (firstItems, totalPages) = cprReportService.getCprNpaNxxsData(reportId)
            writeRows(firstItems)
            // first Item
            if (totalPages >= 0) {
                for (i in 1 until totalPages) {
                    writeRows(cprReportService.getCprNpaNxxsData(reportId, i).first)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            writer.flush()
            writer.close()
        }
    }

    @GetMapping("/cpr_report/{id}/summary")
    @ResponseBody
    fun getCPRReportSummary(@PathVariable("id") reportId: Long): ResponseEntity<CprReportSummary> {
        val report = cprReportService.findById(reportId) ?: throw BadRequestException("No report exist with $reportId")
        val summary = with(report) {
            CprReportSummary()
                    .averageRate(averageRate)
                    .defaultCarrier(defaultCarrier)
                    .defaultCarrierNpaNxx(defaultCarrierNpaNxx)
                    .totalCost(totalCost)
        }
        return ResponseEntity.ok(summary)
    }

    /**
     * Upload CDR File
     */
    @PostMapping("/cdr/upload")
    @PreAuthorize("hasAuthority('${Privilege.CDRImport}')")
    @ResponseBody
    fun uploadCDR(@RequestParam("file") file: MultipartFile, @RequestParam("delimiter") delimiter: String, user: AppUser): ResponseEntity<Any> {
        // Check if CdrImportInProgress is true
        if (appState.isCdrImportInProgress(user.id))
            throw BadRequestException("CDR Import in progress, Please wait a moment")

        if (appState.isCdrDipInProgress(user.id))
            throw BadRequestException("CDR Dip in progress, Please wait a moment")

        val originalFileName = file.originalFilename
        val ext = FilenameUtils.getExtension(originalFileName)

        val uploadDir = storageProperties.uploadDir ?: ""
        if (uploadDir.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BaseResponse("Upload directory is invalid."))
        }

        var reader: CSVReader? = null
        try {
            // Make directory
            File(uploadDir).mkdirs()
            // Generate file name
            val filename = "cdr_${FilenameUtils.getBaseName(originalFileName)
                    ?: "nonamed"}_(-)_${UUID.randomUUID()}.$ext"

            val save2File = File(uploadDir + File.separator + filename)
            file.transferTo(save2File)
            val separator = when (delimiter) {
                InsertRateRequest.DelimiterEnum.COMMA.value -> ','
                InsertRateRequest.DelimiterEnum.PIPE.value -> '|'
                InsertRateRequest.DelimiterEnum.SEMICOLON.value -> ';'
                InsertRateRequest.DelimiterEnum.TAB.value -> '\t'
                else -> throw BadRequestException("Delimiter not specified")
            }

            // Try open file
            reader = CSVReaderBuilder(FileReader(save2File))
                    .withCSVParser(
                            CSVParserBuilder()
                                    .withSeparator(separator)

                                    .build())
                    .build()

            val row = reader.readNext()

            val columns = mutableListOf<String>()

            // Support 20 columns
            for (i in 0..20) {
                columns.add(if (row.size > i) row[i] else "")
            }

            return ResponseEntity.ok(UploadCDRResponse().filename(filename).message("").columns(columns))
        } catch (ex: Exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(BaseResponse("Upload failed."))
        } finally {
            reader?.close()
        }
    }


    /**
     * Insert CDR
     */
    @PostMapping("/cdr/insert")
    @PreAuthorize("hasAuthority('${Privilege.RateImport}')")
    @ResponseBody
    fun insertCDR(@RequestBody r: InsertCDRRequest, user: AppUser): ResponseEntity<Any> {
        // Check if CdrImportInProgress is true
        if (appState.isCdrImportInProgress(user.id))
            throw BadRequestException("CDR Import in progress, Please wait a moment")

        if (appState.isCdrDipInProgress(user.id))
            throw BadRequestException("CDR Dip in progress, Please wait a moment")

        if (r.durationUnit != InsertCDRRequest.DurationUnitEnum.MINUTE && r.durationUnit != InsertCDRRequest.DurationUnitEnum.SECOND) {
            throw BadRequestException("Please select duration unit for this cdr file")
        }

        if (r.billingSecond != 1 && r.billingSecond != 6)
            throw BadRequestException("Please confirm your billing type")

        if (r.isWithLrn == null)
            throw BadRequestException("Please check filling mode")


        val uploadDir = storageProperties.uploadDir ?: ""
        val filePath = uploadDir + File.separator + r.filename

        // Check column indices

        val file = File(filePath)

        if (!file.exists() || !file.isFile || r.delimiter == null)
            return ResponseEntity.badRequest()
                    .body(BaseResponse("Uploaded file not found"))

        val separator = when (r.delimiter) {
            InsertCDRRequest.DelimiterEnum.COMMA -> ','
            InsertCDRRequest.DelimiterEnum.PIPE -> '|'
            InsertCDRRequest.DelimiterEnum.SEMICOLON -> ';'
            InsertCDRRequest.DelimiterEnum.TAB -> '\t'
            else -> throw BadRequestException("Delimiter not specified")
        }

        val userId = user.id
        // Create a new thread to perform cdr import
        var reader: CSVReader? = null
        Thread {
            var wholeCount = 0
            try {
                // Set AppState: CdrImportInProgress to true
                appState.setCdrImportInProgress(userId, true)

                // Build reader
                if (r.insertType == InsertCDRRequest.InsertTypeEnum.OVERWRITE) {
                    cdrDataService.deleteAllByUserId(userId)
                }

                val rowCount = getRowCount(file)

                reader = CSVReaderBuilder(FileReader(file))
                        .withCSVParser(
                                CSVParserBuilder()
                                        .withSeparator(separator)

                                        .build())
                        .build()

                progressEventService.push(user.id, ProgressEventCategory.cdrImport, wholeCount.toFloat() / rowCount * 0.01f, "Importing CDR Data...")
                val batchSize = jdbcBatchInsertSize
                val arr = ArrayList<String>()
                for (row in reader!!) {
                    // Skip first row
                    if (r.isHasColumnHeader) {
                        r.isHasColumnHeader = false
                        continue
                    }

                    val rowAni = r.rowAni?.let { if (row.size > it) row[r.rowAni].trim() else null } ?: ""

                    var duration = r.duration?.let { if (row.size > it) row[r.duration].trim() else null }?.toFloatOrNull()
                            ?: continue

                    // In case of minute, just multiply with 60, so all values are stored in seconds.
                    if (r.durationUnit == InsertCDRRequest.DurationUnitEnum.MINUTE)
                        duration *= 60

                    if (rowAni.isEmpty() || !rowAni.isNumeric() || rowAni.length < 10 || rowAni.startsWith("000000"))
                        continue

                    val prefix = rowAni.substring(0, 6)
                    val cost = r.cost?.let { if (row.size > it) row[r.cost].trim() else null }?.toFloatOrNull()
                    val rate = r.rate?.let { if (row.size > it) row[r.rate].trim() else null }?.toFloatOrNull()
                    var lrn = r.lrn?.let {
                        if (row.size > it) {
                            row[r.lrn].trim()
                        } else null
                    } ?: ""

                    // Three step import
                    arr.add("('$rowAni', $cost, $duration, '$lrn', $rate, $prefix)")

                    // When exceeded batch size,
                    if (arr.size >= batchSize) {
                        wholeCount += cdrDataService.insertBatchImproved(arr, userId)
                        arr.clear()
                        if (wholeCount % progressCount == 0)
                            progressEventService.push(user.id, ProgressEventCategory.cdrImport, wholeCount.toFloat() / rowCount * 0.95f, "Importing CDR Data...")
                    }
                }

                // If any padding (remaining) cdrs...
                if (arr.size > 0) {
                    wholeCount += cdrDataService.insertBatchImproved(arr, userId)
                }

                progressEventService.push(user.id, ProgressEventCategory.cdrImport, 0.95f, "Grouping CDR Data...")
                var notification = Notification().apply {
                    this.userId = userId
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = "Grouping CDR Data..."
                    description = ""
                    category = NotificationCategory.cprGenCdrImportDone
                }
                notificationService.save(notification)

                cdrDataService.validateCDR(userId)
                progressEventService.push(user.id, ProgressEventCategory.cdrImport, 1.0f, "Completed Importing CDR Data.")
                progressEventService.removeEventCategory(user.id, ProgressEventCategory.cdrImport)

                // fill npanxx automatically from self ani
                r.isWithLrn = if (r.isWithLrn == null) false else r.isWithLrn
                val message = if (r.isWithLrn) "Dipping CDR..." else "Filling NPANXX"
                progressEventService.push(user.id, ProgressEventCategory.cdrDip, 0.01f, message)

                if (r.isWithLrn) {
                    notification = Notification().apply {
                        this.userId = userId
                        section = Notification.SECTION_ADMIN
                        type = Notification.TYPE_INFO
                        this.message = message
                        description = ""
                        category = NotificationCategory.cprGenCdrImportDone
                    }
                    notificationService.save(notification)
                }

                cdrDataService.fillNpanxx(userId, r.isWithLrn, r.billingSecond)
                if (r.isWithLrn)
                    progressEventService.push(user.id, ProgressEventCategory.cdrDip, 1.0f, "Completed Dipping CDR Data.")

            } catch (ex: Exception) {
                ex.printStackTrace()
            } finally {
                // Close reader
                reader?.close()
                // Notification
                val notification = Notification().apply {
                    this.userId = userId
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = "CDR Import $wholeCount Record(s) done"
                    description = ""
                    category = NotificationCategory.cprGenCdrImportDone
                }
                // save imported file name to CdrDataFile table
                val fileName = r.filename.substring(4, r.filename.indexOf("_(-)_")) + "." + FilenameUtils.getExtension(r.filename)
                cdrDataService.registerImportedCdrFile(fileName, userId, r.billingSecond)

                // Set AppState: CdrImportInProgress to false
                appState.setCdrImportInProgress(userId, false)
                progressEventService.removeEventCategory(user.id, ProgressEventCategory.cdrDip)


                // Save notification will save notification to db and send to user subscribing to notification.
                notificationService.save(notification)
            }
        }.start()
        return ResponseEntity.ok(BaseResponse("CDR Import started"))
    }

    @PostMapping("/cdr/dip")
    @PreAuthorize("hasAnyAuthority('${Privilege.CDRImport}', '${Privilege.ViewCDR}')")
    @ResponseBody
    fun dipCDR(@RequestBody req: DipCDRRequest, user: AppUser): ResponseEntity<Any> {
        // Check if CdrImportInProgress is true
        if (appState.isCdrImportInProgress(user.id))
            throw BadRequestException("CDR Import in progress, Please wait a moment")

        if (appState.isCdrDipInProgress(user.id))
            throw BadRequestException("CDR Dip in progress, Please wait a moment")

        if (req.isWithLrn != true)
            throw BadRequestException("Please check if dip with LRN or Not")

        if (req.billingSecond != 1 && req.billingSecond != 6)
            throw BadRequestException("Please confirm your billing type")

        val userId = user.id
        Thread {
            // prepare cdr data for lata_npanxx_report_1
            var isFailed = false
            var description = ""
            try {
                appState.setCdrDipInProgress(userId, true)
                progressEventService.push(user.id, ProgressEventCategory.cdrDip, 0.01f, "Dipping CDR Data...")
                cdrDataService.fillNpanxx(userId, req.isWithLrn, req.billingSecond)
                progressEventService.push(user.id, ProgressEventCategory.cdrDip, 1.0f, "Completed Dipping CDR Data.")

            } catch (ex: Exception) {
                isFailed = true
                description = ex.message ?: ""
                ex.printStackTrace()
            } finally {
                // Notification
                val notification = Notification().apply {
                    this.userId = userId
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = if (!isFailed) "CDR dip was done" else "CDR dip was failed"
                    this.description = description
                    category = NotificationCategory.cprGenCdrDipDone
                }
                appState.setCdrDipInProgress(userId, false)
                progressEventService.removeEventCategory(user.id, ProgressEventCategory.cdrDip)

                // Save notification will save notification to db and send to user subscribing to notification.
                notificationService.save(notification)
            }
        }.start()
        return ResponseEntity.ok(BaseResponse("CDR Dip started"))
    }

    @PostMapping("/cdr/search")
    @PreAuthorize("hasAnyAuthority('${Privilege.CDRImport}', '${Privilege.ViewCDR}')")
    @ResponseBody
    fun searchCDR(@RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        // Check if CdrImportInProgress is true
        if (appState.isCdrImportInProgress(user.id))
            throw BadRequestException("CDR Import in progress, Please wait a moment")

        if (appState.isCdrDipInProgress(user.id))
            throw BadRequestException("CDR Dip in progress, Please wait a moment")

        return ResponseEntity.ok(cdrDataService.searchCDR(r, user.id))
    }

    @GetMapping("/cdr/info")
    @PreAuthorize("hasAnyAuthority('${Privilege.CDRImport}', '${Privilege.ViewCDR}')")
    @ResponseBody
    fun getCDRInfo(user: AppUser): ResponseEntity<List<ImportedCDRFileDTO>> {
        // Check if CdrImportInProgress is true
        if (appState.isCdrImportInProgress(user.id))
            return ResponseEntity.ok(cdrDataService.getImportedCdrFiles(user.id).map {
                ImportedCDRFileDTO().apply {
                }
            })

        if (appState.isCdrDipInProgress(user.id))
            return ResponseEntity.ok(cdrDataService.getImportedCdrFiles(user.id).map {
                ImportedCDRFileDTO().apply {
                }
            })

        return ResponseEntity.ok(cdrDataService.getImportedCdrFiles(user.id).map {
            ImportedCDRFileDTO().apply {
                name = it.fileName
            }
        })
    }

    @DeleteMapping("/cdr/deleteAll")
    @PreAuthorize("hasAnyAuthority('${Privilege.CDRImport}', '${Privilege.ViewCDR}')")
    @ResponseBody
    fun deleteAllCDRData(user: AppUser): ResponseEntity<Any> {
        // Check if CdrImportInProgress is true
        if (appState.isCdrImportInProgress(user.id))
            throw BadRequestException("CDR Import in progress, Please wait a moment")

        if (appState.isCdrDipInProgress(user.id))
            throw BadRequestException("CDR Dip in progress, Please wait a moment")

        try {
            cdrDataService.deleteAllByUserId(user.id)
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw BadRequestException("Deleting Failed")
        }
        return ResponseEntity.ok(BaseResponse("Deleted"))
    }

    // for Lata Npanxx Report 1

    /**
     * Lata Npanxx Report 1 List ( only id: name )
     */
    @GetMapping("/lata_npanxx_report_1/list")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun getLataNpanxxReport1List(user: AppUser): ResponseEntity<List<LataNpanxxReportDTO>> {
        return ResponseEntity.ok(lataNpanxxReport1Service.findLataNpanxxReport1sByUserId(user.id).map {
            LataNpanxxReportDTO().apply {
                id = it.id
                name = it.name
            }
        })
    }

    /**
     * Lata Npanxx Report 1 List Search
     */
    @PostMapping("/lata_npanxx_report_1/search")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLataNpanxxReport1(@RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lataNpanxxReport1Service.searchLataNpanxxReport1ByUserId(r, user.id))
    }

    /**
     * Create a new Lata Npanxx Report 1
     */
    @PostMapping("/lata_npanxx_report_1")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun generateLataNpanxxReport1(@RequestBody r: LataNpanxxReport1Request, user: AppUser): ResponseEntity<Any> {
        // Check if CdrImportInProgress is true
        if (appState.isCdrImportInProgress(user.id))
            throw BadRequestException("CDR Import in progress, Please wait a moment")

        if (appState.isCdrDipInProgress(user.id))
            throw BadRequestException("CDR Dip in progress, Please wait a moment")

        if (r.name?.isEmpty() != false) {
            throw BadRequestException("Please set name for new Lata Npanxx Report 1")
        }

        if (r.lcrReportId == null) {
            throw BadRequestException("Please select a LCR report for new Lata Npanxx Report 1")
        }

        if (r.defaultRate == null) {
            throw BadRequestException("Please set value of default rate for unrated codes")
        }

        if (lataNpanxxReport1Service.findLataNpanxxReport1(user.id, r.name) != null) {
            throw BadRequestException("The report with name '${r.name}' exists. Please try with anther name")
        }

        val lcrReport = lcrReportService.findLcrReportById(r.lcrReportId)

        if (lcrReport?.isDeleted != false)
            throw BadRequestException("Please select a valid LCR Report for new LataNpanxxReport 1")

        if (cdrDataService.run { getImportedCdrFiles(userId = user.id).isEmpty() }) {
            throw BadRequestException("There is no CDR data to use for new LataNpanxxReport 1")
        }

        val userId = user.id
        Thread {
            var isFailed = false
            var description = ""
            val newLataNpanxxReport1 = LataNpanxxReport1(r.name, userId)
            newLataNpanxxReport1.lcrReport = lcrReportService.findLcrReportById(r.lcrReportId)
            val importedCdrFiles = cdrDataService.getImportedCdrFiles(userId)
            val comparedFileNames = ArrayList<String>()
            importedCdrFiles.forEach {
                comparedFileNames.add(it.fileName)
            }
            newLataNpanxxReport1.comparedCdrFileNames = comparedFileNames.joinToString(", ")
            newLataNpanxxReport1.defaultRate = r.defaultRate
            lataNpanxxReport1Service.saveLataNpanxxReport1(newLataNpanxxReport1)

            try {
                progressEventService.push(userId, ProgressEventCategory.lataNpanxxReport1, 0.0f, "Generating LataNpanxxReport1...")
                lataNpanxxReport1Service.generateLataNpanxxReport1(userId, r.lcrReportId, r.name, newLataNpanxxReport1.id, r.defaultRate)
                progressEventService.push(userId, ProgressEventCategory.lataNpanxxReport1, 1.0f, "Completed Generating LataNpanxxReport1.")
            } catch (ex: Exception) {
                ex.printStackTrace()
                isFailed = true
                description = ex.message ?: ""
                newLataNpanxxReport1.isDeleted = true
                lataNpanxxReport1Service.saveLataNpanxxReport1(newLataNpanxxReport1)
            } finally {
                // Notification
                val notification = Notification().apply {
                    this.userId = userId
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = "LataNpanxxReport 1 '${r.name}' Generation was " + if (!isFailed) "done" else "failed"
                    this.description = description
                    category = NotificationCategory.cprGenLataNpaNxxReport1Done
                }
                progressEventService.removeEventCategory(userId, ProgressEventCategory.lataNpanxxReport1)
                // Save notification will save notification to db and send to user subscribing to notification.
                notificationService.save(notification)
            }
        }.start()
        return ResponseEntity.ok(BaseResponse("LataNpanxxReport 1 Generation started"))
    }

    /**
     * Lata Npanxx Report 1 Data view by id
     */
    @PostMapping("/lata_npanxx_report_1/{id}")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLataNpanxxReport1ValidDataById(@PathVariable("id") reportId: Long, @RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lataNpanxxReport1Service.searchLataNpanxxReport1DataByReportId(r, reportId))
    }

    /**
     * Lata Npanxx Report 1 Data Invalid view by id
     */
    @PostMapping("/lata_npanxx_report_1/{id}/invalid")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLataNpanxxReport1InvalidDataById(@PathVariable("id") reportId: Long, @RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lataNpanxxReport1Service.searchLataNpanxxReport1DataInvalidByReportId(r, reportId))
    }

    @DeleteMapping("/lata_npanxx_report_1/{id}")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun deleteLataNpanxxReport1ById(@PathVariable("id") reportId: Long, user: AppUser): ResponseEntity<Any> {
        lataNpanxxReport1Service.deleteLataNpanxxReport1DataByReportId(reportId)
        return ResponseEntity.ok(BaseResponse("Deleted"))
    }

    @PostMapping("/lata_npanxx_report_1/{id}/download")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    fun downloadLataNpanxxReport1DataById(@PathVariable("id") reportId: Long, user: AppUser, response: HttpServletResponse) {

        val lataNpanxxReport1 = lataNpanxxReport1Service.findLataNpanxxReport1ById(reportId)
                ?: throw BadRequestException("This data doesn't exist.")
        response.contentType = "text/csv"
        response.setHeader("Content-Disposition", "attachment; filename=\"LATA NPANXX Report 1.csv\"")

        val writer = OutputStreamWriter(response.outputStream)

        try {
            writer.write("STATE,LATA,NPANXX,CALLS,Total Duration(min),MIN_RATE,Total Cost,Carrier\n")
            fun writeRows(rows: List<LataNpanxxReport1Data>) {
                for (i in 0 until rows.count()) {
                    with(rows[i]) {
                        writer.write("${state ?: ""},${lata
                                ?: ""},$npaNxx,$calls,${String.format("%.1f", ceil(totalDuration / 6) / 10)},${String.format("%.5f", minRate)},${String.format("%.5f", totalCost)}.${minCarrier
                                ?: ""}\n")
                    }
                }
            }

            val (firstItems, totalPages) = lataNpanxxReport1Service.getLataNpanxxReport1Data(reportId)
            writeRows(firstItems)
            // first Item
            if (totalPages >= 0) {
                for (i in 1 until totalPages) {
                    writeRows(lataNpanxxReport1Service.getLataNpanxxReport1Data(reportId, i).first)
                }
            }
            if (!lataNpanxxReport1.isDeleted) {
                with(lataNpanxxReport1) {
                    writer.write(",,,,,,\n")
                    writer.write(",,,Report Name        ,${name ?: ""},,\n")
                    writer.write(",,,Total Duration(min),${String.format("%.1f", ceil(totalDuration / 6) / 10)},,\n")
                    writer.write(",,,Total   Cost ($)   ,${String.format("%.5f", totalCost)},,\n")
                    writer.write(",,,Average Cost ($/min),${String.format("%.9f", averageCost)},,\n")
                    writer.write(",,,Compared CDR files ,${comparedCdrFileNames ?: ""},,\n")
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            writer.flush()
            writer.close()
        }
    }

    // for Lata Npanxx Report 2
    /**
     * Lata Npanxx Report 2 List ( only id: name )
     */
    @GetMapping("/lata_npanxx_report_2/list")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun getLataNpanxxReport2List(user: AppUser): ResponseEntity<List<LataNpanxxReportDTO>> {
        return ResponseEntity.ok(lataNpanxxReport2Service.findLataNpanxxReport2sByUserId(user.id).map {
            LataNpanxxReportDTO().apply {
                id = it.id
                name = it.name
            }
        })
    }

    /**
     * Create a new Lata Npanxx Report 2
     */
    @PostMapping("/lata_npanxx_report_2")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun generateLataNpanxxReport2(@RequestBody r: LataNpanxxReport2Request, user: AppUser): ResponseEntity<Any> {

        if (r.name?.isNotEmpty() != true) {
            throw BadRequestException("Please set name for new LataNpanxx Report 2")
        }

        if (lataNpanxxReport2Service.findLataNpanxxReport2(user.id, r.name) != null) {
            throw BadRequestException("The report with name '${r.name}' exists. Please try with anther name")
        }

        if (r.lataNpanxxReport1Id == null) {
            throw BadRequestException("Please select a LataNpanxxReport 1 for new LataNpanxxReport 2")
        }

        val basedLataNpanxxReport1 = lataNpanxxReport1Service.findLataNpanxxReport1ById(r.lataNpanxxReport1Id)
        if (basedLataNpanxxReport1?.isDeleted != false)
            throw BadRequestException("Please select a valid LataNpanxxReport 1 for new LataNpanxxReport 2")

        val userId = user.id
        Thread {
            val newLataNpanxxReport2 = LataNpanxxReport2(r.name, user.id)
            with(newLataNpanxxReport2) {
                lataNpanxxReport1 = basedLataNpanxxReport1
            }
            lataNpanxxReport2Service.saveLataNpanxxReport2(newLataNpanxxReport2)

            var isFailed = false
            var description = ""
            try {
                progressEventService.push(userId, ProgressEventCategory.lataNpanxxReport2, 0.47f, "Generating LataNpanxxReport2...")
                lataNpanxxReport2Service.generateLataNpanxxReport2(userId, r.lataNpanxxReport1Id, newLataNpanxxReport2.id)
                progressEventService.push(userId, ProgressEventCategory.lataNpanxxReport2, 1.0f, "Completed Generating LataNpanxxReport2.")
            } catch (ex: Exception) {
                ex.printStackTrace()
                isFailed = true
                description = ex.message ?: ""

                newLataNpanxxReport2.isDeleted = true
                lataNpanxxReport2Service.saveLataNpanxxReport2(newLataNpanxxReport2)
                lataNpanxxReport2Service.deleteLataNpanxxReport2Data1ByReportId(newLataNpanxxReport2.id)
                lataNpanxxReport2Service.deleteLataNpanxxReport2Data2ByReportId(newLataNpanxxReport2.id)
                lataNpanxxReport2Service.deleteLataNpanxxReport2Data3ByReportId(newLataNpanxxReport2.id)
                lataNpanxxReport2Service.deleteLataNpanxxReport2Data4ByReportId(newLataNpanxxReport2.id)
            } finally {
                // Notification
                val notification = Notification().apply {
                    this.userId = userId
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = "LataNpanxxReport 2 '${r.name}' Generation was " + if (!isFailed) "done" else "failed"
                    this.description = description
                    category = NotificationCategory.cprGenLataNpaNxxReport2Done
                }
                progressEventService.removeEventCategory(userId, ProgressEventCategory.lataNpanxxReport2)
                // Save notification will save notification to db and send to user subscribing to notification.
                notificationService.save(notification)
            }
        }.start()
        return ResponseEntity.ok(BaseResponse("LataNpanxxReport 2 Generation Started"))
    }

    /**
     * Lata Npanxx Report 2 List Search
     */
    @PostMapping("/lata_npanxx_report_2/search")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLataNpanxxReport2(@RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lataNpanxxReport2Service.searchLataNpanxxReport2ByUserId(r, user.id))
    }

    @DeleteMapping("/lata_npanxx_report_2/{id}")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun deleteLataNpanxxReport2ById(@PathVariable("id") reportId: Long, user: AppUser): ResponseEntity<Any> {
        try {
            lataNpanxxReport2Service.deleteLataNpanxxReport2Data4ByReportId(reportId)
            lataNpanxxReport2Service.deleteLataNpanxxReport2Data3ByReportId(reportId)
            lataNpanxxReport2Service.deleteLataNpanxxReport2Data2ByReportId(reportId)
            lataNpanxxReport2Service.deleteLataNpanxxReport2Data1ByReportId(reportId)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return ResponseEntity.ok(BaseResponse("Deleted"))
    }

    /**
     * Lata Npanxx Report 2 Data view by id
     */
    @PostMapping("/lata_npanxx_report_2/view1/{id}")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLataNpanxxReport2Data1ById(@PathVariable("id") reportId: Long, @RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lataNpanxxReport2Service.searchLataNpanxxReport2Data1ByReportId(r, reportId))
    }

    @PostMapping("/lata_npanxx_report_2/view1/{id}/download")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    fun downloadLataNpanxxReport2Data1ById(@PathVariable("id") reportId: Long, user: AppUser, response: HttpServletResponse) {

        val lataNpanxxReport2 = lataNpanxxReport2Service.findLataNpanxxReport2ById(reportId)
                ?: throw BadRequestException("This data doesn't exist.")

        response.contentType = "text/csv"
        response.setHeader("Content-Disposition", "attachment; filename=\"${lataNpanxxReport2.name}(Default Carrier).csv\"")

        val writer = OutputStreamWriter(response.outputStream)

        try {
            writer.write("STATE,LATA,NPANXX,CALLS,Total Duration,Average Rate,Carrier\n")
            fun writeRows(rows: List<LataNpanxxReport2Data1>) {
                for (i in 0 until rows.count()) {
                    with(rows[i]) {
                        writer.write("${state ?: ""},${lata
                                ?: ""},$npaNxx,$calls,${String.format("%.1f", ceil(totalDuration / 6) / 10)},${String.format("%.7f", averageRate)}, ${minCarrier
                                ?: ""}\n")
                    }
                }
            }

            val (firstItems, totalPages) = lataNpanxxReport2Service.getLataNpanxxReport2Data1(reportId)
            writeRows(firstItems)
            // first Item
            if (totalPages >= 0) {
                for (i in 1 until totalPages) {
                    writeRows(lataNpanxxReport2Service.getLataNpanxxReport2Data1(reportId, i).first)
                }
            }

            // statistics
            if (!lataNpanxxReport2.isDeleted) {
                with(lataNpanxxReport2) {
                    writer.write(",,,,,,\n")
                    writer.write(",,,Report Name        ,${name ?: ""},,\n")
                    writer.write(",,,Default Carrier    ,${defaultCarrier ?: ""},,\n")
                    writer.write(",,,Total Duration(min),${String.format("%.1f", ceil(v1TotalDuration / 6) / 10)},,\n")
                    writer.write(",,,Total   Cost ($)   ,${String.format("%.5f", v1TotalCost)},,\n")
                    writer.write(",,,Average Cost ($/min),${String.format("%.9f", v1AverageCost)},,\n")
                }
            }

        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            writer.flush()
            writer.close()
        }
    }

    // for Lata Npanxx Report 2 View 2

    /**
     * Lata Npanxx Report 2 Data 2 view by id
     */
    @PostMapping("/lata_npanxx_report_2/view2/{id}")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLataNpanxxReport2Data2ById(@PathVariable("id") reportId: Long, @RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lataNpanxxReport2Service.searchLataNpanxxReport2Data2ByReportId(r, reportId))
    }

    @PostMapping("/lata_npanxx_report_2/view2/{id}/download")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    fun downloadLataNpanxxReport2Data2ById(@PathVariable("id") reportId: Long, user: AppUser, response: HttpServletResponse) {

        val lataNpanxxReport2 = lataNpanxxReport2Service.findLataNpanxxReport2ById(reportId)
                ?: throw BadRequestException("This data doesn't exist.")

        response.contentType = "text/csv"
        response.setHeader("Content-Disposition", "attachment; filename=\"${lataNpanxxReport2.name}(Others).csv\"")

        val writer = OutputStreamWriter(response.outputStream)

        try {
            writer.write("STATE,LATA,NPANXX,CALLS,Total Duration,Total Cost,Average Rate,Carrier\n")
            fun writeRows(rows: List<LataNpanxxReport2Data2>) {
                for (i in 0 until rows.count()) {
                    with(rows[i]) {
                        writer.write("${state ?: ""},${lata
                                ?: ""},$npaNxx,$calls,${String.format("%.1f", ceil(totalDuration / 6) / 10)},${String.format("%.5f", totalCost)},${String.format("%.7f", averageRate)},${minCarrier
                                ?: ""}\n")
                    }
                }
            }

            val (firstItems, totalPages) = lataNpanxxReport2Service.getLataNpanxxReport2Data2(reportId)
            writeRows(firstItems)
            // first Item
            if (totalPages >= 0) {
                for (i in 1 until totalPages) {
                    writeRows(lataNpanxxReport2Service.getLataNpanxxReport2Data2(reportId, i).first)
                }
            }

            // statistics
            if (!lataNpanxxReport2.isDeleted) {
                with(lataNpanxxReport2) {
                    writer.write(",,,,,,\n")
                    writer.write(",,,Report Name        ,${name ?: ""},,\n")
                    writer.write(",,,Second top carrier  ,${v2DefaultCarrier ?: ""},,\n")
                    writer.write(",,,Total Duration(min),${String.format("%.1f", ceil(v2TotalDuration / 6) / 10)},,\n")
                    writer.write(",,,Total   Cost ($)   ,${String.format("%.5f", v2TotalCost)},,\n")
                    writer.write(",,,Average Cost ($/min),${String.format("%.9f", v2AverageCost)},,\n")
//                    writer.write(",,,Carriers Detail     ,,,\n")
//                    v2CarriersDetail.split("|").map {
//                        writer.write(",,,,${it ?: ""}\n")
//                    }
                }
            }

        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            writer.flush()
            writer.close()
        }
    }

    /**
     * Lata Npanxx Report 2 Data 3 view by id
     */
    @PostMapping("/lata_npanxx_report_2/view3/{id}")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLataNpanxxReport2Data3ById(@PathVariable("id") reportId: Long, @RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lataNpanxxReport2Service.searchLataNpanxxReport2Data3ByReportId(r, reportId))
    }

    @PostMapping("/lata_npanxx_report_2/view3/{id}/download")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    fun downloadLataNpanxxReport2Data3ById(@PathVariable("id") reportId: Long, user: AppUser, response: HttpServletResponse) {

        val lataNpanxxReport2 = lataNpanxxReport2Service.findLataNpanxxReport2ById(reportId)
                ?: throw BadRequestException("This data doesn't exist.")

        response.contentType = "text/csv"
        response.setHeader("Content-Disposition", "attachment; filename=\"${lataNpanxxReport2.name}(Top 33k).csv\"")

        val writer = OutputStreamWriter(response.outputStream)

        try {
            writer.write("STATE,LATA,NPANXX,CALLS,Total Duration,Total Cost,Average Rate,Winning Carriers\n")
            fun writeRows(rows: List<LataNpanxxReport2Data3>) {
                for (i in 0 until rows.count()) {
                    with(rows[i]) {
                        writer.write("${state ?: ""},${lata
                                ?: ""},$npaNxx,$calls,${String.format("%.1f", ceil(totalDuration / 6) / 10)},${String.format("%.5f", totalCost)},${String.format("%.7f", averageRate)},${minCarrier
                                ?: ""}\n")
                    }
                }
            }

            val (firstItems, totalPages) = lataNpanxxReport2Service.getLataNpanxxReport2Data3(reportId)
            writeRows(firstItems)
            // first Item
            if (totalPages >= 0) {
                for (i in 1 until totalPages) {
                    writeRows(lataNpanxxReport2Service.getLataNpanxxReport2Data3(reportId, i).first)
                }
            }

            // statistics
            if (!lataNpanxxReport2.isDeleted) {
                with(lataNpanxxReport2) {
                    writer.write(",,,,,,\n")
                    writer.write(",,,Report Name                    ,${name ?: ""},,\n")
                    writer.write(",,,Winning Carriers (not default) ,${v3WinningCarriers ?: ""},,\n")
                    writer.write(",,,Total Duration(min)            ,${String.format("%.1f", ceil(v3TotalDuration / 6) / 10)},,\n")
                    writer.write(",,,Total   Cost ($)               ,${String.format("%.5f", v3TotalCost)},,\n")
                    writer.write(",,,Average Cost ($/min)           ,${String.format("%.9f", v3AverageCost)},,\n")
                }
            }

        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            writer.flush()
            writer.close()
        }
    }

    @PostMapping("/lata_npanxx_report_2/view4/{id}")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    @ResponseBody
    fun searchLataNpanxxReport2View4DataById(@PathVariable("id") reportId: Long, @RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(lataNpanxxReport2Service.searchLataNpanxxReport2Data4ByReportId(r, reportId))
    }

    @PostMapping("/lata_npanxx_report_2/view4/{id}/download")
    @PreAuthorize("hasAuthority('${Privilege.LCRReport}')")
    fun downloadLataNpanxxReport2View4DataById(@PathVariable("id") reportId: Long, user: AppUser, response: HttpServletResponse) {

        val lataNpanxxReport2 = lataNpanxxReport2Service.findLataNpanxxReport2ById(reportId)
                ?: throw BadRequestException("This data doesn't exist.")


        response.contentType = "text/csv"
        response.setHeader("Content-Disposition", "attachment; filename=\"${lataNpanxxReport2.name}(Final Result).csv\"")

        val writer = OutputStreamWriter(response.outputStream)

        try {
            writer.write("STATE,LATA,NPANXX,Total Duration,Total Cost,Average Rate,Carrier\n")
            fun writeRows(rows: List<LataNpanxxReport2Data4>) {
                for (i in 0 until rows.count()) {
                    with(rows[i]) {
                        writer.write("${state ?: ""},${lata
                                ?: ""},$npaNxx,${String.format("%.1f", ceil(totalDuration / 6) / 10)},${String.format("%.5f", totalCost)},${averageRate.alexFormat(0, 7)},${minCarrier
                                ?: ""}\n")
                    }
                }
            }

            val (firstItems, totalPages) = lataNpanxxReport2Service.getLataNpanxxReport2Data4(reportId)
            writeRows(firstItems)
            // first Item
            if (totalPages >= 0) {
                for (i in 1 until totalPages) {
                    writeRows(lataNpanxxReport2Service.getLataNpanxxReport2Data4(reportId, i).first)
                }
            }

            // statistics
            if (!lataNpanxxReport2.isDeleted) {
                with(lataNpanxxReport2) {
                    var details = v4OtherCarriersDetail.replace(",", "")
                    details = details.replace(":", ",")
                    details = details.replace("|", "\n,")
                    writer.write(",,,,\n")
                    writer.write("Report Name                          ,${name ?: ""},,\n")
                    writer.write(",Count,Carrier,Total Duration,Total Cost,Average Cost\n,")
                    writer.write(details)
                    writer.write("\nTotal,${v4TotalCount
                            ?: ""},,${(v4TotalDuration / 60.0f).alexFormat(1, 1, false)},${v4TotalCost
                            ?: ""},${v4AverageCost},\n")
                }
            }

        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            writer.flush()
            writer.close()
        }
    }

    /**
     * LAD Section
     */
    @PostMapping("/lad")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun buildLad(@RequestBody req: BuildLADRequest, user: AppUser): ResponseEntity<Any> {
        if (req.name.isNullOrEmpty())
            throw BadRequestException("Please set a name of new LAD.")
        if (req.secondReportId == null)
            throw BadRequestException("Please select a Lata/NpanxxReport2.")

        var isFailed = false
        val userId = user.id
        var description = ""
        try {
            ladService.buildLad(req.secondReportId, userId, req.name)
        } catch (e: Exception) {
            isFailed = true
            e.printStackTrace()
            description = e.message ?: "Unkown error"
        } finally {
            // Notification
            val notification = Notification().apply {
                this.userId = userId
                section = Notification.SECTION_ADMIN
                type = Notification.TYPE_INFO
                message = "LAD '${req.name}' Building was " + if (!isFailed) "done" else "failed"
                this.description = description
            }
            // Save notification will save notification to db and send to user subscribing to notification.
            notificationService.save(notification)
        }

        return ResponseEntity.ok(BaseResponse("Success"))
    }

    @PostMapping("/lad/search")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun searchLAD(@RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(ladService.searchLAD(r, user.id))
    }

    @GetMapping("/lad/{id}/sd")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun getSixDigit(@PathVariable("id") radId: Long, user: AppUser): ResponseEntity<ArrayList<LadItem>> {
        val dataMapList = ladService.getSixDigitById(radId, user.id)
        val result = ArrayList<LadItem>()
        dataMapList.forEach { mapItem ->
            val ladItem = LadItem()
            ladItem.label = mapItem["label"]?.toString() ?: return@forEach
            ladItem.definitions = mapItem["definitions"]?.toString() ?: ""
            result.add(ladItem)
        }
        return ResponseEntity.ok(result)
    }

    @GetMapping("/lad/{id}/lata")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun getLata(@PathVariable("id") radId: Long, user: AppUser): ResponseEntity<ArrayList<LadItem>> {
        val dataMapList = ladService.getLataById(radId, user.id)
        val result = ArrayList<LadItem>()
        dataMapList.forEach { mapItem ->
            val ladItem = LadItem()
            ladItem.label = mapItem["label"]?.toString() ?: return@forEach
            ladItem.definitions = mapItem["definitions"]?.toString() ?: ""
            result.add(ladItem)
        }
        return ResponseEntity.ok(result)
    }

    /**
     * New CPR Report Section
     */
    @PostMapping("/new_cpr_report")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    fun generateNewCprReport(@RequestBody req: BuildLADRequest, user: AppUser): ResponseEntity<Any> {
        if (req.name.isNullOrEmpty())
            throw BadRequestException("Please set a name of new CprReport.")
        if (req.secondReportId == null)
            throw BadRequestException("Please select a Lata/NpanxxReport2.")

        val userId = user.id
        Thread {
            val newCprReport = NewCprReport(req.name, user.id)
            newCprReport.lataNpanxxReport2Id = req.secondReportId
            newCprReportService.saveNewCprReport(newCprReport)

            var isFailed = false
            var description = ""

            try {
                progressEventService.push(userId, ProgressEventCategory.cprReport, 0.1f, "Generating CprReport...")
                newCprReportService.generateNewCprReport(userId, newCprReport.id, newCprReport.lataNpanxxReport2Id)
                progressEventService.push(userId, ProgressEventCategory.cprReport, 1.0f, "Completed Generating CprReport.")
            } catch (ex: Exception) {
                ex.printStackTrace()
                isFailed = true
                description = ex.message ?: ""

                newCprReport.isDeleted = true
                newCprReportService.saveNewCprReport(newCprReport)

            } finally {
                // Notification
                val notification = Notification().apply {
                    this.userId = userId
                    section = Notification.SECTION_ADMIN
                    type = Notification.TYPE_INFO
                    message = "CprReport '${req.name}' Generation was " + if (!isFailed) "done" else "failed"
                    this.description = description
                    category = NotificationCategory.cprGenLcrReportDone
                }
                progressEventService.removeEventCategory(userId, ProgressEventCategory.cprReport)
                // Save notification will save notification to db and send to user subscribing to notification.
                notificationService.save(notification)
            }
        }.start()
        return ResponseEntity.ok(BaseResponse("CprReport Generation started..."))
    }

    @PostMapping("/new_cpr_report/search")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun searchNewCprReport(@RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(newCprReportService.searchNewCprReportByUserId(r, user.id))
    }

    @DeleteMapping("/new_cpr_report/{id}")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun deleteNewCprReportById(@PathVariable("id") reportId: Long, user: AppUser): ResponseEntity<Any> {
        try {
            newCprReportService.deleteNewCprReportByReportId(reportId)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return ResponseEntity.ok(BaseResponse("Deleted"))
    }

    @PostMapping("/new_cpr_report/view1/{id}")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun searchNewCprReportData1ById(@PathVariable("id") reportId: Long, @RequestBody r: TableQuery, user: AppUser): ResponseEntity<TableResult> {
        return ResponseEntity.ok(newCprReportService.searchNewCprReportData1ByReportId(r, reportId))
    }

    @PostMapping("/new_cpr_report/view1/{id}/searchData")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    @ResponseBody
    fun searchNewCprReportData1(@PathVariable("id") reportId: Long, @RequestBody r: NewCprReportData1Request): ResponseEntity<NewCprReportData1Result> {
        val secondReportId = newCprReportService.findNewCprReportById(reportId)?.lataNpanxxReport2Id
                ?: throw BadRequestException("Please select a valid CPR Report")

        val secondReport = lataNpanxxReport2Service.findLataNpanxxReport2ById(secondReportId)
                ?: throw BadRequestException("Routing Report Data doesn't exist.")
        if (secondReport.isDeleted)
            throw BadRequestException("Routing Report Data of selected CPR report was deleted")

        return ResponseEntity.ok(newCprReportService.searchData1(secondReportId, r))
    }

    @PostMapping("/new_cpr_report/view1/{id}/download")
    @PreAuthorize("hasAuthority('${Privilege.CPRReport}')")
    fun downloadNewCprReportData1ById(@PathVariable("id") reportId: Long, @RequestParam("state") state: String, @RequestParam("lata") lata: String, @RequestParam("carrier") carrier: String, user: AppUser, response: HttpServletResponse) {
        if (state.isNullOrEmpty())
            throw BadRequestException("Please select a state")

        val newCprReport = newCprReportService.findNewCprReportById(reportId)
                ?: throw BadRequestException("This data doesn't exist.")

        response.contentType = "text/csv"
        response.setHeader("Content-Disposition", "attachment; filename=\"${newCprReport.name}.csv\"")

        val writer = OutputStreamWriter(response.outputStream)

        try {
            writer.write("lata,6-digit,cic\n")

            val stateQ = state.split("(")[0]
            val lataQ = if (!lata.isNullOrEmpty()) lata.split("(")[0] else ""
            val carrierQ = if (!carrier.isNullOrEmpty()) carrier.split("(")[0] else ""

            newCprReportService.getNewCprReportData1(newCprReport.lataNpanxxReport2Id, stateQ, lataQ, carrierQ).forEach {
                writer.write(it)
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        } finally {
            writer.flush()
            writer.close()
        }
    }


}