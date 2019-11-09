package com.digitalipvoice.cps.persistance.model

import com.digitalipvoice.cps.configuration.AuditableBaseId
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import javax.persistence.*

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "lata_npanxx_report_2")
class LataNpanxxReport2 @JvmOverloads constructor(@Column(length = 50) var name: String = "", var userId: Long = 0L) : AuditableBaseId() {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lata_npanxx_report_1_id")
    var lataNpanxxReport1: LataNpanxxReport1? = null

    var isDeleted = false

    // for top duration npanxx
    var defaultCarrier = ""
    var defaultCarrierTotalDuration = 0.0
    var defaultCarrierTotalCost = 0.0
    var defaultCarrierAverageRate = 0.0
    var defaultCarrierNpaNxx = ""
    var defaultCarrierCount = ""

    // for default carrier whole npanxx
    @Column(name = "v1_total_duration")
    var v1TotalDuration = 0.0
    @Column(name = "v1_total_cost")
    val v1TotalCost = 0.0
    @Column(name = "v1_average_cost")
    val v1AverageCost = 0.0

    // for remaining carriers all
    @Column(name = "v2_total_duration")
    var v2TotalDuration = 0.0
    @Column(name = "v2_total_cost")
    var v2TotalCost = 0.0
    @Column(name = "v2_average_cost")
    var v2AverageCost = 0.0
    @Column(name = "v2_default_carrier")
    var v2DefaultCarrier = ""
    @Column(name = "v2_default_carrier_total_duration")
    var v2DefaultCarrierTotalDuration = 0.0
    @Column(name = "v2_default_carrier_total_cost")
    var v2DefaultCarrierTotalCost = 0.0
    @Column(name = "v2_default_carrier_average_rate")
    var v2DefaultCarrierAverageRate = 0.0
    @Column(name = "v2_carriers_detail", length = 5000)
    var v2CarriersDetail = ""


    // for remaining carriers top 33k
    @Column(name = "v3_total_duration")
    var v3TotalDuration: Double = 0.0
    @Column(name = "v3_total_cost")
    var v3TotalCost: Double = 0.0
    @Column(name = "v3_average_cost")
    var v3AverageCost: Double = 0.0

    @Column(name = "v3_winning_carriers", length = 5000)
    var v3WinningCarriers = ""

    // for final result = default + remaining(others than 33k)
    @Column(name = "v4_other_carriers_detail", length = 5000)
    var v4OtherCarriersDetail = ""
    @Column(name = "v4_total_count")
    var v4TotalCount = 0
    @Column(name = "v4_total_duration")
    var v4TotalDuration = 0.0
    @Column(name = "v4_total_cost")
    var v4TotalCost = 0.0
    @Column(name = "v4_average_cost")
    var v4AverageCost = 0.0

    // totalCost(LataNpanxxReport1 total) - v4TotalCost
    @Column(name = "v4_difference_total_duration")
    var v4DifferenceTotalDuration = 0.0
    @Column(name = "v4_difference_total_cost")
    var v4DifferenceTotalCost = 0.0
    @Column(name = "v4_difference_average_cost")
    var v4DifferenceAverageCost = 0.0


}