package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.SortOption
import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult
import com.digitalipvoice.cps.utils.nativeTableQuery
import com.digitalipvoice.cps.utils.nativeTableQueryGroupBy
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext

class LataNpanxxReport1RepositoryImpl : LataNpanxxReport1RepositoryCustom {
    @PersistenceContext
    private lateinit var em: EntityManager

    private val table = "lata_npanxx_report_1"

    override fun searchLataNpanxxReport1(query: TableQuery): TableResult {
        if (query.sorts?.isEmpty() != false) {
            query.addSortsItem(SortOption().column("updated_at").direction(SortOption.DirectionEnum.DESC))
        }
        // columns to select
        val cols = arrayOf("u.id", "u.user_id", "u.created_at", "u.updated_at", "u.name", "u.lcr_report_id", "u.total_duration", "u.total_cost", "u.average_cost", "u.compared_cdr_file_names", "u.default_rate", "u.invalid_npanxx_count")
        val table = " $table u  WHERE u.is_deleted = 0"

        return em.nativeTableQuery(query, table, * cols)
    }

    override fun searchLataNpanxxReport1ByUserId(query: TableQuery, userId: Long): TableResult {
        if (query.sorts?.isEmpty() != false) {
            query.addSortsItem(SortOption().column("updated_at").direction(SortOption.DirectionEnum.DESC))
        }
        // columns to select
        val cols = arrayOf(
                "u.id", "u.user_id", "u.created_at", "u.updated_at", "u.name", "u.lcr_report_id", "lcrr.name as lcr_report_name", "u.compared_cdr_file_names",
                "u.total_duration", "u.total_cost", "u.average_cost", "u.valid_npanxx_count",
                "u.default_rate", "u.invalid_npanxx_count", "u.invalid_total_duration", "u.invalid_total_cost",
                "u.v3_total_count", "u.v3_total_duration", "u.v3_total_cost", "u.v3_average_cost", "u.v3_carriers_detail")
        val table = """
             $table u
             INNER JOIN lcr_report lcrr ON u.lcr_report_id = lcrr.id
             WHERE u.is_deleted = 0  AND u.user_id = $userId
        """.trimIndent()

        return em.nativeTableQueryGroupBy(query, table, " GROUP BY (u.id) ", * cols)
    }
}