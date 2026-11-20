package com.tvbridge.app

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Printer
import android.view.KeyEvent
import java.util.ArrayDeque

internal object UiPerformance {
    const val SETTING="one_performance_trace"
    @Volatile private var enabled=false
    @Volatile private var startedAt=0L
    @Volatile private var dispatchAt=0L
    @Volatile private var dispatchName=""
    private var messages=0L
    private var slowMessages=0L
    private var worstMessageMs=0L
    private val slow=ArrayDeque<String>()
    private val stacks=ArrayDeque<String>()
    private val queueMs=ArrayDeque<Long>()
    private val keyMs=ArrayDeque<Long>()
    private val sections=ArrayDeque<String>()
    fun <T> section(name:String,run:()->T):T{
        if(!enabled)return run()
        val start=SystemClock.uptimeMillis()
        return try{run()}finally{val ms=SystemClock.uptimeMillis()-start;if(ms>=8)synchronized(this){add(sections,"$name ${ms}ms",48)}}
    }
    private fun <T> add(queue:ArrayDeque<T>,value:T,limit:Int){if(queue.size>=limit)queue.removeFirst();queue.addLast(value)}

    fun start(context:Context){
        if(enabled||runCatching{Settings.Global.getInt(context.contentResolver,SETTING,0)}.getOrDefault(0)!=1)return
        enabled=true;startedAt=SystemClock.uptimeMillis()
        val looper=Looper.getMainLooper()
        looper.setMessageLogging(Printer { line->
            if(line.startsWith(">>>>>")){
                dispatchName=line.take(200);dispatchAt=SystemClock.uptimeMillis()
            }else if(line.startsWith("<<<<<")){
                val start=dispatchAt;dispatchAt=0L
                if(start>0){
                    val elapsed=SystemClock.uptimeMillis()-start
                    synchronized(this){
                        messages++;worstMessageMs=maxOf(worstMessageMs,elapsed)
                        if(elapsed>=32){slowMessages++;add(slow,"at=$start ${elapsed}ms $dispatchName",32)}
                    }
                }
            }
        })
        val resolver=context.applicationContext.contentResolver
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var sampled=0L;var nextCheck=0L
            while(enabled){
                val now=SystemClock.uptimeMillis();val start=dispatchAt
                if(start>0&&start!=sampled&&now-start>=32){
                    sampled=start
                    val trace=looper.thread.stackTrace.take(18).joinToString(" <- ")
                    synchronized(this){add(stacks,"at=$start ${now-start}ms $dispatchName\n$trace",16)}
                }
                if(now>=nextCheck){
                    nextCheck=now+1000
                    if(runCatching{Settings.Global.getInt(resolver,SETTING,0)}.getOrDefault(0)!=1){
                        enabled=false
                        android.os.Handler(looper).post{looper.setMessageLogging(null)}
                        break
                    }
                }
                try{Thread.sleep(16)}catch(_:InterruptedException){break}
            }
        },"one-ui-sampler").apply{isDaemon=true;start()}
    }

    fun key(event:KeyEvent,run:()->Boolean):Boolean {
        if(!enabled||event.action!=KeyEvent.ACTION_DOWN)return run()
        val start=SystemClock.uptimeMillis()
        synchronized(this){
            val queued=(start-event.eventTime).coerceAtLeast(0L)
            add(queueMs,queued,512)
            if(queued>=32)add(sections,"at=$start key=${KeyEvent.keyCodeToString(event.keyCode)} queue=${queued}ms",48)
        }
        return try{run()}finally{synchronized(this){val ms=SystemClock.uptimeMillis()-start;add(keyMs,ms,512);if(ms>=16)add(sections,"key=${KeyEvent.keyCodeToString(event.keyCode)} ${ms}ms",48)}}
    }

    @Synchronized fun report():String {
        if(startedAt==0L)return "disabled"
        fun distribution(values:Collection<Long>):String {
            val sorted=values.sorted();if(sorted.isEmpty())return "n=0"
            fun percentile(p:Double)=sorted[kotlin.math.ceil(sorted.size*p).toInt().coerceIn(1,sorted.size)-1]
            return "n=${sorted.size} p50=${percentile(.5)}ms p95=${percentile(.95)}ms max=${sorted.last()}ms"
        }
        return buildString{
            append("enabled=$enabled elapsedMs=${SystemClock.uptimeMillis()-startedAt} messages=$messages slow32ms=$slowMessages worstMs=$worstMessageMs\n")
            append("keyQueue ${distribution(queueMs)}\nkeyDispatch ${distribution(keyMs)}\n")
            append("sections:\n");sections.forEach{append(it).append('\n')}
            append("slowMessages:\n");slow.forEach{append(it).append('\n')}
            append("sampledMainStacks:\n");stacks.forEach{append(it).append('\n')}
        }
    }
}
