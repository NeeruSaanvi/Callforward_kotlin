package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.LataNpanxxReport2Data1
import com.digitalipvoice.cps.persistance.model.LataNpanxxReport2DataId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface LataNpanxxReport2Data1Repository : JpaRepository<LataNpanxxReport2Data1, LataNpanxxReport2DataId>, LataNpanxxReport2Data1RepositoryCustom {
    fun findAllByLataNpanxxReport2Id(lataNpanxxReport2Id: Long, pageable: Pageable): Page<LataNpanxxReport2Data1>
}