package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.SortOption
import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult
import com.digitalipvoice.cps.utils.nativeTableQuery
import com.digitalipvoice.cps.utils.nativeTableQueryGroupBy
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext

class LataNpanxxReport2RepositoryImpl : LataNpanxxReport2RepositoryCustom {
    @PersistenceContext
    private lateinit var em: EntityManager

    private val table = "lata_npanxx_report_2"

    override fun searchLataNpanxxReport2ByUserId(query: TableQuery, userId: Long): TableResult {
        if (query.sorts?.isEmpty() != false) {
            query.addSortsItem(SortOption().column("updated_at").direction(SortOption.DirectionEnum.DESC))
        }
        // columns to select
        val cols = arrayOf(
                "u.id", "u.user_id", "u.created_at", "u.updated_at", "u.name", "u.lata_npanxx_report_1_id", "lnr1.name as report1_name",
                "u.default_carrier", "u.default_carrier_npa_nxx", "u.default_carrier_total_duration", "u.default_carrier_total_cost", "u.default_carrier_average_rate", "u.default_carrier_count",
                "u.v1_total_duration", "u.v1_total_cost", "u.v1_average_cost", "lnr1.invalid_npanxx_count as v1_rated_count",
                "u.v2_total_duration", "u.v2_total_cost", "u.v2_average_cost", "u.v2_carriers_detail",
                "u.v2_default_carrier","u.v2_default_carrier_average_rate","u.v2_default_carrier_total_cost","u.v2_default_carrier_total_duration",
                "u.v3_total_duration", "u.v3_total_cost", "u.v3_average_cost", "u.v3_winning_carriers",
                "u.v4_other_carriers_detail","u.v4_total_count","u.v4_total_duration","u.v4_total_cost","u.v4_average_cost",
                "u.v4_difference_total_duration","u.v4_difference_total_cost","u.v4_difference_average_cost"
                )
        val table = """
             $table u
             LEFT JOIN lata_npanxx_report_1 lnr1 ON u.lata_npanxx_report_1_id = lnr1.id
             WHERE u.user_id = $userId AND u.is_deleted = 0
        """.trimIndent()

        return em.nativeTableQueryGroupBy(query, table, " GROUP BY (u.id) ", * cols)
    }
}