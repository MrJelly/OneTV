package com.tvbridge.app

internal object VendorSourceContracts {
    enum class Kind { HDMI, AV, TV }
    data class Port(val key: String, val label: String, val selector: Int, val kind: Kind)
    val tcl = (1..4).map { Port("HDMI$it", "HDMI $it", 7 + it, Kind.HDMI) } +
        listOf(Port("DTV", "数字电视", 2, Kind.TV), Port("AV1", "AV", 3, Kind.AV))
    val whaley = (1..3).map { Port("HDMI$it", "HDMI $it", 3 + it, Kind.HDMI) } +
        Port("AV1", "AV", 7, Kind.AV)
    val dangbei = listOf(Port("HDMI1", "HDMI 1", 9, Kind.HDMI), Port("HDMI2", "HDMI 2", 10, Kind.HDMI))
}
