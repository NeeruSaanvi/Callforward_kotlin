package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.LataNpanxxReport1
import org.springframework.data.jpa.repository.JpaRepository

interface LataNpanxxReport1Repository : JpaRepository<LataNpanxxReport1, Long>, LataNpanxxReport1RepositoryCustom {
    fun findByUserIdAndNameAndIsDeletedFalse(userId: Long, name: String): LataNpanxxReport1?

    fun findAllByUserIdAndIsDeletedFalse(userId: Long): List<LataNpanxxReport1>
}