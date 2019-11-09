package com.digitalipvoice.cps.persistance.model

import com.digitalipvoice.cps.configuration.AuditableBaseId
import javax.persistence.Column
import javax.persistence.Entity

@Entity
class Lad @JvmOverloads constructor(@Column(length = 50) var name: String = "", var userId: Long = 0L, @Column(name = "lata_npanxx_report_2_id") var lataNpanxxReport2Id: Long = 0L) : AuditableBaseId() {
    var isDeleted = false
//    @Column(name = "lata", length = 20)
//    var lata = ""
//
//    @Column(length = 20)
//    var state = ""
//
//    @Column(length = 20, nullable = true)
//    var ac = ""
//
//    @Column(length = 20, nullable = true)
//    var nxx = ""
//
//    @Column(length = 20, nullable = true)
//    var tn = ""
}