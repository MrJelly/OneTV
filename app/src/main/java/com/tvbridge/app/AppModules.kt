package com.tvbridge.app

object AppModules{
    data class Module(val id:String,val title:String,val glyph:String,val top:Int,val bottom:Int)
    val all=listOf(
        Module("module:live","电视直播","tv",0xFF02D8F9.toInt(),0xFF760EFB.toInt()),
        Module("module:files","文件管理","folder",0xFF2BD9FE.toInt(),0xFF0259FE.toInt()),
        Module("module:browser","浏览器","globe",0xFFFA99A2.toInt(),0xFFFA0D91.toInt())
    )
    fun square(context:android.content.Context,module:Module,size:Int=256):android.graphics.drawable.Drawable {
        val bitmap=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader=android.graphics.LinearGradient(0f,0f,size.toFloat(),size.toFloat(),module.top,module.bottom,android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,size.toFloat(),size.toFloat(),paint)
        val glyph=AppIcons.Vector(module.glyph){android.graphics.Color.WHITE}
        val g=(size*when(module.glyph){"tv"->0.70f;"folder"->0.69f;else->0.75f}).toInt();val o=(size-g)/2
        glyph.setBounds(o,o,o+g,o+g);glyph.draw(canvas)
        return android.graphics.drawable.BitmapDrawable(context.resources,bitmap)
    }
    fun square(context:android.content.Context,id:String)=all.firstOrNull{it.id==id}?.let{square(context,it)}
}
