package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RemoteTextTest {
    @Test fun validatesUnicodeWithoutTrimming(){
        assertTrue(RemoteText.valid(" 中文 ' $ &\n"))
        assertFalse(RemoteText.valid(""))
        assertFalse(RemoteText.valid("a\u0000b"))
        assertFalse(RemoteText.valid("字".repeat(2001)))
    }
    private class Keyboard(val alreadyEnabled:Boolean=false){
        val original="example.keyboard/.Ime"
        val one="com.tvbridge.app/.RemoteInputService"
        var current=original
        val commands=mutableListOf<String>()
        fun shell(cmd:String):String{
            commands+=cmd
            return when(cmd){
                "settings get secure default_input_method"->current
                "settings get secure selected_input_method_subtype"->"7"
                "settings get secure enabled_input_methods"->original+if(alreadyEnabled)":$one" else ""
                "ime set '$one'"->{current=one;""}
                "ime set '$original'"->{current=original;""}
                else->""
            }
        }
    }
    @Test fun unicodeNeverEntersShellAndKeyboardRestored(){
        val k=Keyboard();val text="中文 ' $(hello) & 视频"
        val result=TextInputSession.send(text,k.one,k::shell){assertEquals(k.one,k.current);assertEquals(text,it);true}
        assertTrue(result.committed);assertTrue(result.restored);assertEquals(k.original,k.current)
        assertTrue(k.commands.contains("ime disable '${k.one}'"))
        assertTrue(k.commands.contains("settings put secure selected_input_method_subtype 7"))
        assertFalse(k.commands.any{it.contains(text)})
    }
    @Test fun restoresKeyboardEvenWhenCommitThrows(){
        val k=Keyboard()
        try{TextInputSession.send("测试",k.one,k::shell){throw IllegalStateException("test")};fail()}catch(_:IllegalStateException){}
        assertEquals(k.original,k.current)
        assertTrue(k.commands.contains("ime disable '${k.one}'"))
    }
    @Test fun existingEnabledKeyboardRemainsEnabledAfterRejectedInput(){
        val k=Keyboard(true)
        val result=TextInputSession.send("测试",k.one,k::shell){false}
        assertFalse(result.committed);assertTrue(result.restored)
        assertFalse(k.commands.any{it.startsWith("ime enable")||it.startsWith("ime disable")})
    }
}
