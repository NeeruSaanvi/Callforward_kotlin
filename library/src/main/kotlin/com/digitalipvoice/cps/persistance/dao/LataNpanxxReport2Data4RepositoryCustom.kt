package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult

interface LataNpanxxReport2Data4RepositoryCustom {
    fun searchLataNpanxxReport2Data4ByReportId(query: TableQuery, reportId: Long): TableResult
}