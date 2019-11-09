package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.LataNpanxxReport2Data3
import com.digitalipvoice.cps.persistance.model.LataNpanxxReport2DataId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface LataNpanxxReport2Data3Repository : JpaRepository<LataNpanxxReport2Data3, LataNpanxxReport2DataId>, LataNpanxxReport2Data3RepositoryCustom {
    fun findAllByLataNpanxxReport2Id(lataNpanxxReport4Id: Long, pageable: Pageable): Page<LataNpanxxReport2Data3>
}