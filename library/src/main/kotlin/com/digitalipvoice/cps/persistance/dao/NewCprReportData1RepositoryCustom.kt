package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult

interface NewCprReportData1RepositoryCustom {

    fun searchReportData1ByReportId(query: TableQuery, reportId: Long): TableResult
}