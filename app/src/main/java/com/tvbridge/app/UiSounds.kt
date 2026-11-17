package com.tvbridge.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.view.KeyEvent
import java.util.WeakHashMap

object UiSounds {
    private data class State(var keyAt:Long=-1000,var playedAt:Long=-1000)
    private val states=WeakHashMap<Activity,State>()
    fun enabled(context:Context)=context.getSharedPreferences("appearance",0).getBoolean("ui_sounds",false)
    fun setEnabled(context:Context,value:Boolean){context.getSharedPreferences("appearance",0).edit().putBoolean("ui_sounds",value).apply();if(value)play(context)}
    private val roots=WeakHashMap<android.view.View,Boolean>()
    private var clickedAt=-1000L
    fun attach(activity:Activity)=observe(activity,activity.window.decorView)
    fun observe(activity:Activity,root:android.view.View){
        if(roots.put(root,true)==true)return
        val state=states.getOrPut(activity){State()}
        root.viewTreeObserver.addOnGlobalFocusChangeListener{old,new->
            if(old!==new&&new!=null&&state.keyAt!=state.playedAt&&SystemClock.uptimeMillis()-state.keyAt in 0..250){state.playedAt=state.keyAt;play(activity)}
        }
    }
    fun key(activity:Activity,event:KeyEvent){
        val state=states[activity]?:return
        if(event.action==KeyEvent.ACTION_DOWN){
            if(event.keyCode !in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)){
                state.keyAt=-1000;if(event.repeatCount==0&&event.keyCode==KeyEvent.KEYCODE_BACK)play(activity);return
            };state.keyAt=SystemClock.uptimeMillis()
        }else if(event.action==KeyEvent.ACTION_UP&&!event.isCanceled&&event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER))click(activity)
    }
    fun click(context:Context){GlassUi.activity(context)?.let{states[it]?.keyAt=-1000};val now=SystemClock.uptimeMillis();if(now-clickedAt<80)return;clickedAt=now;play(context)}
    private var pool:SoundPool?=null
    private var sound=0
    private var ready=false
    private var pending=false
    @Synchronized private fun play(context:Context){
        if(!enabled(context))return
        val app=context.applicationContext
        runCatching{
            if(pool==null){
                val file=java.io.File(app.cacheDir,"one-ui-click.wav")
                val samples=882;val data=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                data.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(22050).putInt(44100).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
                repeat(samples){i->val t=i/22050.0;val envelope=kotlin.math.sin(Math.PI*i/samples)*kotlin.math.exp(-t*80);val wave=kotlin.math.sin(2*Math.PI*1050*t)+.35*kotlin.math.sin(2*Math.PI*1550*t);data.putShort((wave*envelope*6500).toInt().toShort())}
                file.writeBytes(data.array())
                val created=SoundPool(2,AudioManager.STREAM_MUSIC,0);pool=created
                created.setOnLoadCompleteListener{loaded,id,status->synchronized(this){if(status==0&&loaded===pool){sound=id;ready=true;if(pending&&enabled(app))loaded.play(sound,.6f,.6f,1,0,1f)};pending=false}}
                sound=created.load(file.path,1)
            }
            if(ready)pool?.play(sound,.6f,.6f,1,0,1f)else pending=true
        }
    }
}
