package com.tvbridge.app

import android.view.View
import android.widget.EditText

object RemoteText {
    const val MAX_LENGTH=2000
    fun valid(text:String)=text.isNotEmpty()&&text.length<=MAX_LENGTH&&!text.contains('\u0000')
    fun insert(view:View?,text:String):Boolean {
        val editor=view as? EditText?:return false
        if(!valid(text)||!editor.isEnabled||!editor.isShown||!editor.isFocused)return false
        val value=editor.text
        val start=editor.selectionStart.takeIf{it>=0}?:value.length
        val end=editor.selectionEnd.takeIf{it>=0}?:start
        value.replace(minOf(start,end),maxOf(start,end),text)
        editor.setSelection((minOf(start,end)+text.length).coerceAtMost(editor.text.length))
        return true
    }
}

internal object TextInputSession {
    data class Result(val committed:Boolean,val restored:Boolean)
    fun send(text:String,component:String,shell:(String)->String,commit:(String)->Boolean):Result {
        require(RemoteText.valid(text))
        val pattern=Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
        require(component.matches(pattern))
        fun identity(value:String)=android.content.ComponentName.unflattenFromString(value)
        val previous=shell("settings get secure default_input_method").trim()
        if(!previous.matches(pattern))return Result(false,true)
        if(identity(previous)==identity(component))return Result(commit(text),true)
        val subtype=shell("settings get secure selected_input_method_subtype").trim().toIntOrNull()
        val enabled=shell("settings get secure enabled_input_methods").trim().split(':')
            .any{identity(it.substringBefore(';'))==identity(component)}
        var committed=false
        var restored=false
        try{
            if(!enabled)shell("ime enable '$component'")
            shell("ime set '$component'")
            committed=commit(text)
        }finally{
            try{
                shell("ime set '$previous'")
                restored=identity(shell("settings get secure default_input_method").trim())==identity(previous)
                if(restored&&subtype!=null)shell("settings put secure selected_input_method_subtype $subtype")
                if(restored&&!enabled)shell("ime disable '$component'")
            }catch(_:Exception){ }
        }
        return Result(committed,restored)
    }
}
