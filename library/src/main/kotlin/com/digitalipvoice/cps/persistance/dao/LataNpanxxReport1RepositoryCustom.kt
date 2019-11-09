package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.client.admin.models.TableQuery
import com.digitalipvoice.cps.client.admin.models.TableResult

interface LataNpanxxReport1RepositoryCustom {
    /**
     * Find users
     */
    fun searchLataNpanxxReport1(query: TableQuery): TableResult

    fun searchLataNpanxxReport1ByUserId(query: TableQuery, userId: Long): TableResult

}