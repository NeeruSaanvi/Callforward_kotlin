package com.digitalipvoice.cps.components

import com.digitalipvoice.cps.utils.logger
import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.FileReader


@Component
class CronJobs {
    @Autowired
    private lateinit var appState: AppState

    @Value("\${lrn.path}")
    private lateinit var lrnFilePath: String


    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val log = logger(javaClass)

    // Every day 00:00 AM
    @Scheduled(cron = "0 0 0 * * *")
    fun refreshLRN(){
        if (appState.isLrnRefreshInProgress)
        {
            log.info("LRN Refresh in progress. please wait")
            return
        }
        appState.isLrnRefreshInProgress = true

        log.info("Starting lrn refresh...")

        var wholeCount = 0
        val batchSize = 100000

        var rows = Array(batchSize){""}

        try {
            // READ as CSV file and start uploading
            val reader = CSVReaderBuilder(FileReader(lrnFilePath))
                    .withCSVParser(
                            CSVParserBuilder()
                                    .withSeparator(',')
                                    .build())
                    .build()

            var cnt = 0

            for (row in reader) {
                if (row.size < 4) continue

                val did = row[0]
                val lrn = row[1]
                val ocn = row[2]
                val grtype = row[3]

                rows[cnt++] = "('${did}', '${lrn}', '${ocn}', '${grtype}')"

                if (cnt >= batchSize) {
                    wholeCount += insertBatch(rows, cnt)
                    cnt = 0
                }
            }

            if (cnt > 0) {
                wholeCount += insertBatch(rows, cnt)
            }

        }catch(ex: Exception) {
            ex.printStackTrace()
        }finally {
            appState.isLrnRefreshInProgress = false
            log.info("Total ${wholeCount} records inserted/updated.")
        }
    }

    private fun insertBatch(rows:Array<String>, count: Int) : Int {
        val buffer = StringBuffer("REPLACE INTO lrn_data (did, lrn, ocn, grtype) VALUES ")
        for (i in 0 until count){
            buffer.append(rows[i])
            if (i < count - 1) {
                buffer.append(",")
            }
        }
        try {
            return jdbcTemplate.update(buffer.toString())
        }catch(ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while batch insert")
        }
        return 0
    }

    fun lrnCounts(): Long{
        try {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lrn_data", Long::class.java) ?: 0
        }catch(ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while fetching lrn counts")
        }
        return 0
    }
}