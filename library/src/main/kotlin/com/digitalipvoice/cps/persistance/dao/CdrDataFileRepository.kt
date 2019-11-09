package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.CdrDataFile
import org.springframework.data.jpa.repository.JpaRepository

interface CdrDataFileRepository : JpaRepository<CdrDataFile, Long> {
    fun findAllByUserId(userId: Long): List<CdrDataFile>
}