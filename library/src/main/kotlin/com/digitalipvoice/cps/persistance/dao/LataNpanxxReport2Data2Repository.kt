package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.LataNpanxxReport2Data2
import com.digitalipvoice.cps.persistance.model.LataNpanxxReport2DataId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface LataNpanxxReport2Data2Repository : JpaRepository<LataNpanxxReport2Data2, LataNpanxxReport2DataId>, LataNpanxxReport2Data2RepositoryCustom {
    fun findAllByLataNpanxxReport2Id(lataNpanxxReport2Id: Long, pageable: Pageable): Page<LataNpanxxReport2Data2>
}