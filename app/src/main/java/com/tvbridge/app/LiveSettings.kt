package com.tvbridge.app

import android.app.*
import android.content.*

object LiveLaunch {
    private fun prefs(context:Context)=context.getSharedPreferences("startup",0)
    fun builtIn(context:Context):Boolean{
        val state=prefs(context)
        if(state.contains("live_app")){
            state.edit().remove("live_app").putString("live_mode","default").apply()
            return false
        }
        return state.getString("live_mode","one")=="one"
    }
    fun selectInternal(context:Context,mode:String,repo:LiveRepository):Boolean {
        if(mode !in listOf("default","one"))return false
        prefs(context).edit().remove("live_app").putString("live_mode",mode).apply()
        if(mode=="one")repo.subscribe(LiveRepository.DEFAULT_SOURCE)
        else repo.sourceList().firstOrNull()?.let{repo.selectSource(it.url)}
        return true
    }
    fun label(context:Context)=if(builtIn(context))"默认直播" else "自定义直播"
}

class LiveSettings(private val activity:Activity){
    fun show(){
        val current=if(LiveLaunch.builtIn(activity))0 else 1
        val body=android.widget.LinearLayout(activity).apply{orientation=android.widget.LinearLayout.VERTICAL}
        val scale=NativeUi.scale(activity)
        var dialog:AlertDialog?=null
        listOf("默认直播","自定义直播").forEachIndexed{index,label->
            val selected=index==current
            val button=NativeUi.optionButton(activity,label,selected=selected)
            if(selected){
                val size=(32*scale).toInt()
                val check=AppIcons.Vector("check"){button.currentTextColor}.apply{setBounds(0,0,size,size)}
                button.setCompoundDrawablesRelative(null,null,check,null)
                button.contentDescription="$label，已选择"
            }
            body.addView(button,android.widget.LinearLayout.LayoutParams(-1,NativeUi.settingsRowHeight(activity)).apply{bottomMargin=NativeUi.settingsRowGap(activity)})
            button.setOnClickListener{
                if(!selected)LiveLaunch.selectInternal(activity,if(index==0)"one" else "default",Hub.live)
                dialog?.dismiss()
            }
        }
        dialog=NativeUi.show(AlertDialog.Builder(activity).setTitle("直播管理").setView(body),initialFocus=body.getChildAt(current))
    }
}
