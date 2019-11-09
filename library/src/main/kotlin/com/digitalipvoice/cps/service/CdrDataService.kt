package com.digitalipvoice.cps.service

import com.digitalipvoice.cps.client.admin.models.SortOption
import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult
import com.digitalipvoice.cps.persistance.dao.CdrDataFileRepository
import com.digitalipvoice.cps.persistance.model.CdrDataFile
import com.digitalipvoice.cps.utils.logger
import com.digitalipvoice.cps.utils.nativeTableQuery
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext

@Service
class CdrDataService {
    private val dataTablePrefix = "cdr_data"
    private val fileTable = "cdr_data_file"
    @PersistenceContext
    private lateinit var em: EntityManager

    val log = logger(javaClass)

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var cdrDataFileRepository: CdrDataFileRepository

    fun createTable(userId: Long) {
        val sql1 = "CREATE TABLE IF NOT EXISTS `${dataTablePrefix}_$userId` (row_ani VARCHAR(50), cost FLOAT, duration FLOAT, lrn VARCHAR(50), rate FLOAT, prefix VARCHAR(50), INDEX(row_ani));"
        try {
            jdbcTemplate.execute(sql1)
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while CDR table create")
        }
        val sql2 = "CREATE TABLE IF NOT EXISTS `${dataTablePrefix}_${userId}_validate` (row_ani VARCHAR(50), lrn VARCHAR(50), calls INT(11), total_duration FLOAT, total_cost FLOAT, average_cost FLOAT, npa_nxx VARCHAR(20), INDEX(row_ani));"
        try {
            jdbcTemplate.execute(sql2)
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while CDR table create")
        }
        val sql3 = "CREATE TABLE IF NOT EXISTS `${dataTablePrefix}_${userId}_groupby_ani` (row_ani VARCHAR(50), lrn VARCHAR(50), calls INT(11), total_duration FLOAT, total_cost FLOAT, average_cost FLOAT, npa_nxx VARCHAR(20), INDEX(row_ani))"
        try {
            jdbcTemplate.execute(sql3)
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while lata_npanxx_report_1_${userId}_buff1 table create")
        }

/*
        val sql4 = "CREATE TABLE IF NOT EXISTS `${dataTablePrefix}_${userId}_fill_npanxx` (npa_nxx VARCHAR(20), calls INT(11), total_duration FLOAT, total_cost FLOAT, average_cost FLOAT, INDEX(npa_nxx))"
        try {
            jdbcTemplate.execute(sql4)
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while lata_npanxx_report_1_${userId}_buff2 table create")
        }
*/
    }

    // For CDR data
    fun deleteAllByUserId(userId: Long = 0L) {
        jdbcTemplate.execute("DELETE FROM `${dataTablePrefix}_$userId`")
        jdbcTemplate.execute("DELETE FROM `${dataTablePrefix}_${userId}_groupby_ani`")
        jdbcTemplate.execute("DELETE FROM `$fileTable` WHERE user_id = $userId")
    }

    fun insertBatchImproved(values: Iterable<String>, userId: Long): Int {
        val sql = "INSERT INTO `${dataTablePrefix}_$userId` (row_ani, cost, duration, lrn, rate, prefix) VALUES ${values.joinToString(",")}"
        try {
            return jdbcTemplate.update(sql)
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while batchInsert")
        }
        return 0
    }

    fun registerImportedCdrFile(fileName: String, userId: Long, billingSecond: Int) {
        val newCdrDataFile = CdrDataFile(fileName, userId, billingSecond)
        cdrDataFileRepository.save(newCdrDataFile)
    }

    fun getImportedCdrFiles(userId: Long) = cdrDataFileRepository.findAllByUserId(userId)


    fun validateCDR(userId: Long): Int {
        var sql = "TRUNCATE `${dataTablePrefix}_${userId}_validate`;"
        try {
            jdbcTemplate.execute(sql)
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while truncate ${dataTablePrefix}_${userId}_groupby_ani table")
        }

        sql = """
            INSERT INTO `${dataTablePrefix}_${userId}_validate` (row_ani, lrn, calls, total_duration, total_cost, average_cost, npa_nxx)
            SELECT DISTINCT
                validate.row_ani,
                validate.lrn,
                IF(SUM(validate.duration)>0,COUNT(validate.duration),0),
                SUM(validate.duration),
                SUM(validate.cost),
                AVG(validate.cost),
                IF(validate.row_ani='0000000000','000000',SUBSTR(validate.row_ani,1,6))
            FROM (
                SELECT
                    IF(lerg.npa_nxx IS NULL, '0000000000', org.row_ani) as row_ani,
                    org.cost as cost,
                    org.duration as duration,
                    org.lrn as lrn
                FROM `${dataTablePrefix}_$userId` org
                LEFT JOIN lerg_import lerg ON org.prefix = lerg.npa_nxx
            ) validate
            GROUP BY validate.row_ani, validate.lrn
        """.trimIndent()

        try {
            return jdbcTemplate.update(sql)
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while validating CDR")
        }
        return 0
    }

    /**
     * Dip CDRs
     */
    fun fillNpanxx(userId: Long, withLrn: Boolean = false, billingSecond: Int = 1) {
        var sql = "TRUNCATE `${dataTablePrefix}_${userId}_groupby_ani`;"
        try {
            jdbcTemplate.execute(sql)
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while truncate ${dataTablePrefix}_${userId}_groupby_ani table")
        }

        val strNpanxx = if (withLrn) "IF(LENGTH(cdr.lrn)>5,SUBSTRING(cdr.lrn,1,6),IF(LENGTH(lrn.lrn)>5,SUBSTRING(lrn.lrn,1,6),SUBSTRING(cdr.row_ani,1,6)))" else "IF(LENGTH(cdr.lrn)>5,SUBSTRING(cdr.lrn,1,6),SUBSTRING(cdr.row_ani,1,6))"
        val strLrn = if (withLrn) "IF(LENGTH(cdr.lrn)<10,IF(lrn.lrn IS NULL,'',lrn.lrn),cdr.lrn)" else "''"
        sql = """
            INSERT INTO `${dataTablePrefix}_${userId}_groupby_ani` (row_ani, lrn, calls, total_duration, total_cost, average_cost, npa_nxx)
            SELECT
                cdr.row_ani,
                $strLrn,
                cdr.calls,
                CEIL(cdr.total_duration/$billingSecond)*$billingSecond,
                cdr.total_cost,
                cdr.average_cost,
                IF(cdr.npa_nxx = '000000', cdr.npa_nxx, $strNpanxx)
            FROM `${dataTablePrefix}_${userId}_validate` cdr
            LEFT JOIN lrn_data lrn ON cdr.row_ani = lrn.did
        """.trimIndent()
        try {
            jdbcTemplate.execute(sql)
        } catch (ex: Exception) {
            ex.printStackTrace()
            log.error("Exception occurred while Group by row_ani")
        }
    }

    fun searchCDR(query: TableQuery, userId: Long): TableResult {
        // columns to select
        val cols = arrayOf("u.row_ani", "u.lrn", "u.npa_nxx", "u.calls", "u.total_duration", "u.total_cost", "u.average_cost")
        val table = " `${dataTablePrefix}_${userId}_groupby_ani` u "

        return em.nativeTableQuery(query, table, * cols)
    }
}