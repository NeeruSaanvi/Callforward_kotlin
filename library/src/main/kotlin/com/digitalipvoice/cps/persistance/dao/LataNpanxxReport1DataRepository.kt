package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.LataNpanxxReport1Data
import com.digitalipvoice.cps.persistance.model.LataNpanxxReport1DataId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface LataNpanxxReport1DataRepository : JpaRepository<LataNpanxxReport1Data, LataNpanxxReport1DataId>, LataNpanxxReport1DataRepositoryCustom {
    fun findAllByLataNpanxxReport1Id(lataNpanxxReport1Id: Long, pageable: Pageable): Page<LataNpanxxReport1Data>
}