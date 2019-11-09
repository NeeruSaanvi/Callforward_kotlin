package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.LataNpanxxReport2
import org.springframework.data.jpa.repository.JpaRepository

interface LataNpanxxReport2Repository : JpaRepository<LataNpanxxReport2, Long>, LataNpanxxReport2RepositoryCustom {
    fun findByUserIdAndNameAndIsDeletedFalse(userId: Long, name: String): LataNpanxxReport2?

    fun findAllByUserIdAndIsDeletedFalse(userId: Long): List<LataNpanxxReport2>
}