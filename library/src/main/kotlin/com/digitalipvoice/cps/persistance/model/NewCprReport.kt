package com.digitalipvoice.cps.persistance.model

import com.digitalipvoice.cps.configuration.AuditableBaseId
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.EntityListeners
import javax.persistence.Table

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "new_cpr_report")
class NewCprReport @JvmOverloads constructor(@Column(length = 50) var name: String = "", var userId: Long = 0L) : AuditableBaseId() {
    @Column(name = "lata_npanxx_report_2_id")
    var lataNpanxxReport2Id = 0L

    @Column(name = "v1_summary", length = 5000)
    var v1Summary = ""

    var isDeleted = false
}

