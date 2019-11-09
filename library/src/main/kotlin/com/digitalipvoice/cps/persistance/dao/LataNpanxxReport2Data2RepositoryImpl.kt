package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.SortOption
import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult
import com.digitalipvoice.cps.utils.nativeTableQuery
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext

class LataNpanxxReport2Data2RepositoryImpl : LataNpanxxReport2Data2RepositoryCustom {
    @PersistenceContext
    private lateinit var em: EntityManager

    private val table = "lata_npanxx_report_2_data_2"

    override fun searchLataNpanxxReport2Data2ByReportId(query: TableQuery, reportId: Long): TableResult {
        // columns to select
        if (query.sorts?.isEmpty() != false) {
            // Add sort by user name by default if not exist
            query.addSortsItem(SortOption().column("npa_nxx").direction(SortOption.DirectionEnum.DESC))
        }
        val cols = arrayOf("u.npa_nxx", "u.lata_npanxx_report_2_id", "u.calls", "u.lata", "u.state", "u.total_duration", "u.total_cost", "u.min_carrier", "u.average_rate")
        val table = " $table u WHERE lata_npanxx_report_2_id = $reportId"

        return em.nativeTableQuery(query, table, * cols)
    }
}