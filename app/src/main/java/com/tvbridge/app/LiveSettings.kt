package com.tvbridge.app

import android.app.*
import android.content.*
import android.view.*
import android.widget.*

object LiveLaunch {
    private fun prefs(context:Context)=context.getSharedPreferences("startup",0)
    fun builtIn(context:Context)=prefs(context).getString("live_mode",if(prefs(context).contains("live_app"))"default"else "one")=="one"
    fun configured(context:Context)=builtIn(context)||component(context)!=null
    fun component(context:Context):ComponentName? {
        if(builtIn(context))return null
        val raw=prefs(context).getString("live_app",null)?:return null
        val saved=ComponentName.unflattenFromString(raw)
        val apps=LauncherApps(context).installed()
        val entry=apps.firstOrNull{it.component==saved}?:apps.firstOrNull{it.component.packageName==saved?.packageName}
        val valid=entry?.component?.takeIf{target->runCatching{
            context.packageManager.getActivityInfo(target,0).let{it.enabled&&it.exported&&it.applicationInfo.enabled}
        }.getOrDefault(false)}
        if(valid!=saved||valid==null)choose(context,valid)
        return valid
    }
    fun choose(context:Context,component:ComponentName?,builtIn:Boolean=false){
        prefs(context).edit().putString("live_mode",if(builtIn)"one"else "default").apply{if(component==null)remove("live_app")else putString("live_app",component.flattenToString())}.apply()
    }
    fun selectInternal(context:Context,mode:String,repo:LiveRepository):Boolean {
        if(mode !in listOf("default","one"))return false
        choose(context,null,mode=="one")
        if(mode=="one")repo.subscribe(LiveRepository.DEFAULT_SOURCE)
        else repo.sourceList().firstOrNull()?.let{repo.selectSource(it.url)}
        return true
    }
    fun label(context:Context):String {
        if(builtIn(context))return "默认直播"
        val component=component(context)?:return "自定义直播"
        return runCatching{context.packageManager.getActivityInfo(component,0).loadLabel(context.packageManager).toString()}.getOrElse{choose(context,null);"自定义直播"}
    }
    fun open(context:Context):Boolean {
        val component=component(context)?:return false
        val apps=LauncherApps(context)
        val entry=apps.installed().firstOrNull{it.component==component}
        val opened=entry!=null&&apps.launch(entry)
        if(!opened)choose(context,null)
        return opened
    }
}

class LiveSettings(private val activity:Activity){
    fun show(){
        val entries=LauncherApps(activity).installed().filter{it.component.packageName !in setOf(activity.packageName,"com.tvbridge.engine")}
        val selected=LiveLaunch.component(activity)
        fun dp(n:Int)=(n*2*NativeUi.scale(activity)).toInt()
        val labels=listOf("默认直播","自定义直播")+entries.map{it.label}
        val rows=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(activity).apply{isVerticalScrollBarEnabled=false;addView(rows)}
        val body=FrameLayout(activity).apply{addView(scroll,FrameLayout.LayoutParams(-1,dp(minOf(264,((labels.size+1)/2)*50))))}
        var dialog:AlertDialog?=null
        var current:Button?=null
        labels.chunked(2).forEachIndexed{rowIndex,items->
            val row=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
            rows.addView(row,LinearLayout.LayoutParams(-1,dp(50)))
            items.forEachIndexed{column,label->
                val index=rowIndex*2+column
                val component=entries.getOrNull(index-2)?.component
                val button=Button(activity).apply{
                    text=label;tag=component?:if(index==0)"one-live"else "one-player";isSelected=if(index<2)selected==null&&(index==0)==LiveLaunch.builtIn(activity)else component==selected
                    contentDescription=label+if(isSelected)"，已选择"else ""
                    isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;maxLines=1
                    ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(12),0,dp(12),0);NativeUi.textSize(this,26f);setTextColor(GlassUi.colors(activity));background=GlassUi.surface(this,20f,faint=true)
                    if(android.os.Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null}
                    if(isSelected){
                        val mark=AppIcons.Vector("check"){currentTextColor}.apply{setBounds(0,0,dp(14),dp(14))}
                        setCompoundDrawablesRelative(null,null,mark,null);compoundDrawablePadding=dp(6);current=this
                    }
                    setOnClickListener{
                        if(index<2)LiveLaunch.selectInternal(activity,if(index==0)"one"else "default",Hub.live)
                        else LiveLaunch.choose(activity,component)
                        dialog?.dismiss()
                    };NativeUi.touchClicks(this)
                }
                row.addView(button,LinearLayout.LayoutParams(0,dp(44),1f).apply{if(column>0)leftMargin=dp(8)})
            }
            if(items.size==1)row.addView(View(activity),LinearLayout.LayoutParams(0,dp(44),1f).apply{leftMargin=dp(8)})
        }
        dialog=NativeUi.show(AlertDialog.Builder(activity).setTitle("直播管理").setView(body).setNegativeButton("取消",null),initialFocus=current)
    }
}
