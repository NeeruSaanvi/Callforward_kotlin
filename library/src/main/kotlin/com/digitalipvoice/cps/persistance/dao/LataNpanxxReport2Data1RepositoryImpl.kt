package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.SortOption
import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult
import com.digitalipvoice.cps.utils.nativeTableQuery
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext

class LataNpanxxReport2Data1RepositoryImpl : LataNpanxxReport2Data1RepositoryCustom {
    @PersistenceContext
    private lateinit var em: EntityManager

    private val table = "lata_npanxx_report_2_data_1"

    override fun searchLataNpanxxReport2Data1ByReportId(query: TableQuery, reportId: Long): TableResult {
        // columns to select
        if (query.sorts?.isEmpty() != false) {
            // Add sort by user name by default if not exist
            query.addSortsItem(SortOption().column("npa_nxx").direction(SortOption.DirectionEnum.ASC))
        }
        val cols = arrayOf("u.npa_nxx", "u.lata_npanxx_report_2_id", "u.calls", "u.lata", "u.state", "u.total_duration", "u.total_cost", "u.min_carrier", "u.average_rate", "u.is_rated")
        val table = """
            $table u
            LEFT JOIN lata_npanxx_report_2 l ON l.id = $reportId
            WHERE u.lata_npanxx_report_2_id = $reportId AND u.min_carrier = l.default_carrier AND u.is_rated = 0
            """.trimMargin()

        return em.nativeTableQuery(query, table, * cols)
    }
}