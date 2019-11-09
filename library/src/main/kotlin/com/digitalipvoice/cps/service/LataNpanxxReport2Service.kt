package com.digitalipvoice.cps.service

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.persistance.dao.*
import com.digitalipvoice.cps.persistance.model.*
import com.digitalipvoice.cps.utils.alexFormat
import com.digitalipvoice.cps.utils.findByIdKt
import com.digitalipvoice.cps.utils.logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import kotlin.math.ceil

@Service
class LataNpanxxReport2Service {
    private val log = logger(javaClass)

    private val listTable = "lata_npanxx_report_2"
    private val dataTable1 = "${listTable}_data_1"
    private val dataTable2 = "${listTable}_data_2"
    private val dataTable3 = "${listTable}_data_3"
    private val dataTable4 = "${listTable}_data_4"

    @Autowired
    private lateinit var lataNpanxxReport1Repository: LataNpanxxReport1Repository

    @Autowired
    private lateinit var lataNpanxxReport2Repository: LataNpanxxReport2Repository

    @Autowired
    private lateinit var lataNpanxxReport2Data1Repository: LataNpanxxReport2Data1Repository

    @Autowired
    private lateinit var lataNpanxxReport2Data2Repository: LataNpanxxReport2Data2Repository

    @Autowired
    private lateinit var lataNpanxxReport2Data3Repository: LataNpanxxReport2Data3Repository

    @Autowired
    private lateinit var lataNpanxxReport2Data4Repository: LataNpanxxReport2Data4Repository

    @Autowired
    private lateinit var rateDeckRepository: RateDeckRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    /**
     * Find Report by Id
     */
    fun findLataNpanxxReport2ById(id: Long) = lataNpanxxReport2Repository.findByIdKt(id)

    fun findLataNpanxxReport2sByUserId(userId: Long) = lataNpanxxReport2Repository.findAllByUserIdAndIsDeletedFalse(userId)

    /**
     * Search Report List By UserId
     */
    fun searchLataNpanxxReport2ByUserId(query: TableQuery, userId: Long) = lataNpanxxReport2Repository.searchLataNpanxxReport2ByUserId(query, userId)

    fun generateLataNpanxxReport2(userId: Long, lataNpanxxReport1Id: Long, lataNpanxxReport2Id: Long): Int {
        var ret = 0
        ret += generateLataNpanxxReport2View1(userId, lataNpanxxReport1Id, lataNpanxxReport2Id)
        ret += generateLataNpanxxReport2View2(userId, lataNpanxxReport1Id, lataNpanxxReport2Id)
        ret += generateLataNpanxxReport2View3(userId, lataNpanxxReport1Id, lataNpanxxReport2Id)
        ret += generateLataNpanxxReport2View4(userId, lataNpanxxReport1Id, lataNpanxxReport2Id)
        return ret
    }

    fun generateLataNpanxxReport2View1(userId: Long, lataNpanxxReport1Id: Long, lataNpanxxReport2Id: Long): Int {
        var ret = 0
        // get default carrier
        var sql = """
            SELECT
                t.carrier
            FROM
                (SELECT DISTINCT
                    min_carrier as carrier,
                    COUNT(min_carrier) as count
                FROM lata_npanxx_report_1_data
                WHERE lata_npanxx_report_1_id = $lataNpanxxReport1Id
                GROUP BY min_carrier) t
                ORDER BY t.count DESC
                LIMIT 1
        """.trimIndent()
        val defaultCarrier = try {
            jdbcTemplate.queryForObject(sql, String::class.java)
        } catch (ex: Exception) {
            null
        } ?: "NONE"

        log.error("LNR2_V1: DEFAULT_CARRIER=$defaultCarrier")
        // insert valid codes
        sql = """
            INSERT INTO lata_npanxx_report_2_data_1 (npa_nxx, lata_npanxx_report_2_id, lata, state, calls, total_duration, total_cost, average_rate, min_carrier, is_rated)
            SELECT
                npa_nxx,
                $lataNpanxxReport2Id,
                lata,
                state,
                calls,
                total_duration,
                total_cost,
                total_cost/total_duration*60,
                min_carrier,
                0
            FROM lata_npanxx_report_1_data
            WHERE lata_npanxx_report_1_id = $lataNpanxxReport1Id AND min_carrier = '$defaultCarrier'
        """.trimIndent()
        ret = jdbcTemplate.update(sql)

        sql = """
            UPDATE $listTable
            SET default_carrier = '$defaultCarrier',
                default_carrier_total_duration = (
                    SELECT IF(total_duration IS NULL,0,total_duration)
                    FROM lata_npanxx_report_2_data_1
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                    ORDER BY total_duration DESC
                    LIMIT 1),
                default_carrier_total_cost = (
                    SELECT IF(total_cost IS NULL,0,total_duration)
                    FROM lata_npanxx_report_2_data_1
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                    ORDER BY total_duration DESC
                    LIMIT 1
                ),
                default_carrier_average_rate = (
                    SELECT IF(average_rate IS NULL,0,total_duration)
                    FROM lata_npanxx_report_2_data_1
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                    ORDER BY total_duration DESC
                    LIMIT 1
                ),
                default_carrier_npa_nxx = (
                    SELECT npa_nxx
                    FROM lata_npanxx_report_2_data_1
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                    ORDER BY total_duration DESC
                    LIMIT 1
                ),
                default_carrier_count = (
                    SELECT COUNT(*)
                    FROM lata_npanxx_report_2_data_1
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND min_carrier = '$defaultCarrier'
                ),
                v1_total_duration = (
                    SELECT IF(SUM(total_duration) IS NULL,0,SUM(total_duration))
                    FROM lata_npanxx_report_2_data_1
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND min_carrier = '$defaultCarrier'
                ),
                v1_total_cost = (
                    SELECT IF(SUM(total_cost) IS NULL,0,SUM(total_cost))
                    FROM lata_npanxx_report_2_data_1
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND min_carrier = '$defaultCarrier'
                ),
                v1_average_cost = (
                    SELECT IF(SUM(total_cost) IS NULL OR SUM(total_duration) IS NULL OR SUM(total_duration) = 0,0,SUM(total_cost)/SUM(total_duration)*60)
                    FROM lata_npanxx_report_2_data_1
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND min_carrier = '$defaultCarrier'
                )
            WHERE id = $lataNpanxxReport2Id;
        """.trimIndent()
        jdbcTemplate.update(sql)

        sql = """
            INSERT INTO lata_npanxx_report_2_data_1 (npa_nxx, lata_npanxx_report_2_id, lata, state, calls, total_duration, total_cost, average_rate, min_carrier, is_rated)
            SELECT
                npa_nxx,
                $lataNpanxxReport2Id,
                '',
                '',
                calls,
                total_duration,
                total_cost,
                default_rate,
                '$defaultCarrier',
                1
            FROM lata_npanxx_report_1_data_invalid
            WHERE lata_npanxx_report_1_id = $lataNpanxxReport1Id
        """.trimIndent()
        ret += jdbcTemplate.update(sql)

        return ret
    }

    fun generateLataNpanxxReport2View2(userId: Long, lataNpanxxReport1Id: Long, lataNpanxxReport2Id: Long): Int {
        var ret = 0
        val defaultCarrier = lataNpanxxReport2Repository.findByIdKt(lataNpanxxReport2Id)?.defaultCarrier ?: "UNDIFINED"
        // insert records
        var sql = """
            INSERT INTO $dataTable2 (npa_nxx, lata_npanxx_report_2_id, lata, state, calls, total_duration, total_cost, average_rate, min_carrier)
            SELECT
                npa_nxx,
                $lataNpanxxReport2Id,
                lata,
                state,
                calls,
                total_duration,
                total_cost,
                total_cost/total_duration*60,
                min_carrier
            FROM lata_npanxx_report_1_data
            WHERE lata_npanxx_report_1_id = $lataNpanxxReport1Id AND min_carrier <> '$defaultCarrier';
        """.trimIndent()

        ret = jdbcTemplate.update(sql)

        // get default carrier, total cost, total duration, average cost
        sql = """
            UPDATE $listTable
            SET v2_default_carrier = (
                SELECT min_carrier
                FROM $dataTable2
                WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                ORDER BY total_duration DESC
                LIMIT 1)
            WHERE id = $lataNpanxxReport2Id
            """.trimIndent()
        jdbcTemplate.execute(sql)

        val v2DefaultCarrier = lataNpanxxReport2Repository.findByIdKt(lataNpanxxReport2Id)?.v2DefaultCarrier
                ?: "UNDIFINED"

        // statistics
        val sqlStatistics = """
            SELECT DISTINCT
                min_carrier as carrier,
                COUNT(min_carrier) as count
            FROM $dataTable2
            WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
            GROUP BY min_carrier, lata_npanxx_report_2_id
            ORDER BY count DESC;
        """.trimIndent()

        val list = jdbcTemplate.queryForList(sqlStatistics)
        val descriptionStart = ArrayList<String>()
        var totalCount = 0L
        list.forEach {
            val carrier = it["carrier"]?.toString() ?: return@forEach
            val count = it["count"]?.toString()?.toLongOrNull() ?: 0
            totalCount += count
            descriptionStart.add("$carrier: ${count.alexFormat(0, 0)}")
        }
        val descriptionEnd = "  of total $totalCount NPANXX"
        val description = descriptionStart.joinToString(", ") + descriptionEnd

        log.error("LNR2_V2: DESCRIPTION=$description")

        sql = """
            UPDATE $listTable
            SET v2_default_carrier_average_rate = (
                    SELECT IF(SUM(total_cost)/SUM(total_duration)*60 IS NULL,0,SUM(total_cost)/SUM(total_duration)*60)
                    FROM $dataTable2
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND min_carrier = '$v2DefaultCarrier'
                ),
                v2_default_carrier_total_duration = (
                    SELECT IF(SUM(total_duration) IS NULL,0,SUM(total_duration))
                    FROM $dataTable2
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND min_carrier = '$v2DefaultCarrier'
                ),
                v2_default_carrier_total_cost = (
                    SELECT IF(SUM(total_cost) IS NULL,0,SUM(total_cost))
                    FROM $dataTable2
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND min_carrier = '$v2DefaultCarrier'
                ),
                v2_average_cost = (
                    SELECT IF(SUM(total_cost)/SUM(total_duration)*60 IS NULL,0,SUM(total_cost)/SUM(total_duration)*60)
                    FROM $dataTable2
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                ),
                v2_total_duration = (
                    SELECT IF(SUM(total_duration) IS NULL,0,SUM(total_duration))
                    FROM $dataTable2
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                ),
                v2_total_cost = (
                    SELECT IF(SUM(total_cost) IS NULL,0,SUM(total_cost))
                    FROM $dataTable2
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                ),
                v2_carriers_detail = '$description'
            WHERE id = $lataNpanxxReport2Id;
        """.trimIndent()
        jdbcTemplate.update(sql)
        return ret
    }

    fun generateLataNpanxxReport2View3(userId: Long, lataNpanxxReport1Id: Long, lataNpanxxReport2Id: Long): Int {
        // insert records
        var sql = """
            INSERT INTO $dataTable3 (npa_nxx, lata_npanxx_report_2_id, lata, state, calls, total_duration, total_cost, average_rate, min_carrier)
            SELECT
                npa_nxx,
                $lataNpanxxReport2Id,
                lata,
                state,
                calls,
                total_duration,
                total_cost,
                total_cost/total_duration*60,
                min_carrier
            FROM $dataTable2
            WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
            ORDER BY total_duration DESC, npa_nxx ASC
            LIMIT 33000
        """.trimIndent()

        val ret = jdbcTemplate.update(sql)

        // get default carrier, total cost, total duration, average cost
        sql = """
            UPDATE $listTable
            SET
                v3_average_cost = (
                    SELECT IF(SUM(total_cost)/SUM(total_duration)*60 IS NULL,0,SUM(total_cost)/SUM(total_duration)*60)
                    FROM $dataTable3
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                ),
                v3_total_duration = (
                    SELECT IF(SUM(total_duration) IS NULL,0,SUM(total_duration))
                    FROM $dataTable3
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                ),
                v3_total_cost = (
                    SELECT IF(SUM(total_cost) IS NULL,0,SUM(total_cost))
                    FROM $dataTable3
                    WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
                )
            WHERE id = $lataNpanxxReport2Id;
        """.trimIndent()
        jdbcTemplate.execute(sql)

        // for statistics
        var sqlStatistics = """
            SELECT DISTINCT
                min_carrier as carrier,
                COUNT(min_carrier) as count
            FROM $dataTable3
            WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
            GROUP BY min_carrier, lata_npanxx_report_2_id
            ORDER BY count DESC;
        """.trimIndent()

        val list = jdbcTemplate.queryForList(sqlStatistics)
        val descriptionStart = ArrayList<String>()
        var totalCount = 0L
        list.forEach {
            val carrier = it["carrier"]?.toString() ?: return@forEach
            val count = it["count"]?.toString()?.toLongOrNull() ?: 0
            totalCount += count
            descriptionStart.add("$carrier: ${count.alexFormat(0, 0)}")
        }
        val descriptionEnd = "  of total $totalCount NPANXX"
        val description = descriptionStart.joinToString(", ") + descriptionEnd

        log.error("LNR2_V3: DESCRIPTION=$description")

        sqlStatistics = """
            UPDATE $listTable
            SET v3_winning_carriers = '$description'
            WHERE id = $lataNpanxxReport2Id;
        """.trimIndent()
        jdbcTemplate.execute(sqlStatistics)

        return ret
    }

    fun generateLataNpanxxReport2View4(userId: Long, lataNpanxxReport1Id: Long, lataNpanxxReport2Id: Long): Int {
        log.error("LNR2_V4: 1")

        val lataNpanxxReport2 = lataNpanxxReport2Repository.findByIdKt(lataNpanxxReport2Id)
        val defaultCarrier = lataNpanxxReport2!!.defaultCarrier

        var ret = 0

        // codes of default carrier
        var sql = """
            INSERT INTO $dataTable4 (lata, state, npa_nxx, lata_npanxx_report_2_id, total_duration, total_cost, average_rate, min_carrier)
            SELECT
                lata,
                state,
                npa_nxx,
                $lataNpanxxReport2Id,
                total_duration,
                total_cost,
                average_rate,
                min_carrier
            FROM $dataTable1
            WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
        """.trimIndent()
        ret += jdbcTemplate.update(sql)

        log.error("LNR2_V4: 2")
        val t2Count = try {
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $dataTable2 WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id", Int::class.java)
        } catch (ex: Exception) {
            null
        } ?: 0
        val t3Count = try {
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $dataTable3 WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id", Int::class.java)
        } catch (ex: Exception) {
            null
        } ?: 0

        log.error("LNR2_V4: T2_Count = $t2Count, T3_Count: $t3Count")
        // insert remaining codes except top 33K codes, but they will be re-rated with default carrier's
        log.error("LNR2_V4: defaultCarrier=$defaultCarrier, userId=$userId")
        val rateDeck = rateDeckRepository.findFirstByUserIdAndCarrierAndIsDeletedFalse(userId, defaultCarrier)
        val rateDeckId = rateDeck?.id ?: 0
        val defaultRate = rateDeck?.defaultRate ?: 0.0f
        log.error("LNR2_V4: RateDeck  id = $rateDeckId, defaultRate: $defaultRate")

        if (t2Count - t3Count > 0) {
            sql = """
            INSERT INTO $dataTable4 (lata, state, npa_nxx, lata_npanxx_report_2_id, total_duration, total_cost, average_rate, min_carrier)
            SELECT
                t.lata,
                t.state,
                t.npa_nxx,
                $lataNpanxxReport2Id,
                t.total_duration,
                IF(rdd.npa_nxx IS NULL, $defaultRate, rdd.inter_rate) * t.total_duration / 60,
                IF(rdd.npa_nxx IS NULL, $defaultRate, rdd.inter_rate),
                '$defaultCarrier'
            FROM
            ( SELECT
                lata,
                state,
                npa_nxx,
                total_duration
            FROM $dataTable2
            WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
            ORDER BY total_duration DESC, npa_nxx ASC
            LIMIT $t3Count, ${t2Count - t3Count}) t
            LEFT JOIN rate_deck_data rdd ON rdd.rate_deck_id = $rateDeckId AND rdd.npa_nxx = t.npa_nxx
            WHERE rdd.npa_nxx IS NOT NULL
            """.trimIndent()
            try {
                ret += jdbcTemplate.update(sql)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }

        }
        log.error("LNR2_V4: 3")
        // top 33K codes
        sql = """
            INSERT INTO $dataTable4 (lata, state, npa_nxx, lata_npanxx_report_2_id, total_duration, total_cost, average_rate, min_carrier)
            SELECT
                lata,
                state,
                npa_nxx,
                $lataNpanxxReport2Id,
                total_duration,
                total_cost,
                average_rate,
                min_carrier
            FROM $dataTable3
            WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id
        """.trimIndent()
        ret += jdbcTemplate.update(sql)
        log.error("LNR2_V4: 4")

        // for list values...
        sql = """
            SELECT DISTINCT min_carrier FROM $dataTable4 WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id GROUP BY min_carrier
        """.trimIndent()
        val listCarriers = jdbcTemplate.queryForList(sql)

        log.error("LNR2_V4: 5")

        val otherCarriersDetail = ArrayList<String>()
        var allTotalDuration = 0.0
        var allTotalCost = 0.0
        var allAverageCost = 0.0
        listCarriers.forEach {
            val carrier = it["min_carrier"]?.toString() ?: return@forEach
            val count = try {
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM $dataTable4 WHERE min_carrier = '$carrier' AND lata_npanxx_report_2_id = $lataNpanxxReport2Id",
                        Int::class.java
                )
            } catch (ex: Exception) {
                null
            } ?: 0
            sql = """
                SELECT SUM(total_duration) FROM $dataTable4 WHERE min_carrier = '$carrier' AND lata_npanxx_report_2_id = $lataNpanxxReport2Id
            """.trimIndent()
            val totalDuration = try {
                jdbcTemplate.queryForObject(sql, Float::class.java)
            } catch (ex: Exception) {
                null
            } ?: 0.0f
            sql = """
                SELECT SUM(total_cost) FROM $dataTable4 WHERE min_carrier = '$carrier' AND lata_npanxx_report_2_id = $lataNpanxxReport2Id
            """.trimIndent()
            val totalCost = try {
                jdbcTemplate.queryForObject(sql, Float::class.java)
            } catch (ex: Exception) {
                null
            } ?: 0.0f
            val averageCost = if (totalDuration != 0.0f) totalCost / totalDuration * 60 else 0.0f
            otherCarriersDetail.add("$count:$carrier:${(ceil(totalDuration / 6) / 10).alexFormat(1, 1)}:${totalCost.alexFormat(2, 2)}:${averageCost.alexFormat(5, 5)}")
            allTotalDuration += totalDuration
            allTotalCost += totalCost
        }
        log.error("LNR2_V4: CARRIERS_DETAIL=${otherCarriersDetail.joinToString("\n")}")

        allAverageCost = if (allTotalDuration != 0.0) allTotalCost / allTotalDuration * 60 else 0.0

        sql = """
            UPDATE $listTable
            SET
                v4_total_duration = $allTotalDuration,
                v4_total_cost = $allTotalCost,
                v4_average_cost = $allAverageCost,
                v4_difference_total_duration = (
                    SELECT IF(v3_total_duration IS NULL, 0, v3_total_duration)-$allTotalDuration FROM lata_npanxx_report_1 WHERE id = $lataNpanxxReport1Id
                ),
                v4_difference_total_cost = (
                    SELECT v3_total_cost-$allTotalCost FROM lata_npanxx_report_1 WHERE id = $lataNpanxxReport1Id
                ),
                v4_difference_average_cost = (
                    SELECT v3_average_cost-$allAverageCost FROM lata_npanxx_report_1 WHERE id = $lataNpanxxReport1Id
                ),
                v4_other_carriers_detail = '${otherCarriersDetail.joinToString("|")}'
            WHERE id = $lataNpanxxReport2Id
        """.trimIndent()
        log.error("LNR2_V4: LIST_UPDATE=$sql")
        jdbcTemplate.update(sql)

        val t1Count = try {
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $dataTable1 WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id", Int::class.java)
        } catch (ex: Exception) {
            null
        } ?: 0
        val t4Count = try {
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $dataTable4 WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id", Int::class.java)
        } catch (ex: Exception) {
            null
        } ?: 0

        sql = "UPDATE $listTable SET v4_total_count = $t4Count WHERE id = $lataNpanxxReport2Id"
        jdbcTemplate.execute(sql)
        return ret
    }

    fun findLataNpanxxReport2(userId: Long, name: String) = lataNpanxxReport2Repository.findByUserIdAndNameAndIsDeletedFalse(userId, name)

    fun saveLataNpanxxReport2(report: LataNpanxxReport2) = lataNpanxxReport2Repository.saveAndFlush(report)


    // For Data
    /**
     * search LataNpanxxReport2 by report id
     */
    fun searchLataNpanxxReport2Data1ByReportId(query: TableQuery, reportId: Long) = lataNpanxxReport2Data1Repository.searchLataNpanxxReport2Data1ByReportId(query, reportId)

    fun searchLataNpanxxReport2Data2ByReportId(query: TableQuery, reportId: Long) = lataNpanxxReport2Data2Repository.searchLataNpanxxReport2Data2ByReportId(query, reportId)

    fun searchLataNpanxxReport2Data3ByReportId(query: TableQuery, reportId: Long) = lataNpanxxReport2Data3Repository.searchLataNpanxxReport2Data3ByReportId(query, reportId)

    fun searchLataNpanxxReport2Data4ByReportId(query: TableQuery, reportId: Long) = lataNpanxxReport2Data4Repository.searchLataNpanxxReport2Data4ByReportId(query, reportId)

    /**
     * delete LataNpanxxReport2 by report id
     */
    fun deleteLataNpanxxReport2Data1ByReportId(reportId: Long): Int {
        lataNpanxxReport2Repository.findByIdKt(reportId)?.let {
            it.isDeleted = true
            lataNpanxxReport2Repository.save(it)
        }

        val sql = "DELETE FROM $dataTable1 WHERE lata_npanxx_report_2_id = $reportId ;"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while LataNpanxxReport2 delete")
        }
        return 0
    }

    fun deleteLataNpanxxReport2Data2ByReportId(reportId: Long): Int {
        val sql = "DELETE FROM $dataTable2 WHERE lata_npanxx_report_2_id = $reportId ;"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while LataNpanxxReport 2 data 2delete")
        }
        return 0
    }

    fun deleteLataNpanxxReport2Data3ByReportId(reportId: Long): Int {
        val sql = "DELETE FROM $dataTable3 WHERE lata_npanxx_report_2_id = $reportId ;"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while LataNpanxxReport2 data 3 delete")
        }
        return 0
    }

    fun deleteLataNpanxxReport2Data4ByReportId(reportId: Long): Int {
        val sql = "DELETE FROM $dataTable4 WHERE lata_npanxx_report_2_id = $reportId ;"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while LataNpanxxReport2 data 4 delete")
        }
        return 0
    }


    // Get page
    fun getLataNpanxxReport2Data1(reportId: Long, page: Int = 0, pageSize: Int = 1000): Pair<List<LataNpanxxReport2Data1>, Int> {
        val pageable = PageRequest.of(page, pageSize, Sort.by("npaNxx"))
        val result = lataNpanxxReport2Data1Repository.findAllByLataNpanxxReport2Id(reportId, pageable)
        return Pair(result.content, result.totalPages)
    }

    fun getLataNpanxxReport2Data2(reportId: Long, page: Int = 0, pageSize: Int = 1000): Pair<List<LataNpanxxReport2Data2>, Int> {
        val pageable = PageRequest.of(page, pageSize, Sort.by("npaNxx"))
        val result = lataNpanxxReport2Data2Repository.findAllByLataNpanxxReport2Id(reportId, pageable)
        return Pair(result.content, result.totalPages)
    }

    fun getLataNpanxxReport2Data3(reportId: Long, page: Int = 0, pageSize: Int = 1000): Pair<List<LataNpanxxReport2Data3>, Int> {
        val pageable = PageRequest.of(page, pageSize, Sort.by("npaNxx"))
        val result = lataNpanxxReport2Data3Repository.findAllByLataNpanxxReport2Id(reportId, pageable)
        return Pair(result.content, result.totalPages)
    }

    fun getLataNpanxxReport2Data4(reportId: Long, page: Int = 0, pageSize: Int = 1000): Pair<List<LataNpanxxReport2Data4>, Int> {
        val pageable = PageRequest.of(page, pageSize, Sort.by("npaNxx"))
        val result = lataNpanxxReport2Data4Repository.findAllByLataNpanxxReport2Id(reportId, pageable)
        return Pair(result.content, result.totalPages)
    }
}