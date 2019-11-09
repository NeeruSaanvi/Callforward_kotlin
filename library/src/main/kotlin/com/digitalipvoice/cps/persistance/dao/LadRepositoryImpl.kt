package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.SortOption
import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult
import com.digitalipvoice.cps.utils.nativeTableQuery
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext

class LadRepositoryImpl : LadRepositoryCustom {
    val table = "lad"

    @PersistenceContext
    private lateinit var em: EntityManager

    override fun searchLad(userId: Long, query: TableQuery): TableResult {
        // columns to select
        if (query.sorts?.isEmpty() != false) {
            // Add sort by user name by default if not exist
            query.addSortsItem(SortOption().column("npa_nxx").direction(SortOption.DirectionEnum.ASC))
        }
        val cols = arrayOf("u.id", "u.lata_npanxx_report_2_id", "u.name", "lnr.name as report_name", "u.created_at", "u.updated_at")
        val table = " $table u LEFT JOIN lata_npanxx_report_2 lnr ON lnr.id = u.lata_npanxx_report_2_id WHERE u.user_id = $userId AND u.is_deleted = 0"

        return em.nativeTableQuery(query, table, * cols)
    }
}