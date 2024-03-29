package com.digitalipvoice.cps.configuration

import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import javax.persistence.EntityListeners
import javax.persistence.MappedSuperclass

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class Auditable: AuditableBase() {
    @CreatedBy
    var createdBy = 0L

    @LastModifiedBy
    var updatedBy = 0L
}