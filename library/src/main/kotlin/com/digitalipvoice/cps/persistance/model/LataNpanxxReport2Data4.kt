package com.digitalipvoice.cps.persistance.model

import javax.persistence.*

@Entity
@IdClass(LataNpanxxReport2DataId::class)
@Table(name = "lata_npanxx_report_2_data_4")
class LataNpanxxReport2Data4 {
    @Id
    var npaNxx = ""

    @Id
    @Column(name = "lata_npanxx_report_2_id")
    var lataNpanxxReport2Id = 0L

    @Column(nullable = true)
    var lata = ""
    @Column(nullable = true)
    var state = ""

    var totalDuration = 0.0f
    var totalCost = 0.0f
    var averageRate = 0.0f
    var minCarrier = ""
}
