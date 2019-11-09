package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.SortOption
import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult
import com.digitalipvoice.cps.utils.nativeTableQuery
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext

class NewCprReportRepositoryImpl : NewCprReportRepositoryCustom {
    private val table = "new_cpr_report"

    @PersistenceContext
    private lateinit var em: EntityManager

    override fun searchNewCprReportByUserId(query: TableQuery, userId: Long): TableResult {
        if (query.sorts?.isEmpty() != false) {

            query.addSortsItem(SortOption().column("updated_at").direction(SortOption.DirectionEnum.DESC))
        }
        // columns to select
        val cols = arrayOf("u.id", "u.user_id", "u.created_at", "u.updated_at", "u.name", "lnr.name as second_report_name", "u.v1_summary")
        val table = " $table u LEFT JOIN lata_npanxx_report_2 lnr ON lnr.id = u.lata_npanxx_report_2_id WHERE u.user_id = $userId AND u.is_deleted = 0"

        return em.nativeTableQuery(query, table, * cols)
    }
}