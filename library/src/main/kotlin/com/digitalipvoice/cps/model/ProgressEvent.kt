package com.digitalipvoice.cps.model

class ProgressEventCategory {
    companion object {
        const val cdrImport = "cprgen.cdrImport"
        const val lergImport = "cprgen.lergImport"
        const val rateDeckImport = "cprgen.rateDeckImport"
        const val cdrDip = "cprgen.cdrDip"
        const val lcrReport = "cprgen.lcrReport"
        const val lataNpanxxReport1 = "cprgen.lataNpanxxReport1"
        const val lataNpanxxReport2 = "cprgen.lataNpanxxReport2"
        const val cprReport = "cprgen.cprReport"
        const val somosCadBulk = "somos.cadBulk"
    }
}

class ProgressEvent @JvmOverloads constructor(var category: String, var progress: Float = 0f, var description: String = "")
