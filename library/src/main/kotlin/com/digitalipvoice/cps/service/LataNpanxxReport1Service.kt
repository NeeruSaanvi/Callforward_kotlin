package com.digitalipvoice.cps.service

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.persistance.dao.*
import com.digitalipvoice.cps.persistance.model.LataNpanxxReport1
import com.digitalipvoice.cps.persistance.model.LataNpanxxReport1Data
import com.digitalipvoice.cps.utils.alexFormat
import com.digitalipvoice.cps.utils.findByIdKt
import com.digitalipvoice.cps.utils.logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext
import kotlin.math.ceil

@Service
class LataNpanxxReport1Service {
    private val log = logger(javaClass)

    private val listTable = "lata_npanxx_report_1"
    private val dataTable = "${listTable}_data"
    private val unratedDataTable = "${listTable}_data_invalid"

    @PersistenceContext
    private lateinit var em: EntityManager

    @Autowired
    private lateinit var lataNpanxxReport1Repository: LataNpanxxReport1Repository

    @Autowired
    private lateinit var lataNpanxxReport1DataRepository: LataNpanxxReport1DataRepository

    @Autowired
    private lateinit var lataNpanxxReport1DataInvalidRepository: LataNpanxxReport1DataInvalidRepository

    @Autowired
    private lateinit var lcrReportRepository: LcrReportRepository

    @Autowired
    private lateinit var lcrReportDataRepository: LcrReportDataRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    /**
     * Find Report by Id
     */
    fun findLataNpanxxReport1ById(id: Long) = lataNpanxxReport1Repository.findByIdKt(id)

    /**
     * Search Report List By UserId
     */
    fun searchLataNpanxxReport1ByUserId(query: TableQuery, userId: Long) = lataNpanxxReport1Repository.searchLataNpanxxReport1ByUserId(query, userId)

    fun findLataNpanxxReport1(userId: Long, name: String) = lataNpanxxReport1Repository.findByUserIdAndNameAndIsDeletedFalse(userId, name)

    fun findLataNpanxxReport1sByUserId(userId: Long) = lataNpanxxReport1Repository.findAllByUserIdAndIsDeletedFalse(userId)

    fun saveLataNpanxxReport1(report: LataNpanxxReport1) = lataNpanxxReport1Repository.saveAndFlush(report)


    // Generate Report
    fun generateLataNpanxxReport1(userId: Long, lcrReportId: Long, name: String, newLataNpanxxReport1Id: Long = 0, defaultRate: Float = 0.0f): Int {

        // report part 1 (valid codes)
        var sql = """
            INSERT INTO $dataTable (lata_npanxx_report_1_id, lata, state, npa_nxx, total_duration, calls, total_cost, min_rate, min_carrier)
            SELECT
                $newLataNpanxxReport1Id,
                lcrr.lata,
                lcrr.state,
                temp.*,
                temp.total_duration * lcrr.min_rate / 60,
                lcrr.min_rate,
                lcrr.min_carrier
            FROM (SELECT DISTINCT cdr.npa_nxx as npa_nxx,
                IF(SUM(cdr.total_duration) IS NULL,0,SUM(cdr.total_duration)) as total_duration,
                IF(SUM(cdr.calls) IS NULL OR IF(SUM(cdr.total_duration) IS NULL,0,SUM(cdr.total_duration))<0.00001,0,SUM(cdr.calls)) as calls
                FROM cdr_data_${userId}_groupby_ani cdr
                INNER JOIN lerg_import lerg ON lerg.npa_nxx = cdr.npa_nxx
                GROUP BY cdr.npa_nxx) temp
            INNER JOIN lcr_report_data lcrr ON lcrr.npa_nxx = temp.npa_nxx AND lcrr.lcr_report_id = $lcrReportId
            WHERE temp.npa_nxx IS NOT NULL;
        """.trimIndent()

        val ret = jdbcTemplate.update(sql)

        // for report level statistics
        sql = """
            UPDATE $listTable
            SET
                valid_npanxx_count = (
                    SELECT
                    COUNT(*)
                    FROM $dataTable
                    WHERE lata_npanxx_report_1_id = $newLataNpanxxReport1Id
                ),
                total_cost = (
                    SELECT
                    IF(SUM(total_cost) IS NULL,0,SUM(total_cost))
                    FROM $dataTable
                    WHERE lata_npanxx_report_1_id = $newLataNpanxxReport1Id
                ),
                total_duration = (
                    SELECT
                    IF(SUM(total_duration) IS NULL,0,SUM(total_duration))
                    FROM $dataTable
                    WHERE lata_npanxx_report_1_id = $newLataNpanxxReport1Id
                ),
                average_cost = IF(total_duration=0, 0, total_cost/total_duration*60)
            WHERE id = $newLataNpanxxReport1Id;
        """.trimMargin()

        jdbcTemplate.execute(sql)

        // un rated codes
        sql = """
            INSERT INTO $unratedDataTable (lata_npanxx_report_1_id, lata, state, npa_nxx, total_duration, calls, total_cost, default_rate)
            SELECT
                $newLataNpanxxReport1Id,
                temp.*,
                temp.total_duration * $defaultRate / 60,
                $defaultRate
            FROM (SELECT DISTINCT
                lerg.lata as lata,
                lerg.state as state,
                cdr.npa_nxx as npa_nxx,
                IF(SUM(cdr.total_duration) IS NULL,0,SUM(cdr.total_duration)) as total_duration,
                IF(SUM(cdr.calls) IS NULL OR IF(SUM(cdr.total_duration) IS NULL,0,SUM(cdr.total_duration))<0.00001,0,SUM(cdr.calls)) as calls
                FROM cdr_data_${userId}_groupby_ani cdr
                INNER JOIN lerg_import lerg ON lerg.npa_nxx = cdr.npa_nxx
                WHERE lerg.state IS NOT NULL
                GROUP BY cdr.npa_nxx) temp
            LEFT JOIN lcr_report_data lcrr ON lcrr.npa_nxx = temp.npa_nxx AND lcrr.lcr_report_id = $lcrReportId
            WHERE lcrr.min_carrier IS NULL;
        """.trimIndent()

        jdbcTemplate.update(sql)

        jdbcTemplate.execute(
                """
                    UPDATE $listTable
                    SET
                        invalid_npanxx_count = (
                            SELECT COUNT(*)
                            FROM $unratedDataTable
                            WHERE lata_npanxx_report_1_id = $newLataNpanxxReport1Id
                        ),
                    invalid_total_cost = (
                        SELECT
                        IF(SUM(total_cost) IS NULL,0,SUM(total_cost))
                        FROM $unratedDataTable
                        WHERE lata_npanxx_report_1_id = $newLataNpanxxReport1Id
                    ),
                    invalid_total_duration = (
                        SELECT
                        IF(SUM(total_duration) IS NULL,0,SUM(total_duration))
                        FROM $unratedDataTable
                        WHERE lata_npanxx_report_1_id = $newLataNpanxxReport1Id
                    )
                    WHERE id = $newLataNpanxxReport1Id
                """.trimIndent())

        // for view 3 for total
        sql = """
            SELECT DISTINCT min_carrier FROM $dataTable WHERE lata_npanxx_report_1_id = $newLataNpanxxReport1Id GROUP BY min_carrier
        """.trimIndent()
        val listCarriers = jdbcTemplate.queryForList(sql)
        val carriersDetail = ArrayList<String>()
        var allTotalDuration = 0.0
        var allTotalCost = 0.0
        // for rated Data
        listCarriers.forEach {
            val carrier = it["min_carrier"]?.toString() ?: return@forEach
            val count = try {
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM $dataTable WHERE min_carrier = '$carrier' AND lata_npanxx_report_1_id = $newLataNpanxxReport1Id ORDER BY SUM(total_duration) DESC",
                        Int::class.java
                )
            } catch (ex: Exception) {
                null
            } ?: 0
            sql = """
                SELECT SUM(total_duration) FROM $dataTable WHERE min_carrier = '$carrier' AND lata_npanxx_report_1_id = $newLataNpanxxReport1Id
            """.trimIndent()
            val totalDuration = try {
                jdbcTemplate.queryForObject(sql, Float::class.java)
            } catch (ex: Exception) {
                null
            } ?: 0.0f
            sql = """
                SELECT SUM(total_cost) FROM $dataTable WHERE min_carrier = '$carrier' AND lata_npanxx_report_1_id = $newLataNpanxxReport1Id
            """.trimIndent()
            val totalCost = try {
                jdbcTemplate.queryForObject(sql, Float::class.java)
            } catch (ex: Exception) {
                null
            } ?: 0.0f
            val averageCost = if (totalDuration != 0.0f) totalCost / totalDuration * 60 else 0.0f
            carriersDetail.add("$carrier:${count.alexFormat(0, 0)}:${(ceil(totalDuration / 6) / 10).alexFormat(1, 1)}:${totalCost.alexFormat(2, 2)}:${averageCost.alexFormat(5, 5)}")
            allTotalDuration += totalDuration
            allTotalCost += totalCost
        }
        // for unrated Data
        val newReport = lataNpanxxReport1Repository.findByIdKt(newLataNpanxxReport1Id) ?: return ret
        with(newReport) {
            allTotalDuration += invalidTotalDuration
            allTotalCost += invalidTotalCost
            carriersDetail.add("Un-Rated:${invalidNpanxxCount.alexFormat(0, 0)}:${(ceil(invalidTotalDuration / 6) / 10).alexFormat(1, 1)}:${invalidTotalCost.alexFormat(2, 2)}:${defaultRate.alexFormat(5, 5)}")
        }

        // for invalid code
        val invalidCodeTotalDuration = try {
            jdbcTemplate.queryForObject("SELECT total_duration FROM cdr_data_${userId}_groupby_ani WHERE npa_nxx = '000000'", Float::class.java)
        } catch (ex: Exception) {
            null
        } ?: 0.0f

        val invalidCodeTotalCost = invalidCodeTotalDuration * defaultRate / 60
        allTotalDuration += invalidCodeTotalDuration
        allTotalCost += invalidCodeTotalCost
        carriersDetail.add("Invalid:0:${(ceil(invalidCodeTotalDuration / 6) / 10).alexFormat(1, 1)}:${invalidCodeTotalCost.alexFormat(2,2)}:${defaultRate.alexFormat(5,5)}")


        val v3AverageCost = allTotalCost / allTotalDuration * 60
        sql = """
            UPDATE $listTable
            SET
                v3_total_count = ${newReport.validNpanxxCount + newReport.invalidNpanxxCount},
                v3_total_duration = $allTotalDuration,
                v3_total_cost = $allTotalCost,
                v3_average_cost = ${v3AverageCost.alexFormat(5, 0)},
                v3_carriers_detail = '${carriersDetail.joinToString("|")}'
            WHERE id = $newLataNpanxxReport1Id
        """.trimIndent()
        jdbcTemplate.execute(sql)

        return ret
    }


    /**
     * search LataNpanxxReport1 by report id
     */
    fun searchLataNpanxxReport1DataByReportId(query: TableQuery, reportId: Long) = lataNpanxxReport1DataRepository.searchLataNpanxxReport1DataByReportId(query, reportId)

    /**
     * search LataNpanxxReport1 invalid data by report id
     */
    fun searchLataNpanxxReport1DataInvalidByReportId(query: TableQuery, reportId: Long) = lataNpanxxReport1DataInvalidRepository.searchLataNpanxxReport1DataInvalidByReportId(query, reportId)

    /**
     * delete LataNpanxxReport1 by report id
     */
    fun deleteLataNpanxxReport1DataByReportId(reportId: Long): Int {
        lataNpanxxReport1Repository.findByIdKt(reportId)?.let {
            it.isDeleted = true
            lataNpanxxReport1Repository.save(it)
        }

        var sql = "DELETE FROM $dataTable WHERE lata_npanxx_report_1_id = $reportId ;"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while LataNpanxxReport1 delete")
        }
        sql = "DELETE FROM $unratedDataTable WHERE lata_npanxx_report_1_id = $reportId ;"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while LataNpanxxReport1 delete")
        }
        return 0
    }

    // Get page
    fun getLataNpanxxReport1Data(reportId: Long, page: Int = 0, pageSize: Int = 1000): Pair<List<LataNpanxxReport1Data>, Int> {
        val pageable = PageRequest.of(page, pageSize, Sort.by("npaNxx"))
        val result = lataNpanxxReport1DataRepository.findAllByLataNpanxxReport1Id(reportId, pageable)
        return Pair(result.content, result.totalPages)
    }

}