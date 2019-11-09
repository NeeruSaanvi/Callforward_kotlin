package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.NewCprReportData1
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface NewCprReportData1Repository : JpaRepository<NewCprReportData1, Long>, NewCprReportData1RepositoryCustom {
    fun findAllByNewCprReportId(newCprReportId: Long, pageable: Pageable): Page<NewCprReportData1>
}