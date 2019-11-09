package com.digitalipvoice.cps.service

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult
import com.digitalipvoice.cps.exceptions.BadRequestException
import com.digitalipvoice.cps.persistance.dao.LadRepository
import com.digitalipvoice.cps.persistance.model.Lad
import com.digitalipvoice.cps.utils.findByIdKt
import com.digitalipvoice.cps.utils.logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class LadService {
    private val log = logger(javaClass)
    private val listTable = "lad"
    private val top33kTable = "lata_npanxx_report_2_data_3"
    private val sdTable = "lad_six_digit"
    private val lataTable = "lad_lata"

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var ladRepository: LadRepository

    fun createDataTables() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `$sdTable` (label VARCHAR(20), definitions LONGTEXT, lad_id BIGINT(20) , INDEX(lad_id, label))")
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `$lataTable` (label VARCHAR(20), definitions LONGTEXT, lad_id BIGINT(20) , INDEX(lad_id, label))")
    }

    fun save(lad: Lad) = ladRepository.saveAndFlush(lad)

    fun buildLad(lataNpanxxReport2Id: Long, userId: Long, name: String = ""): Int {
        createDataTables()

        val lad = Lad(name, userId, lataNpanxxReport2Id)
        save(lad)


        var sdIndex = 0
        var lataIndex = 0

        val sdDataItem = ArrayList<String>()
        val sdDataArray = ArrayList<String>()
        // insert six digit data into database
        fun insertSixDigitData() {
            if (sdDataArray.size > 0) {
                val sqlSixDigit = """
                            INSERT INTO $sdTable (label, definitions, lad_id)
                            VALUES ${sdDataArray.joinToString(",")};
                        """.trimIndent()
                jdbcTemplate.execute(sqlSixDigit)
                sdDataArray.clear()
            }
        }


        // get state
        val stateList = jdbcTemplate.queryForList("SELECT DISTINCT state FROM $top33kTable WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id GROUP BY state")

        stateList.forEach { stateMap ->
            val state = stateMap["state"]?.toString() ?: return@forEach

            // get lata
            val lataList = jdbcTemplate.queryForList("SELECT DISTINCT lata FROM $top33kTable WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND state = '$state' GROUP BY lata")
            val ladLataArray = ArrayList<String>()
            lataloop@ for (i in 0 until lataList.size) {
                val lata = lataList[i]["lata"]?.toString() ?: continue@lataloop
                lataIndex++

                ladLataArray.add("('${"*LATA%04d".format(lataIndex)}','$lata',${lad.id})")

                // add six digit data into array
                fun addSixDigitData() {
                    if (sdDataItem.isNotEmpty()) {
                        sdIndex++

                        sdDataArray.add("('${"*NXX%05d".format(sdIndex)}','${sdDataItem.joinToString()}',${lad.id})")
                        sdDataItem.clear()
                    }
                }


                val npaNxxList = jdbcTemplate.queryForList("SELECT npa_nxx FROM $top33kTable WHERE lata_npanxx_report_2_id = $lataNpanxxReport2Id AND state='$state' AND lata='$lata'")
                npanxxloop@ for (j in 0 until npaNxxList.size) {
                    val npaNxx = npaNxxList[j]["npa_nxx"]?.toString() ?: continue@lataloop
                    sdDataItem.add(npaNxx)

                    // SD's count == 255
                    if (sdDataItem.size >= 255) {
                        addSixDigitData()

                    }
                }
                // if remaining...
                if (sdDataItem.size > 0) {
                    addSixDigitData()
                }
            }

            // insert data
            insertSixDigitData()
            jdbcTemplate.execute("INSERT INTO $lataTable (label, definitions, lad_id) VALUES ${ladLataArray.joinToString(",")}")
        }

        return 0
    }

    fun searchLAD(r: TableQuery, userId: Long): TableResult {
        return ladRepository.searchLad(userId, r)
    }

    fun getSixDigitById(radId: Long, userId: Long):  List<Map<String, Any>>{
        if (ladRepository.findByIdKt(radId)?.userId == userId) {
            try {
                return jdbcTemplate.queryForList("SELECT label, definitions FROM $sdTable WHERE lad_id = $radId")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        throw BadRequestException("Please select a valid LAD")
    }
    fun getLataById(radId: Long, userId: Long):  List<Map<String, Any>>{
        if (ladRepository.findByIdKt(radId)?.userId == userId) {
            try {
                return jdbcTemplate.queryForList("SELECT label, definitions FROM $lataTable WHERE lad_id = $radId")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        throw BadRequestException("Please select a valid LAD")
    }
}