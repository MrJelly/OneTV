package com.tvbridge.app

import android.content.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

@android.annotation.TargetApi(21)
internal object DesktopForeground {
    fun latest(context:Context,lookback:Long):Pair<ComponentName,Long>? {
        var found:Pair<ComponentName,Long>?=null;val now=System.currentTimeMillis()
        val events=runCatching{(context.getSystemService("usagestats") as UsageStatsManager).queryEvents(now-lookback,now)}.getOrNull()?:return null
        val event=UsageEvents.Event()
        while(events.hasNextEvent()){events.getNextEvent(event)
            if(event.eventType==UsageEvents.Event.MOVE_TO_FOREGROUND&&event.timeStamp>(found?.second?:0)){
                val pkg=event.packageName;val cls=event.className
                if(pkg!=null&&cls!=null)found=ComponentName(pkg,cls) to event.timeStamp
            }
        };return found
    }
}
