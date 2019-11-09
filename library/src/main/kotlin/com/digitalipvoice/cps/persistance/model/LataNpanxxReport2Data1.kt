package com.digitalipvoice.cps.persistance.model

import java.io.Serializable
import javax.persistence.*

data class LataNpanxxReport2DataId @JvmOverloads constructor(var npaNxx: String = "", var lataNpanxxReport2Id: Long = 0L) : Serializable

@Entity
@IdClass(LataNpanxxReport2DataId::class)
@Table(name = "lata_npanxx_report_2_data_1")
class LataNpanxxReport2Data1 {
    @Id
    var npaNxx = ""

    @Id
    @Column(name = "lata_npanxx_report_2_id")
    var lataNpanxxReport2Id = 0L

    @Column(nullable = true)
    var lata = ""
    @Column(nullable = true)
    var state = ""

    var calls = 0L
    var totalDuration = 0.0f
    var totalCost = 0.0f
    var minCarrier = ""
    var averageRate = 0.0f

    var isRated = false
}