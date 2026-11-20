package com.tvbridge.app

internal object BootLaunchPolicy {
    fun shouldOffer(fromBoot:Boolean,reopen:Boolean,earlyHome:Boolean,offeredThisBoot:Boolean)=
        reopen||(!offeredThisBoot&&(fromBoot||earlyHome))
}
