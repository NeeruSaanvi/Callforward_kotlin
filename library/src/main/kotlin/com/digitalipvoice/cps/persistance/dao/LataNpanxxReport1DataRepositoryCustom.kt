package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult

interface LataNpanxxReport1DataRepositoryCustom {

    fun searchLataNpanxxReport1DataByReportId(query: TableQuery, reportId: Long): TableResult
}