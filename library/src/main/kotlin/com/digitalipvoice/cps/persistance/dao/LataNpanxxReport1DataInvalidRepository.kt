package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.LataNpanxxReport1DataId
import com.digitalipvoice.cps.persistance.model.LataNpanxxReport1DataInvalid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface LataNpanxxReport1DataInvalidRepository : JpaRepository<LataNpanxxReport1DataInvalid, LataNpanxxReport1DataId>, LataNpanxxReport1DataInvalidRepositoryCustom {
    fun findAllByLataNpanxxReport1Id(lataNpanxxReport1Id: Long, pageable: Pageable): Page<LataNpanxxReport1DataInvalid>
}