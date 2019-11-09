package com.digitalipvoice.cps.service

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.model.AppUser
import com.digitalipvoice.cps.persistance.dao.LcrReportDataRepository
import com.digitalipvoice.cps.persistance.dao.LcrReportRepository
import com.digitalipvoice.cps.persistance.dao.RateDeckDataRepository
import com.digitalipvoice.cps.persistance.dao.RateDeckRepository
import com.digitalipvoice.cps.persistance.model.LcrReport
import com.digitalipvoice.cps.persistance.model.LcrReportData
import com.digitalipvoice.cps.utils.findByIdKt
import com.digitalipvoice.cps.utils.logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.*
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext
import kotlin.collections.ArrayList

@Service
class LcrReportService {
    private val log = logger(javaClass)

    private val listTable = "lcr_report"
    private val dataTable = "lcr_report_data"

    @PersistenceContext
    private lateinit var em: EntityManager

    @Autowired
    private lateinit var lcrReportRepository: LcrReportRepository

    @Autowired
    private lateinit var lcrReportDataRepository: LcrReportDataRepository

    @Autowired
    private lateinit var rateDeckRepository: RateDeckRepository

    @Autowired
    private lateinit var rateDeckDataRepository: RateDeckDataRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    companion object {
        const val maxComparableCarriers = 5
    }

    /**
     * Find Report by Id
     */
    fun findLcrReportById(id: Long) = lcrReportRepository.findByIdKt(id)

    /**
     * Search Report List By UserId
     */
    fun searchLcrReportByUserId(query: TableQuery, userId: Long) = lcrReportRepository.searchLcrReportByUserId(query, userId)

    fun generateLcrReportByRateNamesNew(userId: Long, rateNames: List<String>, newReportId: Long = 0): Int {
        val rateDecks = rateNames.mapNotNull { rateDeckRepository.findByUserIdAndNameAndIsDeletedFalse(userId, it) }
        val carriers = rateDecks.map { "${it.carrier}" }


        val selectionQuery =
                if (rateDecks.size > 1)
                    createRateDeckMergeTable(newReportId, rateDecks.map { it.id }, carriers)
                else {
                    """
                        INSERT INTO $dataTable (lcr_report_id, npa_nxx, min_rate, carrier_1, min_carrier, lata, state)
                        SELECT
                            $newReportId,
                            lg.npa_nxx,
                            rdd.inter_rate,
                            rd.carrier,
                            rd.carrier,
                            lg.lata,
                            lg.state
                        FROM lerg_import lg
                        INNER JOIN rate_deck_data rdd ON rdd.npa_nxx = lg.npa_nxx
                        LEFT JOIN rate_deck rd ON rd.id = rdd.rate_deck_id
                        WHERE rdd.rate_deck_id = ${rateDecks[0].id} AND lg.npa_nxx IS NOT NULL;
                    """.trimMargin()
                }
        val ret = jdbcTemplate.update(selectionQuery)

        // for statistics
        var sqlStatistics = """
            SELECT
                min_carrier as carrier,
                COUNT(min_carrier) as count
            FROM $dataTable
            WHERE lcr_report_id = $newReportId
            GROUP BY min_carrier, lcr_report_id
            ORDER BY count DESC;
        """.trimIndent()

        val list = jdbcTemplate.queryForList(sqlStatistics)
        val descriptionStart = ArrayList<String>()
        var totalCount = 0L
        list.forEach {
            val carrier = it["carrier"]?.toString() ?: return@forEach
            val count = it["count"]?.toString()?.toLongOrNull() ?: 0
            totalCount += count
            descriptionStart.add("$carrier: $count")
        }
        val descriptionEnd = "  of total $totalCount NPANXX"
        val description = descriptionStart.joinToString(", ") + descriptionEnd

        val averageRate = try {
            jdbcTemplate.queryForObject("SELECT AVG(min_rate) FROM $dataTable WHERE lcr_report_id = $newReportId", Float::class.java)
        } catch (ex: Exception) {
            null
        } ?: 0.0f

        val temp1 = "lcr_report_temp_1_${userId}_$newReportId"
        val temp2 = "lcr_report_temp_2_${userId}_$newReportId"

        sqlStatistics = """
            CREATE TABLE IF NOT EXISTS $temp1
            SELECT DISTINCT
                lcr_report_id,
                min_carrier as carrier,
                COUNT(min_carrier) as count
            FROM $dataTable
            WHERE lcr_report_id = $newReportId
            GROUP BY min_carrier, lcr_report_id;
        """.trimIndent()
        jdbcTemplate.execute(sqlStatistics)
        sqlStatistics = """
            CREATE TABLE IF NOT EXISTS $temp2
            SELECT
                MAX(count) as max_count,
                SUM(count) as total_count
            FROM $temp1
            GROUP BY lcr_report_id;
        """.trimIndent()
        jdbcTemplate.execute(sqlStatistics)
        sqlStatistics = """
            UPDATE $listTable
            SET
                default_carrier =
                    (SELECT t1.carrier
                    FROM $temp1 t1
                    LEFT JOIN $temp2 t2 ON t1.count = t2.max_count
                    WHERE t1.count = t2.max_count),
                description = '$description',
                average_rate = $averageRate
            WHERE id = $newReportId;
        """.trimIndent()
        jdbcTemplate.execute(sqlStatistics)
        sqlStatistics = "DROP TABLE $temp1"
        jdbcTemplate.execute(sqlStatistics)
        sqlStatistics = "DROP TABLE $temp2"
        jdbcTemplate.execute(sqlStatistics)

        return ret
    }

    fun createRateDeckMergeTable(reportId: Long, ids: List<Long>, carriers: List<String>): String {
        // Table Creation Query for each rate deck
        val rateDeckDataTables = ids.map { "SELECT inter_rate as rate_1, npa_nxx FROM rate_deck_data WHERE rate_deck_id = $it" }

        fun unionTables(leftTable: String, rightTable: String, rightRateIndex: Int): String {
            val rateColumn = "rate_$rightRateIndex"
            val columns = (1 until rightRateIndex).joinToString(", ") { "l.rate_$it as rate_$it" }

            return """SELECT l.npa_nxx, $columns, r.rate_1 as $rateColumn FROM ($leftTable) l
                LEFT JOIN ($rightTable) r ON l.npa_nxx = r.npa_nxx
                UNION
                SELECT r.npa_nxx, $columns, r.rate_1 as $rateColumn FROM ($leftTable) l
                RIGHT JOIN ($rightTable) r ON l.npa_nxx = r.npa_nxx WHERE l.npa_nxx IS NULL
            """.trimMargin()
        }

        var tableQuery = unionTables(rateDeckDataTables[0], rateDeckDataTables[1], 2)
        if (rateDeckDataTables.size > 2)
            for (i in 2 until rateDeckDataTables.size) {
                tableQuery = unionTables(tableQuery, rateDeckDataTables[i], i + 1)
            }

        val strLeast = (1..ids.size).joinToString(", ", "LEAST (", ")") { "IF(t.rate_$it IS NULL, 99999, t.rate_$it)" }

        val carriersColumn = (1..maxComparableCarriers).joinToString(", ") { "carrier_$it" }


        val whenClauses = carriers.mapIndexed { index, value -> "WHEN $strLeast = t.rate_${index + 1} THEN '$value'" }

        val minCarrier = "(CASE ${whenClauses.joinToString(" ")} ELSE NULL END)"

        val carriersValues = (0 until maxComparableCarriers).map { i ->
            if (i < whenClauses.size) {
                val str = whenClauses.subList(i, whenClauses.size).joinToString(" ")
                "(CASE $str ELSE NULL END) as carrier_${i + 1}"
            } else {
                "NULL as carrier_${i + 1}"
            }
        }



        return """INSERT INTO lcr_report_data (lcr_report_id, npa_nxx, lata, state, min_rate, min_carrier, $carriersColumn)
             SELECT '$reportId', lg.npa_nxx, lg.lata, lg.state, $strLeast, $minCarrier, ${carriersValues.joinToString(", ")}
             FROM lerg_import lg
             INNER JOIN ($tableQuery) t ON t.npa_nxx = lg.npa_nxx
             WHERE lg.npa_nxx IS NOT NULL;
        """.trimMargin()
    }


    fun findLcrReport(userId: Long, name: String) = lcrReportRepository.findByUserIdAndNameAndIsDeletedFalse(userId, name)

    fun findLcrReportsByUserId(userId: Long) = lcrReportRepository.findAllByUserIdAndIsDeletedFalse(userId)

    fun saveLcrReport(report: LcrReport) = lcrReportRepository.saveAndFlush(report)

    // For Data
    /**
     * Search Reports
     */
    fun searchLcrReportData(query: TableQuery) = lcrReportDataRepository.searchLcrReportData(query)

    /**
     * search LcrReportData by report id
     */
    fun searchLcrReportDataByReportId(query: TableQuery, reportId: Long) = lcrReportDataRepository.searchLcrReportDataByReportId(query, reportId)

    /**
     * delete LcrReportData by report id
     */
    fun deleteLcrReportDataByReportId(reportId: Long): Int {
        lcrReportRepository.findByIdKt(reportId)?.let {
            it.isDeleted = true
            lcrReportRepository.save(it)
        }

        val sql = "DELETE FROM $dataTable WHERE lcr_report_id = $reportId ;"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while LcrReportData delete")
        }
        return 0
    }

    // Get page
    fun getLcrData(reportId: Long, page: Int = 0, pageSize: Int = 1000): Pair<List<LcrReportData>, Int> {
        val pageable = PageRequest.of(page, pageSize, Sort.by("npaNxx"))
        val result = lcrReportDataRepository.findAllByLcrReportId(reportId, pageable)
        return Pair(result.content, result.totalPages)
    }

}