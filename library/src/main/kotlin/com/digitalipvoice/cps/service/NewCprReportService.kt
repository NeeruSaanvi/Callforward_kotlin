package com.digitalipvoice.cps.service

import com.digitalipvoice.cps.client.admin.models.NewCprReportData1Request
import com.digitalipvoice.cps.client.admin.models.NewCprReportData1Result
import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.exceptions.BadRequestException
import com.digitalipvoice.cps.persistance.dao.NewCprReportData1Repository
import com.digitalipvoice.cps.persistance.dao.NewCprReportRepository
import com.digitalipvoice.cps.persistance.model.NewCprReport
import com.digitalipvoice.cps.persistance.model.NewCprReportData2
import com.digitalipvoice.cps.utils.findByIdKt
import com.digitalipvoice.cps.utils.logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class NewCprReportService {
    private val log = logger(javaClass)

    private val listTable = "new_cpr_report"
    private val dataTable1 = "${listTable}_data_1"
    private val dataTable2 = "${listTable}_data_2"
    private val sourceDataTable = "lata_npanxx_report_2_data_4"

    @Autowired
    private lateinit var newCprReportRepository: NewCprReportRepository

    @Autowired
    private lateinit var newCprReportData1Repository: NewCprReportData1Repository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    /**
     * Find Report by Id
     */
    fun findNewCprReportById(id: Long) = newCprReportRepository.findByIdKt(id)

    fun findNewCprReportsByUserId(userId: Long) = newCprReportRepository.findByUserIdAndIsDeletedFalse(userId)

    fun saveNewCprReport(newCprReport: NewCprReport) = newCprReportRepository.saveAndFlush(newCprReport)
    /**
     * Search Report List By UserId
     */
    fun searchNewCprReportByUserId(query: TableQuery, userId: Long) = newCprReportRepository.searchNewCprReportByUserId(query, userId)

    fun generateNewCprReport(userId: Long, newCprReportId: Long, lataNpanxxReport2Id: Long): Int {
        val stateList = jdbcTemplate.queryForList("SELECT DISTINCT state FROM $sourceDataTable WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND state <> '' AND lata <> '' GROUP BY state ORDER BY state ASC")
        val v1SummaryList = ArrayList<String>()
        stateList.forEach { map ->
            val state = map["state"]?.toString() ?: return@forEach
            if (state.isEmpty())
                return@forEach

            val carrierInStateList = jdbcTemplate.queryForList("SELECT DISTINCT min_carrier FROM $sourceDataTable WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND state = '$state' AND state <> '' AND lata <> '' ORDER BY min_carrier ASC")
            v1SummaryList.add("$state(${carrierInStateList.size})")

            // for final CPR (download as CSV)
            if (carrierInStateList.size == 1) {
                val carrier = carrierInStateList[0]["min_carrier"]?.toString() ?: return@forEach
                val sql = """
                    INSERT INTO $dataTable2 (new_cpr_report_id, state, lata, npa_nxx, carrier, code_type)
                    VALUES ($newCprReportId, '$state', '', '', '$carrier', '${NewCprReportData2.oneCarrierState}')
                """.trimIndent()
                jdbcTemplate.execute(sql)
            } else if (carrierInStateList.size > 1) {
                val lataList = jdbcTemplate.queryForList("SELECT DISTINCT lata, COUNT(npa_nxx) as count FROM $sourceDataTable WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id GROUP BY lata ORDER BY COUNT(npa_nxx) ASC")
                lataList.forEach {
                    val lata = it["lata"]?.toString() ?: "NONE"
                    val carrierInLataList = jdbcTemplate.queryForList("SELECT DISTINCT min_carrier, COUNT(npa_nxx) as count FROM $sourceDataTable WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND state = '$state' AND lata <> '$lata' AND lata <> '' ORDER BY COUNT(npa_nxx) ASC")
                    for (i in 0 until carrierInLataList.size) {
                        val carrier = carrierInLataList[i]["min_carrier"]?.toString()?:"NONE"
                        if (i < carrierInLataList.size - 1) {
                            val sql = """
                                INSERT INTO $dataTable2 (new_cpr_report_id, state, lata, npa_nxx, carrier, code_type)
                                SELECT
                                $newCprReportId,
                                '$state',
                                '$lata',
                                s.npa_nxx,
                                '$carrier',
                                '${NewCprReportData2.multiCarrierNormal}'
                                FROM $sourceDataTable s
                                WHERE s.lata_npanxx_report_2_id = $lataNpanxxReport2Id AND s.state = '$state' AND s.lata = '$lata' AND s.min_carrier = '$carrier'
                                ORDER BY s.npa_nxx
                            """.trimIndent()
                            jdbcTemplate.execute(sql)
                        } else {
                            val sql = """
                                INSERT INTO $dataTable2 (new_cpr_report_id, state, lata, npa_nxx, carrier, code_type)
                                VALUES ($newCprReportId, '$state', '$lata', '', '$carrier', '${NewCprReportData2.multiCarrierOther}')
                            """.trimIndent()
                            jdbcTemplate.execute(sql)
                        }
                    }
                }
            }

        }

        // update list table
        jdbcTemplate.execute("UPDATE $listTable SET v1_summary = '${v1SummaryList.joinToString(";")}' WHERE id = $newCprReportId")

        return v1SummaryList.size
    }

    /**
     * Search Carriers by lataNpanxxReport2Id
     * @return List<Carrier-count>
     */
    fun searchData1(lataNpanxxReport2Id: Long, r: NewCprReportData1Request): NewCprReportData1Result {
        // for validation of state
        if (r.state.isNullOrEmpty())
            throw BadRequestException("Please select a state")

        val ret = NewCprReportData1Result()     // return value
        val carriers = ArrayList<String>()      // for return value option
        val latas = ArrayList<String>()         //      --//--
        val npanxxs = ArrayList<String>()       //      --//--

        // condition query for state
        val conditionString = StringBuffer()
        conditionString.append(" lata_npanxx_report_2_id = $lataNpanxxReport2Id AND state = '${r.state}' AND state <> '' AND lata <> '' ")

        // get all latas with count of carriers in the state
        val lataListMap = jdbcTemplate.queryForList("SELECT DISTINCT lata FROM $sourceDataTable WHERE $conditionString GROUP BY lata ORDER BY lata ASC")
        lataListMap.forEach { item ->
            val lata = item["lata"]?.toString() ?: return@forEach
            if (lata.isEmpty())
                return@forEach
            val carriersInLata = jdbcTemplate.queryForList("SELECT DISTINCT min_carrier FROM $sourceDataTable WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND state = '${r.state}' AND state <> '' AND lata = '$lata' AND lata <> '' GROUP BY min_carrier ORDER BY min_carrier ASC")
            latas.add("$lata(${carriersInLata.size})")
        }

        // condition query for lata and npanxx
        if (!r.lata.isNullOrEmpty())
            conditionString.append(" AND lata = '${r.lata}'")
        if (!r.npanxx.isNullOrEmpty())
            conditionString.append(" AND npa_nxx = '${r.npanxx}' ")

        // get carriers with count of codes of the npanxx, in the lata, in the state
        val carrierListMap = jdbcTemplate.queryForList("SELECT DISTINCT min_carrier as carrier, COUNT(npa_nxx) as count FROM $sourceDataTable WHERE $conditionString GROUP BY min_carrier ORDER BY min_carrier ASC")
        carrierListMap.forEach { item ->
            val carrier = item["carrier"]?.toString() ?: return@forEach
            val count = item["count"]?.toString()?.toIntOrNull() ?: 0
            carriers.add("$carrier($count)")
        }


        // get all npanxx codes in the carrier ,of the lata, in the state
        if (!r.carrier.isNullOrEmpty())
            conditionString.append(" AND min_carrier = '${r.carrier}' ")

        val npanxxListMap = jdbcTemplate.queryForList("SELECT npa_nxx FROM $sourceDataTable WHERE $conditionString ORDER BY npa_nxx ASC")
        npanxxListMap.forEach { item ->
            val npaNxx = item["npa_nxx"]?.toString() ?: return@forEach
            npanxxs.add("$npaNxx")
        }

        // set return values into values got from query
        ret.carriers = carriers
        ret.npanxxs = npanxxs
        ret.latas = latas

        // return result
        return ret
    }

    // For Data
    /**
     * search LataNpanxxReport2 by report id
     */
    fun searchNewCprReportData1ByReportId(query: TableQuery, reportId: Long) = newCprReportData1Repository.searchReportData1ByReportId(query, reportId)


    /**
     * delete LataNpanxxReport2 by report id
     */
    fun deleteNewCprReportByReportId(reportId: Long): Int {
        newCprReportRepository.findByIdKt(reportId)?.let {
            it.isDeleted = true
            newCprReportRepository.save(it)
        }

        var sql = "DELETE FROM $dataTable1 WHERE new_cpr_report_id = $reportId ;"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while NewCprReport delete")
        }
        sql = "DELETE FROM $dataTable2 WHERE new_cpr_report_id = $reportId ;"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while NewCprReport delete")
        }
        return 0
    }

    // Get page
    fun getNewCprReportData1(lataNpanxxReport2Id: Long, state: String, lata: String, carrier: String): List<String> {
        if (state.isNullOrEmpty())
            throw BadRequestException("Please select a state")

        val result = ArrayList<String>()
        val conditionQeury = StringBuffer()
        conditionQeury.append(" lata_npanxx_report_2_id = $lataNpanxxReport2Id ")
        conditionQeury.append(" AND state = '$state' AND state <> '' AND lata <> '' AND min_carrier <> '' ")

        if (!lata.isNullOrEmpty())
            conditionQeury.append(" AND lata = '$lata' ")
        if (!carrier.isNullOrEmpty())
            conditionQeury.append(" AND min_carrier = '$carrier' ")

        val rowList = jdbcTemplate.queryForList("SELECT lata, npa_nxx, min_carrier as carrier FROM $sourceDataTable WHERE $conditionQeury ORDER BY lata ASC, min_carrier ASC, npa_nxx ASC")
        rowList.forEach {
            val npaNxx = it["npa_nxx"]?.toString() ?: return@forEach
            val lata = it["lata"]?.toString() ?: return@forEach
            val carrier = it["carrier"]?.toString() ?: return@forEach
            val carrierNum =
                    when (carrier) {
                        "ATT" -> "288"
                        "VRZ" -> "555"
                        "ITQ" -> "5105"
                        "ATX" -> "288"
                        "LV3" -> "5102"
                        else -> carrier
                    }
            result.add("$lata,$npaNxx,$carrierNum\n")
        }
        return result
    }
}