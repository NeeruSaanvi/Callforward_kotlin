package com.digitalipvoice.cps.persistance.dao

import com.digitalipvoice.cps.persistance.model.Lad
import org.springframework.data.jpa.repository.JpaRepository

interface LadRepository : JpaRepository<Lad, Long>, LadRepositoryCustom {
}