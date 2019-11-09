package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult

interface LadRepositoryCustom {
    fun searchLad(userId: Long, r: TableQuery): TableResult
}