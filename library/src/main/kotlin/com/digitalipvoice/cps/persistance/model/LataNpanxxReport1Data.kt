package com.digitalipvoice.cps.persistance.model

import java.io.Serializable
import javax.persistence.*

data class LataNpanxxReport1DataId @JvmOverloads constructor(var npaNxx: String = "", var lataNpanxxReport1Id: Long = 0L) : Serializable

@Entity
@IdClass(LataNpanxxReport1DataId::class)
@Table(name = "lata_npanxx_report_1_data")
class LataNpanxxReport1Data {
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
    var minRate = 0.0f
    var minCarrier = ""
}