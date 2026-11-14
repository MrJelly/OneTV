package com.tvbridge.app

import android.inputmethodservice.InputMethodService
import android.os.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.*
import android.widget.*
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RemoteInputService:InputMethodService(){
    @Volatile private var editing=false
    override fun onCreate(){super.onCreate();active=WeakReference(this)}
    override fun onStartInput(attribute:EditorInfo?,restarting:Boolean){super.onStartInput(attribute,restarting);editing=attribute!=null&&attribute.inputType!=InputType.TYPE_NULL}
    override fun onFinishInput(){editing=false;super.onFinishInput()}
    override fun onDestroy(){editing=false;if(active.get()===this)active.clear();super.onDestroy()}
    override fun onEvaluateFullscreenMode()=false
    override fun onCreateInputView():View=LinearLayout(this).apply{
        gravity=Gravity.CENTER_VERTICAL;setPadding(24,12,24,12);setBackgroundColor(0xffe9ece9.toInt())
        addView(TextView(context).apply{text="手机输入";setTextColor(0xff35445a.toInt());textSize=16f},LinearLayout.LayoutParams(0,-2,1f))
        addView(Button(context).apply{text="切换键盘";setOnClickListener{(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()}})
    }
    private fun insert(text:String):Boolean {
        if(!editing||!RemoteText.valid(text))return false
        val connection=currentInputConnection?:return false
        connection.beginBatchEdit()
        return try{connection.finishComposingText();connection.commitText(text,1)}finally{connection.endBatchEdit()}
    }
    companion object {
        @Volatile private var active=WeakReference<RemoteInputService>(null)
        val isActive:Boolean get()=active.get()?.editing==true
        fun commit(text:String,waitMs:Long=2000):Boolean {
            if(!RemoteText.valid(text))return false
            if(Looper.myLooper()==Looper.getMainLooper())return active.get()?.insert(text)==true
            val handler=Handler(Looper.getMainLooper());val done=CountDownLatch(1)
            val finished=AtomicBoolean(false);val result=AtomicBoolean(false)
            val deadline=SystemClock.uptimeMillis()+waitMs
            val probe=object:Runnable{override fun run(){
                if(finished.get())return
                val service=active.get()
                if(service?.editing==true){
                    if(finished.compareAndSet(false,true)){try{result.set(service.insert(text))}catch(_:RuntimeException){}finally{done.countDown()}}
                }else if(SystemClock.uptimeMillis()<deadline)handler.postDelayed(this,50)
                else if(finished.compareAndSet(false,true))done.countDown()
            }}
            handler.post(probe)
            try{done.await(waitMs+250,TimeUnit.MILLISECONDS)}catch(_:InterruptedException){Thread.currentThread().interrupt()}
            finished.set(true);handler.removeCallbacks(probe)
            return result.get()
        }
    }
}
