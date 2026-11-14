package com.tvbridge.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter

class TvHomeView(context:Context,private val liveAction:Runnable,private val filesAction:Runnable,settings:Runnable,refresh:Runnable,private val browseAction:Runnable=Runnable{}):ViewGroup(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular=Typeface.create("sans-serif",Typeface.NORMAL)
    private val bold=Typeface.create("sans-serif",Typeface.BOLD)
    private val clockFont=Typeface.create("sans-serif",Typeface.BOLD)
    private val timeFormat=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault())
    private val dateFormat=java.text.SimpleDateFormat("yyyy年M月d日  EEEE",java.util.Locale.SIMPLIFIED_CHINESE)
    private val tiles=mutableListOf<AppTileView>()
    private var appMenu:android.widget.ListPopupWindow?=null
    private var moving:String?=null
    private var menuSelection=0
    private var menuEntry:String?=null
    private val controlButton=object:Button(context){
        private val icon=AppIcons.Vector("settings"){currentTextColor}
        override fun onDraw(canvas:Canvas){
            val size=(40*NativeUi.scale(context)).toInt()
            val left=(width-size)/2;val top=(height-size)/2
            icon.setBounds(left,top,left+size,top+size);icon.draw(canvas)
        }
    }
    private val launcherApps=LauncherApps(context)
    private val appRow=android.widget.GridLayout(context).apply{columnCount=5;clipChildren=false;clipToPadding=false}
    private val appRail=android.widget.FrameLayout(context).apply{clipChildren=false;addView(appRow)}
    private var scale=1f;private var gridMargin=0;private var tileGap=0;private var tileWidth=0;private var tileHeight=0;private var oy=0f
    private var styledScale=-1f
    private var styledWidth=-1
    private var qr:Bitmap?=null;private var payload="";private var address="";private var code="";private var connected=0;private var notice=""
    var drawsBackdrop=true
    private var darkHome=HomeAppearance.dark(context)
    private var appearanceKey=HomeAppearance.key(context)
    private val ink get()=GlassUi.ink(context,darkHome)
    private var appSignature:String?=null
    private var rememberedFocus=""
    private var lastGridFocus=""
    private fun tileKey(tile:AppTileView)=(tile.tag as? LauncherApps.Entry)?.component?.flattenToString() ?: tile.tag as String
    private var lastClockMinute=-1L
    private var connectionLabel=java.lang.ref.WeakReference<android.widget.TextView>(null)
    private var connectionImage=java.lang.ref.WeakReference<android.widget.ImageView>(null)
    private var connectionDot=java.lang.ref.WeakReference<View>(null)
    private val rows get()=maxOf(1,(tiles.size+4)/5)
    // Aerial home geometry on the 1920x1080 design canvas (its 960x540dp values doubled):
    // 5 columns, 56dp side margins, 28dp column gaps, row pitch = tile height + 50dp name room.
    // The shelf row rests at topRowY with the next row peeking; a focused row deeper in the
    // grid rests with its top at restY; collapseDip is the shelf-to-grid scroll distance over
    // which the clock and the plate fade out.
    private val cols=5
    private val topRowY get()=956f-tileHeight/scale
    private val restY=409f
    private val collapseDip=382f
    private val pitchY get()=tileHeight/scale+100f
    private val plateView=View(context)
    private var scrollY=0f
    private var scrollTarget=0f
    private var scrollVel=0f
    private var springPosted=false
    private var lastFrameNanos=0L
    private val choreographer=android.view.Choreographer.getInstance()
    private val springFrame=object:android.view.Choreographer.FrameCallback{
        override fun doFrame(frameTimeNanos:Long){
            springPosted=false
            val dt=if(lastFrameNanos==0L)0.016f else ((frameTimeNanos-lastFrameNanos)/1e9f).coerceIn(0.001f,0.05f)
            lastFrameNanos=frameTimeNanos
            // Aerial's scroll spring: dampingRatio 1 (no overshoot), stiffness 280.
            val accel=-280f*(scrollY-scrollTarget)-33.5f*scrollVel
            scrollVel+=accel*dt;scrollY+=scrollVel*dt
            if(Math.abs(scrollY-scrollTarget)<0.6f&&Math.abs(scrollVel)<8f){scrollY=scrollTarget;scrollVel=0f;applyScroll();return}
            applyScroll()
            springPosted=true;choreographer.postFrameCallback(this)
        }
    }
    private fun applyScroll(){applyRowAlphas();requestLayout();invalidate()}
    /** Aerial's hiddenFrom rule: rows below the shelf are out of sight at rest and fade in
     *  as Home lifts into the grid. */
    private fun applyRowAlphas(){val a=gridAlpha();tiles.forEachIndexed{i,tile->tile.alpha=if(i<cols)1f else a}}
    private fun animateScrollTo(target:Float){
        scrollTarget=target.coerceAtLeast(0f)
        if(!springPosted&&scrollY!=scrollTarget){springPosted=true;lastFrameNanos=0L;choreographer.postFrameCallback(springFrame)}
    }
    private fun scrollTargetFor(row:Int):Float{
        if(row<=0)return 0f
        val wanted=topRowY+pitchY*row-restY
        val bottom=topRowY+pitchY*rows+72f-1080f
        return minOf(wanted,maxOf(bottom,topRowY+pitchY-restY))
    }
    private val liftFrac get()=(scrollY/collapseDip).coerceIn(0f,1f)
    private fun shelfAlpha()=(1f-1.6f*liftFrac).coerceIn(0f,1f)
    private fun gridAlpha()=(2.5f*liftFrac).coerceIn(0f,1f)
    private fun onTileFocused(tile:AppTileView){val index=tiles.indexOf(tile);if(index>=0)animateScrollTo(scrollTargetFor(index/cols))}

    private data class Module(val id:String,val title:String,val glyph:String,val top:Int,val bottom:Int,val run:Runnable)
    private val modules get()=AppModules.all.zip(listOf(liveAction,filesAction,browseAction)).map{Module(it.first.id,it.first.title,it.first.glyph,it.first.top,it.first.bottom,it.second)}

    fun refreshAppearance(){
        val dark=HomeAppearance.dark(context)
        if(appearanceKey!=HomeAppearance.key(context)){appearanceKey=HomeAppearance.key(context);darkHome=dark;styledScale=-1f;appSignature=null;refreshApps();requestLayout();invalidate()}
    }

    init{setWillNotDraw(false);isFocusable=false;clipChildren=false
        controlButton.apply{text="";contentDescription="控制中心";id=generateViewId();isAllCaps=false;isFocusableInTouchMode=true;if(android.os.Build.VERSION.SDK_INT>=21)backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false;setTextColor(Color.WHITE);setPadding(0,0,0,0);minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setOnClickListener{rememberedFocus="控制中心";settings.run()}}
        controlButton.setOnFocusChangeListener{_,focus->if(focus)rememberedFocus="控制中心";controlButton.animate().scaleX(if(focus)1.08f else 1f).scaleY(if(focus)1.08f else 1f).setDuration(120).start()}
        controlButton.nextFocusLeftId=controlButton.id;controlButton.nextFocusRightId=controlButton.id;controlButton.nextFocusUpId=controlButton.id
        NativeUi.touchClicks(controlButton)
        plateView.isFocusable=false
        addView(plateView)
        addView(appRail)
        addView(controlButton)
        refreshApps()
    }

    fun refreshApps(){
        val all=launcherApps.orderedIds()
        val hidden=launcherApps.hiddenIds().toSet()
        val folders=launcherApps.folderLayout(all).mapKeys{"folder:"+it.key}.mapValues{(_,members)->members.filterNot{it in hidden}}.filterValues{it.isNotEmpty()}
        val grouped=folders.entries.flatMap{(folder,members)->members.map{it to folder}}.toMap()
        val order=all.filterNot{it in hidden}.map{grouped[it]?:it}.distinct()
        val entries=launcherApps.installed().associateBy{it.component.flattenToString()}
        val builtIns=modules.associateBy{it.id}
        val signature="B2:${AppArtwork.version()}:"+order.joinToString{id->val entry=entries[id];"$id:${launcherApps.appTitle(id)}:${entry?.updated}"}+":"+folders+":"+darkHome
        if(appSignature==signature){requestLayout();return}
        val focused=(findFocus() as? AppTileView)?.let(::tileKey)
        appSignature=signature
        appRow.removeAllViews();tiles.clear()
        fun baseTile(title:String):AppTileView{
            val tile=AppTileView(context)
            tile.title=title;tile.contentDescription=title;tile.id=generateViewId()
            tile.focusHook={focus->if(focus){val key=tileKey(tile);rememberedFocus=key;lastGridFocus=key;controlButton.nextFocusDownId=tile.id;onTileFocused(tile)};invalidate()}
            tiles.add(tile);appRow.addView(tile)
            return tile
        }
        order.forEach{id->
            if(id in folders){
                val name=id.removePrefix("folder:")
                val tile=baseTile(name)
                tile.tag=id
                tile.role="module"
                val previews=folders.getValue(id).take(9).mapNotNull{key->
                    val entry=entries[key]
                    if(entry!=null)runCatching{
                        val banner=AppArtwork.tile(context,entry.component.packageName)?:launcherApps.banner(entry)
                        (banner?:launcherApps.icon(entry))?.let{it to (banner!=null)}
                    }.getOrNull()
                    else moduleArtwork(key)?.let{it to true}
                }
                tile.banner=HomeFolderArtwork(previews)
                tile.setOnClickListener{if(!placeMovingTile(tile))showFolder(name)}
                tile.setOnLongClickListener{openerDownTime=heldConfirmDownTime;showAppMenu(tile);true}
                NativeUi.touchClicks(tile);tile.isFocusableInTouchMode=true
                return@forEach
            }
            val module=builtIns[id]
            if(module!=null){
                val tile=baseTile(launcherApps.displayTitle(id,module.title))
                tile.role="module";tile.tag=id
                tile.banner=moduleArtwork(id)
                tile.tintTop=module.top;tile.tintBottom=module.bottom
                tile.setOnClickListener{rememberedFocus=id;if(!placeMovingTile(tile))module.run.run()}
                tile.setOnLongClickListener{openerDownTime=heldConfirmDownTime;showAppMenu(tile);true}
                NativeUi.touchClicks(tile);tile.isFocusableInTouchMode=true
                return@forEach
            }
            val entry=entries[id]?:return@forEach
            val tile=baseTile(launcherApps.displayTitle(id,entry.label))
            tile.role="app";tile.tag=entry
            tile.banner=try{AppArtwork.tile(context,entry.component.packageName)?:launcherApps.banner(entry)}catch(_:Exception){null}
            if(tile.banner==null)tile.icon=try{launcherApps.icon(entry)}catch(_:Exception){null}
            try{val tint=launcherApps.tileTint(entry);tile.tintTop=tint[0];tile.tintBottom=tint[1]}catch(_:Exception){}
            if(tile.banner!=null){tile.tintTop=0;tile.tintBottom=0}
            tile.setOnClickListener{rememberedFocus=tileKey(tile);if(!placeMovingTile(tile)&&!launcherApps.launch(entry))NativeUi.notice(context,"应用已移除或无法打开")}
            tile.setOnLongClickListener{openerDownTime=heldConfirmDownTime;showAppMenu(tile);true}
            NativeUi.touchClicks(tile);tile.isFocusableInTouchMode=true
        }
        tiles.forEachIndexed{i,tile->
            tile.nextFocusLeftId=tiles[if(i%cols==0)i else i-1].id
            tile.nextFocusRightId=tiles.getOrNull(if(i%cols==cols-1)i else i+1)?.id?:tile.id
            tile.nextFocusUpId=if(i>=cols)tiles[i-cols].id else controlButton.id
            tile.nextFocusDownId=if(i/cols+1<rows)tiles[rowIndex(i/cols+1,i%cols)].id else tile.id
        }
        controlButton.nextFocusDownId=(tiles.firstOrNull{tileKey(it)==lastGridFocus}?:tiles.firstOrNull())?.id?:controlButton.id
        applyRowAlphas()
        styledScale=-1f
        requestLayout()
        if(focused!=null)(tiles.firstOrNull{tileKey(it)==focused}?:tiles.firstOrNull())?.requestFocus()
    }

    private fun rowIndex(row:Int,column:Int)=(row*cols+column).coerceAtMost(tiles.lastIndex)

    private fun focusEntry(entry:String){tiles.firstOrNull{tileKey(it)==entry}?.requestFocus()}
    private fun finishMove(){val entry=moving?:return;moving=null;refreshApps();focusEntry(entry);invalidate()}
    private fun enterMove(entry:String){appMenu?.dismiss();moving=entry;refreshApps();focusEntry(entry);invalidate()}
    private fun moveEntry(entry:String,offset:Int){
        val from=tiles.indexOfFirst{tileKey(it)==entry}
        if(from<0)return
        val target=when(offset){
            -1,1->(from+offset).takeIf{it in tiles.indices&&it/cols==from/cols}
            else->(from/cols+offset/cols).takeIf{it in 0 until rows}?.let{rowIndex(it,from%cols)}
        }?:return
        val all=launcherApps.orderedIds()
        val targetId=folderRepresentative(tileKey(tiles[target]))
        if(target==from||!launcherApps.move(folderRepresentative(entry),all.indexOf(targetId)-all.indexOf(folderRepresentative(entry))))return
        appSignature=null;refreshApps()
        focusEntry(entry)
        invalidate()
    }
    private fun placeMovingTile(tile:AppTileView):Boolean{
        val source=moving?:return false
        launcherApps.swap(folderRepresentative(source),folderRepresentative(tileKey(tile)))
        finishMove()
        return true
    }
    private fun folderRepresentative(id:String)=if(id.startsWith("folder:"))
        launcherApps.orderedIds().firstOrNull{it in launcherApps.folderMembers(id.removePrefix("folder:"))}?:id else id

    private fun moduleArtwork(id:String):android.graphics.drawable.Drawable?{
        val resource=when(id){
            "module:live"->R.drawable.home_live_wide
            "module:files"->R.drawable.home_files_wide
            "module:browser"->R.drawable.home_browser_wide
            else->return null
        }
        return androidx.core.content.ContextCompat.getDrawable(context,resource)
    }

    private fun showFolder(name:String){
        val hidden=launcherApps.hiddenIds().toSet()
        val entries=launcherApps.installed().associateBy{it.component.flattenToString()}
        val members=launcherApps.folderLayout(launcherApps.orderedIds())[name].orEmpty().filter{it !in hidden}.mapNotNull{id->launcherApps.appTitle(id)?.let{id to it}}
        if(members.isEmpty())return
        val dialog=android.app.Dialog(context)
        val backdrop=object:android.widget.FrameLayout(context){
            private val backdropPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            init{setWillNotDraw(false);setOnClickListener{dialog.dismiss()}}
            override fun onDraw(canvas:Canvas){
                HomeAppearance.drawBackdrop(context,canvas,width,height,darkHome,backdropPaint)
                canvas.drawColor(0x99000000.toInt())
            }
        }
        val content=android.widget.LinearLayout(context).apply{
            orientation=android.widget.LinearLayout.VERTICAL
            clipChildren=false;clipToPadding=false
            setPadding(gridMargin,(112*scale).toInt(),gridMargin,(96*scale).toInt())
        }
        val heading=android.widget.TextView(context).apply{
            text=name
            setTextColor(Color.WHITE)
            typeface=bold
            NativeUi.textSize(this,60f)
            isSingleLine=true
        }
        content.addView(heading,android.widget.LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=(72*scale).toInt()})
        var first:AppTileView?=null
        members.chunked(cols).forEach{items->
            val row=android.widget.LinearLayout(context).apply{clipChildren=false;clipToPadding=false}
            content.addView(row,android.widget.LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=(70*scale).toInt()})
            items.forEachIndexed{column,(id,title)->
                val tile=AppTileView(context).apply{
                    this.title=title
                    if(id.startsWith("module:"))role="module"
                    contentDescription=title
                    val entry=entries[id]
                    if(entry!=null){
                        banner=AppArtwork.tile(context,entry.component.packageName)?:launcherApps.banner(entry)
                        if(banner==null)icon=launcherApps.icon(entry)
                    }else banner=moduleArtwork(id)
                    setOnClickListener{dialog.dismiss();if(!launchHomeApp(id))NativeUi.notice(context,"应用已移除或无法打开")}
                    setOnLongClickListener{
                        NativeUi.show(android.app.AlertDialog.Builder(context).setTitle(title)
                            .setItems(arrayOf("移出文件夹")){_,_->
                                launcherApps.moveToFolder(id,null);dialog.dismiss();refreshApps();showFolder(name)
                            }.setNegativeButton("取消",null),widthDp=250,listIcons=listOf("folder"))
                        true
                    }
                    NativeUi.touchClicks(this);isFocusableInTouchMode=true
                }
                if(first==null)first=tile
                row.addView(tile,android.widget.LinearLayout.LayoutParams(tileWidth,tileHeight).apply{if(column>0)leftMargin=tileGap})
            }
        }
        val scroll=object:android.widget.ScrollView(context){
            private val blankTap=android.view.GestureDetector(context,object:android.view.GestureDetector.SimpleOnGestureListener(){
                override fun onDown(event:android.view.MotionEvent)=true
                override fun onSingleTapUp(event:android.view.MotionEvent):Boolean{
                    performClick()
                    return true
                }
            })
            override fun onTouchEvent(event:android.view.MotionEvent):Boolean{
                val scrolling=super.onTouchEvent(event)
                return blankTap.onTouchEvent(event)||scrolling
            }
        }.apply{
            isVerticalScrollBarEnabled=false
            setOnClickListener{dialog.dismiss()}
            isFocusable=false
            addView(content)
        }
        backdrop.addView(scroll,android.widget.FrameLayout.LayoutParams(-1,-1))
        dialog.setContentView(backdrop)
        dialog.window?.apply{
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            decorView.systemUiVisibility=this@TvHomeView.rootView.systemUiVisibility
            if(android.os.Build.VERSION.SDK_INT>=28)attributes=attributes.apply{
                layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        dialog.show()
        dialog.window?.setLayout(-1,-1)
        first?.requestFocusFromTouch()
    }
    private fun launchHomeApp(id:String):Boolean{
        modules.firstOrNull{it.id==id}?.let{it.run.run();return true}
        return launcherApps.installed().firstOrNull{it.component.flattenToString()==id}?.let(launcherApps::launch)==true
    }
    private fun chooseFolder(id:String,anchor:AppTileView){
        val names=launcherApps.folderNames()
        fun assign(name:String){launcherApps.moveToFolder(id,name);refreshApps();focusFirst()}
        val rows=android.widget.LinearLayout(context).apply{orientation=android.widget.LinearLayout.VERTICAL}
        var picker:android.app.AlertDialog?=null
        fun back(){picker?.dismiss();showAppMenu(anchor)}
        fun addRow(title:String,glyph:String,action:()->Unit){
            val row=android.widget.Button(context).apply{
                text=title;isAllCaps=false;isSingleLine=true
                gravity=Gravity.START or Gravity.CENTER_VERTICAL
                setPadding((20*scale).toInt(),0,(20*scale).toInt(),0)
                NativeUi.textSize(this,30f);setTextColor(GlassUi.colors(context))
                background=GlassUi.surface(this,16f,border=false,faint=true)
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
                        setColor(if(darkHome)0xffa9d5ff.toInt() else 0xff245a94.toInt())
                        setSize((4*scale).toInt().coerceAtLeast(2),-1)
                    }
                }
                filters=arrayOf(android.text.InputFilter.LengthFilter(20))
                NativeUi.textSize(this,32f);setTextColor(GlassUi.ink(context))
                setHintTextColor(GlassUi.ink(context) and 0x75ffffff)
                background=GlassUi.surface(this,14f,faint=true)
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
        picker.setOnCancelListener{showAppMenu(anchor)}
    }

    private var menuChoices:List<String> = emptyList()
    private fun showAppMenu(anchor:AppTileView){
        if(appMenu?.isShowing==true||moving!=null)return
        val id=tileKey(anchor)
        val entry=anchor.tag as? LauncherApps.Entry
        val bootAction=if(launcherApps.bootApp()==id)"取消开机时打开" else "开机时打开"
        val folder=id.takeIf{it.startsWith("folder:")}?.removePrefix("folder:")
        val choices=if(folder!=null)mutableListOf("移动","重命名文件夹","解散文件夹")
            else mutableListOf("移动","移到文件夹","从主屏幕隐藏","应用信息",bootAction)
        if(entry!=null){
            val flags=try{context.packageManager.getApplicationInfo(entry.component.packageName,0).flags}catch(_:Exception){android.content.pm.ApplicationInfo.FLAG_SYSTEM}
            if(flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM==0)choices.add("卸载应用")else if(flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP!=0)choices.add("卸载更新")
        }
        menuChoices=choices
        val popup=android.widget.ListPopupWindow(context);appMenu=popup;menuEntry=id;moving=null;menuSelection=0
        popup.anchorView=anchor
        popup.isModal=false
        val rowHeight=(76*scale).toInt()
        val menuPadding=(10*scale).toInt()
        val menuHeight=rowHeight*choices.size+2*menuPadding
        val inset=(12*scale).toInt()
        val frame=android.graphics.Rect().also{anchor.getWindowVisibleDisplayFrame(it)}
        val location=IntArray(2).also{anchor.getLocationOnScreen(it)}
        popup.width=(440*scale).toInt().coerceAtMost((frame.width()-2*inset).coerceAtLeast(1))
        popup.height=menuHeight
        val left=(location[0]+(anchor.width-popup.width)/2).coerceIn(frame.left+inset,(frame.right-popup.width-inset).coerceAtLeast(frame.left+inset))
        val above=location[1]-menuHeight-inset
        val below=location[1]+anchor.height+inset
        val top=(if(above>=frame.top+inset)above else below).coerceIn(frame.top+inset,(frame.bottom-menuHeight-inset).coerceAtLeast(frame.top+inset))
        popup.horizontalOffset=left-location[0]
        popup.verticalOffset=top-location[1]-anchor.height
        popup.setAnimationStyle(0)
        popup.setBackgroundDrawable(GlassUi.homeGlass(anchor,20f,screenLeft=left,screenTop=top))
        popup.setAdapter(object:android.widget.ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,choices){
            override fun getView(position:Int,convert:View?,parent:ViewGroup):View=android.widget.TextView(context).apply{
                text=getItem(position)
                NativeUi.textSize(this,30f)
                gravity=Gravity.CENTER_VERTICAL
                val horizontal=(28*scale).toInt()
                layoutParams=android.widget.AbsListView.LayoutParams(-1,rowHeight)
                setPadding(horizontal,0,horizontal,0)
                isSingleLine=true
                styleMenuRow(this,position,position==menuSelection)
            }
        })
        popup.setOnItemClickListener{_,_,position,_->when(choices[position]){
            "移动"->enterMove(id)
            "重命名文件夹"->{popup.dismiss();if(folder!=null)renameFolder(folder)}
            "解散文件夹"->{
                popup.dismiss()
                if(folder!=null)NativeUi.show(android.app.AlertDialog.Builder(context).setTitle("解散文件夹")
                    .setMessage("文件夹内的应用会回到首页。")
                    .setPositiveButton("解散"){_,_->launcherApps.dissolveFolder(folder);refreshApps();focusFirst()}
                    .setNegativeButton("取消",null))
            }
            "移到文件夹"->{popup.dismiss();chooseFolder(id,anchor)}
            "从主屏幕隐藏"->{popup.dismiss();launcherApps.setHidden(id,true);refreshApps();focusFirst()}
            bootAction->{popup.dismiss();val on=launcherApps.toggleBootApp(id);NativeUi.notice(context,if(on)"开机时打开 ${anchor.title}" else "已取消开机打开")}
            "应用信息"->{popup.dismiss();if(entry==null){showModuleDetails(anchor);return@setOnItemClickListener};try{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+entry.component.packageName)))}catch(_:Exception){NativeUi.notice(context,"无法打开应用信息")}}
            else->{if(entry==null)return@setOnItemClickListener;popup.dismiss();try{context.startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:"+entry.component.packageName)))}catch(_:Exception){NativeUi.notice(context,"系统未提供卸载入口")}}
        }}
        popup.setOnDismissListener{appMenu=null;menuEntry=null;moving=null;refreshApps();focusEntry(id);invalidate()}
        popup.show();popup.listView?.let{it.setPadding(menuPadding,menuPadding,menuPadding,menuPadding);it.divider=null;it.dividerHeight=0;it.setHeaderDividersEnabled(false);it.setFooterDividersEnabled(false);it.selector=ColorDrawable(Color.TRANSPARENT)};post{if(popup.isShowing)popup.setSelection(0)}
        popup.listView?.setOnItemSelectedListener(object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){
                menuSelection=position
                styleMenuRows(popup)
            }
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){ }
        })
        requestLayout();invalidate()
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
            refreshApps()
            focusEntry("folder:"+next)
        }
    }
    private fun showModuleDetails(tile:AppTileView){
        val description=when(tileKey(tile)){
            "module:live"->"观看直播频道，在设置的直播管理中切换直播方式。"
            "module:files"->"查看和管理手机发送到 One 的文件。"
            else->"浏览网页和播放网页视频。"
        }
        NativeUi.show(android.app.AlertDialog.Builder(context).setTitle(tile.title).setMessage(description).setPositiveButton("关闭",null))
    }
    private fun menuItemBackground(selected:Boolean)=GradientDrawable().apply{
        cornerRadius=10*scale
        setColor(if(selected)0x339dc4ed else Color.TRANSPARENT)
        if(selected)setStroke((2*scale).toInt().coerceAtLeast(1),0x759dc4ed)
    }
    private fun styleMenuRow(row:android.widget.TextView,position:Int,selected:Boolean){
        row.isSelected=selected
        row.background=menuItemBackground(selected)
        row.setTypeface(null,if(selected)Typeface.BOLD else Typeface.NORMAL)
        val destructive=menuChoices.getOrNull(position)?.startsWith("卸载")==true
        row.setTextColor(if(destructive)0xffffa3ad.toInt() else 0xffedf2fa.toInt())
    }
    private fun styleMenuRows(popup:android.widget.ListPopupWindow){
        val list=popup.listView?:return
        for(i in 0 until list.childCount){
            val row=list.getChildAt(i) as? android.widget.TextView?:continue
            val position=list.firstVisiblePosition+i
            styleMenuRow(row,position,position==menuSelection)
        }
    }
    private fun selectMenuItem(popup:android.widget.ListPopupWindow,index:Int){
        menuSelection=index
        popup.setSelection(index)
        styleMenuRows(popup)
    }
    private var openerDownTime:Long?=null
    private var heldConfirmDownTime:Long?=null

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
        moving?.let{entry->
            when(code){
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_MENU->finishMove()
                KeyEvent.KEYCODE_DPAD_LEFT->moveEntry(entry,-1)
                KeyEvent.KEYCODE_DPAD_RIGHT->moveEntry(entry,1)
                KeyEvent.KEYCODE_DPAD_UP->moveEntry(entry,-cols)
                KeyEvent.KEYCODE_DPAD_DOWN->moveEntry(entry,cols)
                else->return false
            };return true
        }
        appMenu?.takeIf{it.isShowing}?.let{popup->
            val selected=menuSelection;val entry=menuEntry?:return false
            when(code){
                KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_MENU->popup.dismiss()
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->if(selected==0)enterMove(entry)else popup.performItemClick(selected)
                KeyEvent.KEYCODE_DPAD_UP->selectMenuItem(popup,(menuSelection-1).coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_DOWN->selectMenuItem(popup,(menuSelection+1).coerceAtMost(menuChoices.lastIndex))
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->if(selected==0)enterMove(entry)
                else->return false
            };return true
        }
        if(code==KeyEvent.KEYCODE_BACK&&(scrollY>1f||(findFocus() as? AppTileView)?.let{tiles.indexOf(it)>=cols}==true)){
            val focused=findFocus() as? AppTileView
            val column=focused?.let{tiles.indexOf(it)%cols}?:0
            (tiles.getOrNull(column)?:tiles.firstOrNull())?.requestFocus()
            animateScrollTo(0f)
            return true
        }
        if(code==KeyEvent.KEYCODE_MENU){val tile=findFocus() as? AppTileView;if(tile!=null){showAppMenu(tile);return true}}
        return false
    }
    // Touch: the grid itself drags, as in Aerial (its Compose grid is directly draggable with
    // decay). The phone pad reaches here as real injected swipes (ADB input swipe), so a
    // vertical drag pulls the grid, and on release it settles on the nearest row with focus.
    private var touchDownX=0f;private var touchDownY=0f;private var touchStartScroll=0f
    private var touchDragging=false;private var touchVel=0f;private var touchLastY=0f;private var touchLastMs=0L
    private fun maxScroll()=scrollTargetFor((rows-1).coerceAtLeast(0))
    private fun cancelSpring(){choreographer.removeFrameCallback(springFrame);springPosted=false;scrollVel=0f;scrollTarget=scrollY}
    private val touchSlop=android.view.ViewConfiguration.get(context).scaledTouchSlop
    private fun startDragIfNeeded(ev:android.view.MotionEvent):Boolean{
        val dy=kotlin.math.abs(ev.y-touchDownY)
        if(rows<2||dy<=touchSlop||dy<=kotlin.math.abs(ev.x-touchDownX))return false
        touchDragging=true
        touchStartScroll=scrollY
        cancelSpring()
        return true
    }
    override fun onInterceptTouchEvent(ev:android.view.MotionEvent):Boolean{
        return when(ev.actionMasked){
            android.view.MotionEvent.ACTION_DOWN->{
                touchDownX=ev.x;touchDownY=ev.y;touchDragging=false;touchVel=0f;touchLastY=ev.y;touchLastMs=ev.eventTime
                false
            }
            android.view.MotionEvent.ACTION_MOVE->touchDragging||startDragIfNeeded(ev)
            else->false
        }
    }
    override fun onTouchEvent(ev:android.view.MotionEvent):Boolean{
        when(ev.actionMasked){
            android.view.MotionEvent.ACTION_DOWN->return true
            android.view.MotionEvent.ACTION_MOVE->{
                if(touchDragging||startDragIfNeeded(ev)){
                    scrollY=(touchStartScroll+(touchDownY-ev.y)/scale).coerceIn(0f,maxScroll())
                    val now=ev.eventTime;val dt=(now-touchLastMs).coerceAtLeast(1)
                    touchVel=touchVel*0.7f+((touchLastY-ev.y)/scale)/(dt/1000f)*0.3f
                    touchLastY=ev.y;touchLastMs=now
                    applyScroll()
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL->{
                if(!touchDragging&&ev.actionMasked==android.view.MotionEvent.ACTION_UP&&moving!=null)finishMove()
                if(touchDragging){
                    touchDragging=false
                    val velocity=if(ev.actionMasked==android.view.MotionEvent.ACTION_UP&&ev.eventTime-touchLastMs<90)touchVel else 0f
                    animateScrollTo((scrollY+(velocity*0.14f).coerceIn(-pitchY,pitchY)).coerceIn(0f,maxScroll()))
                    return true
                }
            }
        }
        return true
    }
    override fun onGenericMotionEvent(ev:android.view.MotionEvent):Boolean{
        if(ev.action==android.view.MotionEvent.ACTION_SCROLL){
            val v=ev.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
            val index=(findFocus() as? AppTileView)?.let{tiles.indexOf(it)}?:-1
            if(index>=0&&v!=0f){
                val row=(index/cols+if(v<0)1 else -1).coerceIn(0,rows-1)
                tiles[rowIndex(row,index%cols)].requestFocus()
                return true
            }
        }
        return super.onGenericMotionEvent(ev)
    }
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);if(visibility!=View.VISIBLE){appMenu?.dismiss();moving=null}}
    override fun onDetachedFromWindow(){appMenu?.dismiss();cancelSpring();super.onDetachedFromWindow()}

    override fun onMeasure(width:Int,height:Int){
        val w=MeasureSpec.getSize(width);val h=MeasureSpec.getSize(height);setMeasuredDimension(w,h)
        scale=minOf(w/1920f,h/1080f).coerceAtLeast(0.001f);oy=(h-1080*scale)/2
        if(styledScale!=scale||styledWidth!=w){
            styledWidth=w
            gridMargin=(112*scale).toInt()
            tileGap=(56*scale).toInt()
            tileWidth=((w-2*gridMargin-(cols-1)*tileGap)/cols).coerceAtLeast(0)
            tileHeight=(tileWidth*9f/16f).toInt()
            styledScale=scale
            controlButton.setTextColor(Color.WHITE);controlButton.background=GlassUi.surface(controlButton,GlassUi.CIRCLE_RADIUS)
            plateView.background=GlassUi.homeGlass(plateView,64f)
            tiles.forEachIndexed{i,tile->
                tile.layoutParams=android.widget.GridLayout.LayoutParams().apply{
                    this.width=tileWidth;this.height=tileHeight
                    columnSpec=android.widget.GridLayout.spec(i%5);rowSpec=android.widget.GridLayout.spec(i/5)
                    marginEnd=if(i%5==4)0 else tileGap;bottomMargin=(100*scale).toInt()
                }
            }
        }
        appRail.measure(MeasureSpec.makeMeasureSpec((cols*tileWidth+(cols-1)*tileGap),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((rows*(tileHeight+(100*scale).toInt())),MeasureSpec.EXACTLY))
        plateView.measure(MeasureSpec.makeMeasureSpec((w-(120*scale).toInt()).coerceAtLeast(0),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((tileHeight+(80*scale).toInt()),MeasureSpec.EXACTLY))
        controlButton.measure(MeasureSpec.makeMeasureSpec((76*scale).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((76*scale).toInt(),MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        val railTop=(oy+(topRowY-scrollY)*scale).toInt()
        val ax=gridMargin
        appRail.layout(ax,railTop,ax+appRail.measuredWidth,railTop+appRail.measuredHeight)
        val plateLeft=(60*scale).toInt();val plateTop=(oy+(topRowY-40)*scale).toInt()
        plateView.layout(plateLeft,plateTop,plateLeft+plateView.measuredWidth,plateTop+plateView.measuredHeight)
        val lift=(liftFrac/.55f).coerceIn(0f,1f)
        val dissolve=lift*lift*(3f-2f*lift)
        val swell=28f*scale*dissolve
        plateView.translationY=-scrollY*scale
        plateView.scaleX=1f+2f*swell/plateView.measuredWidth.coerceAtLeast(1)
        plateView.scaleY=1f+2f*swell/plateView.measuredHeight.coerceAtLeast(1)
        plateView.alpha=1f-dissolve
        val right=(width-96*scale).toInt();val cy=(oy+58*scale).toInt()
        controlButton.layout(right-controlButton.measuredWidth,cy,right,cy+controlButton.measuredHeight)
        tiles.forEach{tile->
            val isMoving=moving==tileKey(tile)
            tile.contentDescription=tile.title+(if(isMoving)"，移动中" else "")
            tile.isSelected=isMoving
        }
    }
    override fun dispatchDraw(canvas:Canvas){
        super.dispatchDraw(canvas)
        val entry=moving?:return
        val tile=tiles.firstOrNull{tileKey(it)==entry}?:return
        val x=appRail.left+tile.left.toFloat();val y=appRail.top+tile.top+tile.height*.5f
        val index=tiles.indexOf(tile)
        val arrowPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink;style=Paint.Style.STROKE;strokeWidth=2.5f*scale;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        fun arrow(center:Float,left:Boolean,enabled:Boolean){arrowPaint.alpha=if(enabled)255 else 65;val sign=if(left)-1 else 1;val p=Path();p.moveTo(center-sign*3*scale,y-6*scale);p.lineTo(center+sign*3*scale,y);p.lineTo(center-sign*3*scale,y+6*scale);canvas.drawPath(p,arrowPaint)}
        arrow(x+13*scale,true,index%cols>0);arrow(x+tile.width-13*scale,false,index%cols<cols-1&&index<tiles.lastIndex)
        val cx=x+tile.width/2f
        fun vArrow(centerY:Float,up:Boolean,enabled:Boolean){arrowPaint.alpha=if(enabled)255 else 65;val sign=if(up)-1 else 1;val p=Path();p.moveTo(cx-6*scale,centerY-sign*3*scale);p.lineTo(cx,centerY+sign*3*scale);p.lineTo(cx+6*scale,centerY-sign*3*scale);canvas.drawPath(p,arrowPaint)}
        vArrow(appRail.top+tile.top+13*scale,true,index>=cols);vArrow(appRail.top+tile.top+tile.height-13*scale,false,index/cols+1<rows)
    }
    fun update(base:String,pin:String,invite:String,count:Int,message:String){
        val minute=System.currentTimeMillis()/60000
        val connectionChanged=address!=base||code!=pin||payload!=invite||connected!=count
        val changed=connectionChanged||lastClockMinute!=minute
        lastClockMinute=minute
        address=base;code=pin;connected=count;controlButton.contentDescription="控制中心"+(if(count>0)"，手机已连接" else "");notice=message
        if(payload!=invite){payload=invite;qr=null;if(invite.isNotEmpty())try{val matrix=QRCodeWriter().encode(invite,BarcodeFormat.QR_CODE,480,480,mapOf(EncodeHintType.MARGIN to 2));val pixels=IntArray(480*480){i->if(matrix[i%480,i/480])0xff191727.toInt()else Color.WHITE};qr=Bitmap.createBitmap(pixels,480,480,Bitmap.Config.ARGB_8888)}catch(_:WriterException){notice="二维码生成失败，请使用地址和配对码"}}
        if(connectionChanged){connectionLabel.get()?.text=connectionLine();connectionImage.get()?.setImageBitmap(qr);connectionDot.get()?.background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(connected>0)0xff4caf50.toInt() else Color.WHITE)}}
        if(changed)invalidate()
    }
    fun focusFirst(){
        val entry=rememberedFocus
        val target=when{
            entry.isEmpty()->null
            entry=="控制中心"->controlButton
            else->tiles.firstOrNull{tileKey(it)==entry}
        }
        (target?:tiles.firstOrNull())?.requestFocus()
    }
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,color:Int=ink,strong:Boolean=false){paint.shader=null;paint.color=color;paint.textSize=size;paint.typeface=if(strong)bold else regular;c.drawText(value,x,y,paint)}
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        paint.shader=null;paint.isFilterBitmap=true
        if(drawsBackdrop)HomeAppearance.drawBackdrop(context,c,width,height,darkHome,paint)
        c.save();c.translate(0f,oy);c.scale(scale,scale)
        val now=java.util.Date();val zone=java.util.TimeZone.getDefault();timeFormat.timeZone=zone;dateFormat.timeZone=zone
        val time=timeFormat.format(now)
        val fade=shelfAlpha()
        if(fade>0f){
            fun faded(color:Int)=Color.argb((Color.alpha(color)*fade).toInt(),Color.red(color),Color.green(color),Color.blue(color))
            paint.style=Paint.Style.FILL
            val foreground=if(darkHome)Color.WHITE else 0xff162638.toInt()
            paint.setShadowLayer(9f,0f,3f,faded(if(darkHome)0x65000000 else 0x50ffffff))
            text(c,dateFormat.format(now),96f,364f,43f,color=faded(foreground),strong=true)
            paint.shader=null
            paint.typeface=clockFont
            paint.textSize=168f
            paint.color=faded(foreground)
            c.drawText(time,96f,297f,paint)
            paint.clearShadowLayer()
        }
        c.restore()
    }
    private fun connectionSummary()=listOf(if(connected>0)"手机已连接" else "扫码连接",address.ifEmpty{"请先联网"},if(code.isNotEmpty())"配对码  $code" else "").filter{it.isNotEmpty()}.joinToString("\n")
    private fun connectionLine()=listOf(address.ifEmpty{"请先联网"},if(code.isNotEmpty())"配对码  $code" else "").filter{it.isNotEmpty()}.joinToString("    ")
    fun qrBitmap()=qr
    fun connectionSummaryText()=connectionSummary()
    fun connectionLineText()=connectionLine()
    fun connectionCount()=connected
    fun showConnection(refresh:Runnable){
        val body=android.widget.LinearLayout(context).apply{orientation=android.widget.LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL}
        val titleRow=android.widget.LinearLayout(context).apply{orientation=android.widget.LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val dot=View(context).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(connected>0)0xff4caf50.toInt() else Color.WHITE)}}
        titleRow.addView(dot,android.widget.LinearLayout.LayoutParams((12*scale).toInt(),(12*scale).toInt()))
        titleRow.addView(android.widget.TextView(context).apply{text="手机连接";NativeUi.textSize(this,30f);setTypeface(null,Typeface.BOLD);setTextColor(GlassUi.ink(context))},android.widget.LinearLayout.LayoutParams(-2,-2).apply{leftMargin=(10*scale).toInt()})
        body.addView(titleRow,android.widget.LinearLayout.LayoutParams(-2,-2).apply{bottomMargin=(14*scale).toInt()});connectionDot=java.lang.ref.WeakReference(dot)
        val size=minOf((320*scale).toInt(),(resources.displayMetrics.heightPixels*.36f).toInt()).coerceAtLeast(1)
        val image=android.widget.ImageView(context).apply{setImageBitmap(qr);scaleType=android.widget.ImageView.ScaleType.FIT_CENTER;background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=16*scale}}
        body.addView(image,android.widget.LinearLayout.LayoutParams(size,size))
        val label=android.widget.TextView(context).apply{text=connectionLine();gravity=Gravity.CENTER;NativeUi.textSize(this,28f);setTextColor(GlassUi.ink(context));setPadding(0,(18*scale).toInt(),0,0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        body.addView(label,android.widget.LinearLayout.LayoutParams(-1,-2));connectionLabel=java.lang.ref.WeakReference(label);connectionImage=java.lang.ref.WeakReference(image)
        NativeUi.show(android.app.AlertDialog.Builder(context).setView(body).setPositiveButton("刷新"){_,_->refresh.run();showConnection(refresh)}.setNegativeButton("关闭",null),widthDp=280,anchor=controlButton)
    }
}
