package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult

interface LataNpanxxReport2Data2RepositoryCustom {
    /**
     * Find users
     */
    fun searchLataNpanxxReport2Data2ByReportId(query: TableQuery, reportId: Long): TableResult
}