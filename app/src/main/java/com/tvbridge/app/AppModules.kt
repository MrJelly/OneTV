package com.tvbridge.app

object AppModules{
    data class Module(val id:String,val title:String,val glyph:String,val top:Int,val bottom:Int)
    val all=listOf(
        Module("module:live","电视直播","tv",0xFFBE6589.toInt(),0xFF784D86.toInt()),
        Module("module:files","文件管理","folder",0xFF5D9CD0.toInt(),0xFF38628E.toInt()),
        Module("module:browser","浏览器","globe",0xFF59AFA9.toInt(),0xFF327281.toInt())
    )
}
