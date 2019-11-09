package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult

interface LataNpanxxReport2RepositoryCustom {
    fun searchLataNpanxxReport2ByUserId(query: TableQuery, userId: Long): TableResult
}