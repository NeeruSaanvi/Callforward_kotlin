package com.digitalipvoice.cps.persistance.model

import java.io.Serializable
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.IdClass
import javax.persistence.Table

data class NewCprReportDataId @JvmOverloads constructor(var npaNxx: String = "", var newCprReportId: Long = 0L, var state: String = "", var lata: String = "", var carrier: String = "") : Serializable

@Entity
@Table(name = "new_cpr_report_data_1")
@IdClass(NewCprReportDataId::class)
class NewCprReportData1 {
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
}