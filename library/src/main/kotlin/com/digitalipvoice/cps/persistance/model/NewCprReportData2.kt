package com.digitalipvoice.cps.persistance.model

import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.IdClass
import javax.persistence.Table

@Entity
@Table(name = "new_cpr_report_data_2")
@IdClass(NewCprReportDataId::class)
class NewCprReportData2 {
    @Id
    var newCprReportId = 0L

    @Id
    var state = ""

    @Id
    var lata = ""

    @Id
    var npaNxx = ""

    @Id
    var carrier = ""

    var codeType = ""

    companion object {
        const val oneCarrierState = "one.carrier.state"
        const val oneCarrierLata = "one.carrier.lata"
        const val multiCarrierNormal = "multi.carrier.normal"
        const val multiCarrierOther = "multi.carrier.other"
    }

}