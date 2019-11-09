package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult

interface LataNpanxxReport1DataInvalidRepositoryCustom {
    /**
     * Find users
     */
    fun searchLataNpanxxReport1DataInvalid(query: TableQuery): TableResult

    fun searchLataNpanxxReport1DataInvalidByReportId(query: TableQuery, reportId: Long): TableResult
}