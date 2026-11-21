package com.tvbridge.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

class HomeAppMenu(private val context:Context,private val launcherApps:LauncherApps,private val host:Host){
    interface Host{
        val scale:Float
        val darkHome:Boolean
        fun tileKey(tile:AppTileView):String
        fun isMoving():Boolean
        fun enterMove(id:String)
        fun refreshApps()
        fun focusEntry(id:String)
        fun focusFirst()
        fun invalidate()
        fun requestLayout()
    }
    private var popup:android.widget.ListPopupWindow?=null
    private var selection=0
    private var entryId:String?=null
    private var choices:List<String> = emptyList()
    var openerDownTime:Long?=null
    var heldConfirmDownTime:Long?=null
    fun isShowing()=popup?.isShowing==true
    fun dismiss(){popup?.dismiss()}

    fun consumeMenuConfirm(event:KeyEvent):Boolean {
        if(event.keyCode!=KeyEvent.KEYCODE_DPAD_CENTER&&event.keyCode!=KeyEvent.KEYCODE_ENTER&&event.keyCode!=KeyEvent.KEYCODE_NUMPAD_ENTER)return false
        if(event.action==KeyEvent.ACTION_UP){
            heldConfirmDownTime=null
            val consume=openerDownTime==event.downTime
            openerDownTime=null
            return consume
        }
        if(openerDownTime==event.downTime)return true
        if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0){
            openerDownTime=null
            heldConfirmDownTime=event.downTime
        }
        return false
    }

    fun handleKey(code:Int):Boolean{
        val popup=popup?.takeIf{it.isShowing}?:return false
        val selected=selection;val entry=entryId?:return false
        when(code){
            KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_MENU->popup.dismiss()
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->if(selected==0)host.enterMove(entry)else popup.performItemClick(selected)
            KeyEvent.KEYCODE_DPAD_UP->selectMenuItem(popup,(selection-1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_DOWN->selectMenuItem(popup,(selection+1).coerceAtMost(choices.lastIndex))
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->if(selected==0)host.enterMove(entry)
            else->return false
        };return true
    }

    fun show(anchor:AppTileView){
        if(popup?.isShowing==true||host.isMoving())return
        val scale=host.scale
        val id=host.tileKey(anchor)
        val entry=anchor.tag as? LauncherApps.Entry
        val bootAction=if(launcherApps.bootApp()==id)"取消开机时打开" else "开机时打开"
        val folder=id.takeIf{it.startsWith("folder:")}?.removePrefix("folder:")
        val choices=if(folder!=null)mutableListOf("移动","重命名文件夹","解散文件夹")
            else mutableListOf("移动","移到文件夹","从主屏幕隐藏","应用信息",bootAction)
        if(entry!=null){
            val flags=try{context.packageManager.getApplicationInfo(entry.component.packageName,0).flags}catch(_:Exception){android.content.pm.ApplicationInfo.FLAG_SYSTEM}
            if(flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM==0)choices.add("卸载应用")else if(flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP!=0)choices.add("卸载更新")
        }
        this.choices=choices
        val popup=android.widget.ListPopupWindow(context);this.popup=popup;entryId=id;selection=0
        popup.anchorView=anchor
        popup.isModal=false
        val rowHeight=(76*scale).toInt()
        val menuPadding=(10*scale).toInt()
        val menuHeight=rowHeight*choices.size+2*menuPadding
        val inset=(12*scale).toInt()
        val frame=android.graphics.Rect().also{anchor.getWindowVisibleDisplayFrame(it)}
        val location=IntArray(2).also{anchor.getLocationOnScreen(it)}
        popup.width=(320*scale).toInt().coerceAtMost((frame.width()-2*inset).coerceAtLeast(1))
        popup.height=menuHeight
        val left=(location[0]+(anchor.width-popup.width)/2).coerceIn(frame.left+inset,(frame.right-popup.width-inset).coerceAtLeast(frame.left+inset))
        val above=location[1]-menuHeight-inset
        val below=location[1]+anchor.height+inset
        val top=(if(above>=frame.top+inset)above else below).coerceIn(frame.top+inset,(frame.bottom-menuHeight-inset).coerceAtLeast(frame.top+inset))
        popup.horizontalOffset=left-location[0]
        popup.verticalOffset=top-location[1]-anchor.height
        popup.setAnimationStyle(0)
        GlassUi.invalidateScene();GlassUi.prepareScene(context)
        popup.setBackgroundDrawable(GlassUi.sceneGlass(anchor,20f,screenLeft=left,screenTop=top))
        popup.setAdapter(object:android.widget.ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,choices){
            override fun getView(position:Int,convert:View?,parent:ViewGroup):View=android.widget.TextView(context).apply{
                text=getItem(position)
                NativeUi.textSize(this,30f)
                gravity=Gravity.CENTER_VERTICAL
                val horizontal=(28*scale).toInt()
                layoutParams=android.widget.AbsListView.LayoutParams(-1,rowHeight)
                setPadding(horizontal,0,horizontal,0)
                isSingleLine=true
                styleMenuRow(this,position,position==selection)
            }
        })
        popup.setOnItemClickListener{_,_,position,_->when(choices[position]){
            "移动"->host.enterMove(id)
            "重命名文件夹"->{popup.dismiss();if(folder!=null)renameFolder(folder)}
            "解散文件夹"->{
                popup.dismiss()
                if(folder!=null)NativeUi.show(android.app.AlertDialog.Builder(context).setTitle("解散文件夹")
                    .setMessage("文件夹内的应用会回到首页。")
                    .setPositiveButton("解散"){_,_->launcherApps.dissolveFolder(folder);host.refreshApps();host.focusFirst()}
                    .setNegativeButton("取消",null))
            }
            "移到文件夹"->{popup.dismiss();chooseFolder(id,anchor)}
            "从主屏幕隐藏"->{popup.dismiss();launcherApps.setHidden(id,true);host.refreshApps();host.focusFirst()}
            bootAction->{popup.dismiss();val on=launcherApps.toggleBootApp(id);NativeUi.notice(context,if(on)"开机时打开 ${anchor.title}" else "已取消开机打开")}
            "应用信息"->{popup.dismiss();if(entry==null){showModuleDetails(anchor);return@setOnItemClickListener};try{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+entry.component.packageName)))}catch(_:Exception){NativeUi.notice(context,"无法打开应用信息")}}
            else->{if(entry==null)return@setOnItemClickListener;popup.dismiss();try{context.startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:"+entry.component.packageName)))}catch(_:Exception){NativeUi.notice(context,"系统未提供卸载入口")}}
        }}
        popup.setOnDismissListener{this.popup=null;entryId=null;host.refreshApps();host.focusEntry(id);host.invalidate()}
        popup.show();popup.listView?.let{it.setPadding(menuPadding,menuPadding,menuPadding,menuPadding);it.divider=null;it.dividerHeight=0;it.setHeaderDividersEnabled(false);it.setFooterDividersEnabled(false);it.selector=ColorDrawable(Color.TRANSPARENT)};anchor.post{if(popup.isShowing)popup.setSelection(0)}
        popup.listView?.setOnItemSelectedListener(object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){
                selection=position
                styleMenuRows(popup)
            }
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){ }
        })
        host.requestLayout();host.invalidate()
    }
    private fun chooseFolder(id:String,anchor:AppTileView){
        val scale=host.scale
        val names=launcherApps.folderNames()
        fun assign(name:String){launcherApps.moveToFolder(id,name);host.refreshApps();host.focusFirst()}
        val rows=android.widget.LinearLayout(context).apply{orientation=android.widget.LinearLayout.VERTICAL}
        var picker:android.app.AlertDialog?=null
        fun back(){picker?.dismiss();show(anchor)}
        fun addRow(title:String,glyph:String,action:()->Unit){
            val row=android.widget.Button(context).apply{
                text=title;isAllCaps=false;isSingleLine=true
                gravity=Gravity.START or Gravity.CENTER_VERTICAL
                setPadding((20*scale).toInt(),0,(20*scale).toInt(),0)
                NativeUi.textSize(this,30f);setTextColor(GlassUi.colors(context))
                background=GlassUi.surface(this,16f,border=false,faint=true,plain=true)
                if(android.os.Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null}
                val size=(32*scale).toInt()
                val icon=AppIcons.Vector(glyph){currentTextColor}.apply{setBounds(0,0,size,size)}
                setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=(16*scale).toInt()
                setOnClickListener{picker?.dismiss();action()};NativeUi.touchClicks(this)
            }
            rows.addView(row,android.widget.LinearLayout.LayoutParams(-1,(76*scale).toInt()).apply{bottomMargin=(8*scale).toInt()})
        }
        addRow("新建文件夹","plus"){
            val field=android.widget.EditText(android.view.ContextThemeWrapper(context,R.style.FolderInput)).apply{
                hint="文件夹名称";isSingleLine=true
                val defaultName=if("文件夹" !in names)"文件夹" else
                    generateSequence(2){it+1}.map{"文件夹 $it"}.first{it !in names}
                setText(defaultName)
                setSelectAllOnFocus(true)
                if(android.os.Build.VERSION.SDK_INT>=29){
                    textCursorDrawable=GradientDrawable().apply{
                        setColor(if(host.darkHome)0xffa9d5ff.toInt() else 0xff245a94.toInt())
                        setSize((4*scale).toInt().coerceAtLeast(2),-1)
                    }
                }
                filters=arrayOf(android.text.InputFilter.LengthFilter(20))
                NativeUi.textSize(this,32f);setTextColor(GlassUi.ink(context))
                setHintTextColor(GlassUi.ink(context) and 0x75ffffff)
                background=GlassUi.surface(this,14f,faint=true,plain=true)
                setPadding((20*scale).toInt(),(14*scale).toInt(),(20*scale).toInt(),(14*scale).toInt())
            }
            val editor=NativeUi.show(android.app.AlertDialog.Builder(context).setTitle("新建文件夹").setView(field)
                .setPositiveButton("创建",null).setNegativeButton("取消",null),widthDp=280,initialFocus=field)
            editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener{
                val name=field.text.toString().trim()
                if(name.isBlank())field.error="请输入文件夹名称"
                else if(name in names)field.error="文件夹已存在"
                else{editor.dismiss();assign(name)}
            }
        }
        names.forEach{name->addRow(name,"folder"){assign(name)}}
        val scroll=android.widget.ScrollView(context).apply{isVerticalScrollBarEnabled=false;addView(rows)}
        picker=NativeUi.show(android.app.AlertDialog.Builder(context).setTitle("移到文件夹").setView(scroll)
            .setNegativeButton("返回"){_,_->back()},widthDp=220,anchor=anchor,initialFocus=rows.getChildAt(0))
        picker.setOnCancelListener{show(anchor)}
    }
    private fun renameFolder(name:String){
        val field=android.widget.EditText(context).apply{
            setText(name);selectAll();isSingleLine=true
            filters=arrayOf(android.text.InputFilter.LengthFilter(20))
            setTextColor(GlassUi.ink(context))
        }
        val dialog=NativeUi.show(android.app.AlertDialog.Builder(context).setTitle("重命名文件夹").setView(field)
            .setPositiveButton("保存",null).setNegativeButton("取消",null),widthDp=280,initialFocus=field)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener{
            val next=field.text.toString().trim()
            if(next==name){dialog.dismiss();return@setOnClickListener}
            if(!launcherApps.renameFolder(name,next)){field.error="请输入未使用的名称";return@setOnClickListener}
            dialog.dismiss()
            host.refreshApps()
            host.focusEntry("folder:"+next)
        }
    }
    private fun showModuleDetails(tile:AppTileView){
        val description=when(host.tileKey(tile)){
            "module:live"->"观看直播频道。"
            "module:files"->"查看和管理手机发送到 One 的文件。"
            LauncherApps.STOCK_HOME_ID->"打开电视自带的原厂桌面。"
            else->"浏览网页和播放网页视频。"
        }
        NativeUi.show(android.app.AlertDialog.Builder(context).setTitle(tile.title).setMessage(description).setPositiveButton("关闭",null))
    }
    private fun menuItemBackground(selected:Boolean)=GradientDrawable().apply{
        cornerRadius=10*host.scale
        setColor(if(selected)0x339dc4ed else Color.TRANSPARENT)
        if(selected)setStroke((2*host.scale).toInt().coerceAtLeast(1),0x759dc4ed)
    }
    private fun styleMenuRow(row:android.widget.TextView,position:Int,selected:Boolean){
        row.isSelected=selected
        row.background=menuItemBackground(selected)
        row.setTypeface(null,if(selected)Typeface.BOLD else Typeface.NORMAL)
        val destructive=choices.getOrNull(position)?.startsWith("卸载")==true
        row.setTextColor(if(destructive)0xffffa3ad.toInt() else 0xffedf2fa.toInt())
    }
    private fun styleMenuRows(popup:android.widget.ListPopupWindow){
        val list=popup.listView?:return
        for(i in 0 until list.childCount){
            val row=list.getChildAt(i) as? android.widget.TextView?:continue
            val position=list.firstVisiblePosition+i
            styleMenuRow(row,position,position==selection)
        }
    }
    private fun selectMenuItem(popup:android.widget.ListPopupWindow,index:Int){
        selection=index
        popup.setSelection(index)
        styleMenuRows(popup)
    }
}
