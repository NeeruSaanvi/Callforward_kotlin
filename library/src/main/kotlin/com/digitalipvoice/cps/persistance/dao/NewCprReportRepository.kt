package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.NewCprReport
import org.springframework.data.jpa.repository.JpaRepository

interface NewCprReportRepository : JpaRepository<NewCprReport, Long>, NewCprReportRepositoryCustom {
    fun findByUserIdAndIsDeletedFalse(userId: Long): NewCprReport?
}