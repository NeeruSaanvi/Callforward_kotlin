package com.digitalipvoice.cps.persistance.model

import com.digitalipvoice.cps.configuration.AuditableBaseId
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import javax.persistence.*

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "cdr_data_file")
class CdrDataFile @JvmOverloads constructor(@Column(length = 50) var fileName: String = "", var userId: Long = 0L, var billingSecond: Int = 1) : AuditableBaseId() {
}