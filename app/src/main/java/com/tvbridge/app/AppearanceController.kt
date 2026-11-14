package com.tvbridge.app

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.view.*
import android.widget.*
import java.io.File

class AppearanceController(private val activity:Activity,private val installer:ApkInstaller,private val changed:(keepDialog:Boolean)->Unit,private val connect:()->Unit){
    companion object{const val IMAGE=61}
    private var importing=false
    private var wallpaperDialog:AlertDialog?=null
    private var wallpaperRefresh:(()->Unit)?=null
    private var uploadDialog:AlertDialog?=null
    private var wallpaperDraft:WallpaperDraft?=null
    private fun dp(value:Int)=(value*2*NativeUi.scale(activity)).toInt()
    private fun message(text:String)=NativeUi.notice(activity,text)
    fun wallpaper(){
        wallpaperDialog?.dismiss()
        val draft=WallpaperDraft(activity);wallpaperDraft=draft
        val row=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        var dialog:AlertDialog?=null
        val tiles=mutableMapOf<String,View>()
        val previews=mutableMapOf<String,FrameLayout>()
        fun confirm(mode:String=draft.selection){
            if(draft.busy){message("正在处理图片，请稍候");return}
            if(!draft.available(mode))return
            draft.selection=mode;draft.busy=true
            Thread({val error=try{draft.commit();null}catch(e:Throwable){e.message?:"壁纸切换失败"}
                activity.runOnUiThread{draft.busy=false;if(error==null){if(draft.dismissed)draft.dispose()else dialog?.dismiss();if(!activity.isFinishing)changed(false)}else if(draft.dismissed)draft.dispose()else message(error)}
            },"wallpaper-confirm").start()
        }
        fun refreshTiles(){
            for((mode,tile) in tiles){
                tile.isSelected=mode==draft.selection
                val preview=previews.getValue(mode);preview.removeAllViews()
                val bitmap=draft.thumbnail(mode)
                if(bitmap!=null)preview.addView(ImageView(activity).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setImageBitmap(bitmap)},FrameLayout.LayoutParams(-1,-1))
                else preview.addView(TextView(activity).apply{text="暂无";gravity=Gravity.CENTER;NativeUi.textSize(this,24f);setTextColor(GlassUi.muted(activity))},FrameLayout.LayoutParams(-1,-1))
                tile.isEnabled=draft.available(mode);tile.isFocusable=tile.isEnabled
                preview.invalidate()
            }
        }
        for(mode in listOf("gradient","bing","custom")){
            val tile=LinearLayout(activity).apply{
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;id=View.generateViewId();tag=mode
                setOnClickListener{confirm(mode)}
            }
            tiles[mode]=tile
            tile.setOnFocusChangeListener{_,_->previews[mode]?.invalidate()}
            val preview=object:FrameLayout(activity){
                private val shape=android.graphics.Path()
                private val edge=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun dispatchDraw(canvas:android.graphics.Canvas){
                    val corner=24*NativeUi.scale(activity);val rect=android.graphics.RectF(0f,0f,width.toFloat(),height.toFloat())
                    val save=canvas.save();shape.reset();shape.addRoundRect(rect,corner,corner,android.graphics.Path.Direction.CW);canvas.clipPath(shape);super.dispatchDraw(canvas);canvas.restoreToCount(save)
                    edge.style=android.graphics.Paint.Style.STROKE;edge.strokeWidth=(if(tile.isSelected||tile.hasFocus())2f else 1f)*NativeUi.scale(activity);edge.color=if(tile.isSelected||tile.hasFocus())0xff81c4d6.toInt()else 0x55ffffff
                    rect.inset(edge.strokeWidth/2,edge.strokeWidth/2);canvas.drawRoundRect(rect,corner,corner,edge)
                }
            }.apply{background=GlassUi.surface(this,24f)}
            previews[mode]=preview
            tile.addView(preview,LinearLayout.LayoutParams(-1,dp(64)))
            NativeUi.touchClicks(tile);row.addView(tile,LinearLayout.LayoutParams(0,dp(64),1f).apply{if(row.childCount>0)leftMargin=dp(8)})
        }
        refreshTiles()
        val buttons=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val wallpaperButtonWidth=listOf("确认","刷新壁纸","上传图片").maxOf{Button(activity).apply{NativeUi.textSize(this,26f)}.paint.measureText(it)+dp(16)}.toInt().coerceIn(dp(80),dp(200))
        fun makeButton(text:String,action:()->Unit){
            val button=Button(activity).apply{setText(text);NativeUi.textSize(this,26f);setTextColor(GlassUi.colors(activity));isAllCaps=false;maxLines=1;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;if(android.os.Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null;elevation=0f};setPadding(dp(8),0,dp(8),0);background=GlassUi.surface(this,24f);setOnClickListener{if(draft.busy)message("正在处理图片，请稍候")else action()};NativeUi.touchClicks(this)}
            buttons.addView(button,LinearLayout.LayoutParams(wallpaperButtonWidth,dp(32)).apply{if(buttons.childCount>0)leftMargin=dp(10)})
        }
        makeButton("确认"){confirm()}
        makeButton("刷新壁纸"){
            draft.busy=true
            Thread({val error=draft.refresh();activity.runOnUiThread{draft.busy=false;if(!draft.dismissed){if(error==null){draft.selection="bing";refreshTiles()}else message(error)}else draft.dispose()}},"bing-preview").start()
        }
        makeButton("上传图片"){received()}
        val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;addView(row);addView(buttons,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})}
        dialog=NativeUi.show(AlertDialog.Builder(activity).setTitle("桌面背景").setView(body))
        wallpaperDialog=dialog;wallpaperRefresh={refreshTiles()}
        val shownDialog=dialog
        dialog.setOnDismissListener{
            NativeUi.handleExternalDismiss(shownDialog);draft.dismissed=true
            if(wallpaperDraft===draft){wallpaperDraft=null;wallpaperDialog=null;wallpaperRefresh=null}
            uploadDialog?.dismiss();uploadDialog=null
            if(!draft.busy)draft.dispose()
        }
        tiles.values.firstOrNull{it.isSelected&&it.isEnabled}?.requestFocusFromTouch()
    }
    private fun received(){
        val files=File(activity.filesDir,"received").listFiles()?.filter{it.isFile&&it.extension.lowercase() in listOf("jpg","jpeg","png","webp","bmp","gif")}?.sortedByDescending{it.lastModified()}.orEmpty()
        if(files.isEmpty()){

            message("暂无可上传的图片，请在设置页手机连接扫码上传");return}
        val anchorView=wallpaperDialog?.takeIf{it.isShowing}?.window?.decorView
        val widthDp=anchorView?.let{view->val w=wallpaperDialog?.window?.attributes?.width?:0;if(w>0)(w/(2*NativeUi.scale(activity))).toInt() else null}
        uploadDialog=if(anchorView!=null)NativeUi.show(AlertDialog.Builder(activity).setTitle("选择背景").setItems(files.map{it.name}.toTypedArray()){_,which->importWallpaper(Uri.fromFile(files[which]))}.setNegativeButton("取消",null),anchor=anchorView,widthDp=widthDp)
        else NativeUi.show(AlertDialog.Builder(activity).setTitle("选择背景").setItems(files.map{it.name}.toTypedArray()){_,which->importWallpaper(Uri.fromFile(files[which]))}.setNegativeButton("取消",null))
    }
    fun onResult(request:Int,result:Int,data:Intent?):Boolean {
        if(request!=IMAGE)return false
        if(result!=Activity.RESULT_OK)return true
        val uri=data?.data?:return true
        importWallpaper(uri)
        return true
    }
    private fun importWallpaper(uri:Uri){
        val draft=wallpaperDraft?:return
        if(importing||draft.busy)return
        importing=true;draft.busy=true
        Thread({val error=try{activity.contentResolver.openInputStream(uri)?.let{draft.importImage(it)}?:error("无法读取图片");null}catch(_:OutOfMemoryError){"图片尺寸过大"}catch(e:Throwable){e.message?:"图片导入失败"}
            activity.runOnUiThread{
                importing=false;draft.busy=false
                if(draft.dismissed){draft.dispose();return@runOnUiThread}
                if(!activity.isFinishing){if(error==null){
                    uploadDialog?.dismiss();uploadDialog=null
                    draft.selection="custom";wallpaperRefresh?.invoke();message("图片已就绪，点击确认应用")
                }else message(error)}
            }
        },"wallpaper-preview-import").start()
    }
}
