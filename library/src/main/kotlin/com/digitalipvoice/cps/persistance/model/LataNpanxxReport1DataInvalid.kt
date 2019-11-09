package com.digitalipvoice.cps.persistance.model

import javax.persistence.*

@Entity
@IdClass(LataNpanxxReport1DataId::class)
@Table(name = "lata_npanxx_report_1_data_invalid")
class LataNpanxxReport1DataInvalid {
    @Id
    var npaNxx = ""

    @Id
    @Column(name = "lata_npanxx_report_1_id")
    var lataNpanxxReport1Id = 0L

    @Column(nullable = true)
    var lata = ""
    @Column(nullable = true)
    var state = ""

    var calls = 0L
    var totalDuration = 0.0f
    var totalCost = 0.0f
    var defaultRate = 0.0f
}