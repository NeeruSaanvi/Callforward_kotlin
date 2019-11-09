package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.SortOption
import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult
import com.digitalipvoice.cps.utils.nativeTableQuery
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext

class NewCprReportData1RepositoryImpl : NewCprReportData1RepositoryCustom {
    private val table = "new_cpr_report_data_1"

    @PersistenceContext
    private lateinit var em: EntityManager

    override fun searchReportData1ByReportId(query: TableQuery, reportId: Long): TableResult {
        if (query.sorts?.isEmpty() != false) {
            // Add sort by user name by default if not exist
            query.addSortsItem(SortOption().column("id").direction(SortOption.DirectionEnum.ASC))
        }
        val cols = arrayOf("u.new_cpr_report_id", "u.state", "u.lata", "u.carrier", "u.npa_nxx")
        val table = " $table u WHERE u.new_cpr_report_id = $reportId"

        return em.nativeTableQuery(query, table, * cols)
    }
}