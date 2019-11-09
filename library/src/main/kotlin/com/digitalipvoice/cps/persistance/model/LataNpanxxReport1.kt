package com.digitalipvoice.cps.persistance.model

import com.digitalipvoice.cps.configuration.AuditableBaseId
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import javax.persistence.*

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "lata_npanxx_report_1")
class LataNpanxxReport1 @JvmOverloads constructor(@Column(length = 50) var name: String = "", var userId: Long = 0L) : AuditableBaseId() {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lcr_report_id")
    var lcrReport: LcrReport? = null

    var comparedCdrFileNames = ""

    var isDeleted = false

    var totalDuration: Double = 0.0
    var totalCost: Double = 0.0
    var averageCost: Double = 0.0
    var validNpanxxCount = 0.0

    var defaultRate = 0.0f
    var invalidNpanxxCount = 0
    var invalidTotalDuration = 0.0
    var invalidTotalCost = 0.0

    @Column(name = "v3_total_count")
    var v3TotalCount = 0
    @Column(name = "v3_total_duration")
    var v3TotalDuration = 0.0
    @Column(name = "v3_total_cost")
    var v3TotalCost = 0.0
    @Column(name = "v3_average_cost")
    var v3AverageCost = 0.0
    @Column(name = "v3_carriers_detail", length = 5000)
    var v3CarriersDetail = ""
}