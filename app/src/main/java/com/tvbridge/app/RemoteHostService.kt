package com.tvbridge.app

import android.app.*
import android.content.*
import android.os.*

class RemoteHostService:Service(){
    private val health=Handler(Looper.getMainLooper())
    @Volatile private var screenOff=false
    private val check=object:Runnable{override fun run(){DesktopTakeover.recover(this@RemoteHostService);if(!screenOff)health.postDelayed(this,30000)}}
    private var screenReceiver:BroadcastReceiver?=null
    companion object{@Volatile var running=false;private set}
    override fun onCreate(){super.onCreate();running=true
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel("remote","手机遥控与文件传输",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=23)PendingIntent.FLAG_IMMUTABLE else 0)
        val builder=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,"remote")else Notification.Builder(this)
        val notification=builder.setSmallIcon(R.drawable.notification_icon).setContentTitle("One手机连接服务运行中").setContentText("用于局域网遥控与传文件，可在One遥控设置中停止").setContentIntent(open).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(1,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)else startForeground(1,notification)
        Hub.start(this)
        health.post(check)
        screenOff=runCatching{val power=getSystemService(POWER_SERVICE) as PowerManager;!(if(Build.VERSION.SDK_INT>=21)power.isInteractive else power.isScreenOn)}.getOrDefault(false)
        screenReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
            when(intent.action){
                Intent.ACTION_SCREEN_OFF->{screenOff=true;health.removeCallbacks(check)}
                Intent.ACTION_SCREEN_ON->{screenOff=false;health.removeCallbacks(check);health.post(check)}
            }
        }}.also{androidx.core.content.ContextCompat.registerReceiver(this,it,IntentFilter(Intent.ACTION_SCREEN_OFF).apply{addAction(Intent.ACTION_SCREEN_ON)},androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)}
        if(Hub.adb.enabled())Thread({Hub.adb.restore()},"restore-system-remote").start()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_STICKY
    override fun onBind(intent:Intent?)=null
    override fun onDestroy(){screenReceiver?.let{try{unregisterReceiver(it)}catch(_:Throwable){}};screenReceiver=null;health.removeCallbacksAndMessages(null);GlobalPointer.hide();running=false;if(Hub.activity==null)Hub.stop();super.onDestroy()}
}
