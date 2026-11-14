package com.tvbridge.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.*
import android.util.TypedValue
import android.view.*
import android.widget.Button
import com.google.zxing.*
import com.google.zxing.qrcode.QRCodeWriter

class TvHomeView(context:Context,live:Runnable,files:Runnable,settings:Runnable,refresh:Runnable,private val apps:Runnable,private val addApps:Runnable,browse:Runnable=Runnable{}):ViewGroup(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular=Typeface.create("sans-serif",Typeface.NORMAL)
    private val bold=Typeface.create("sans-serif",Typeface.BOLD)
    private val clockFont=Typeface.create("sans-serif",Typeface.BOLD)
    private val timeFormat=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault())
    private val dateFormat=java.text.SimpleDateFormat("yyyy年M月d日  EEEE",java.util.Locale.SIMPLIFIED_CHINESE)
    private val buttons=mutableListOf<Button>()
    private val appButtons=mutableListOf<ShortcutButton>()
    private var appMenu:android.widget.ListPopupWindow?=null
    private var moving:LauncherApps.Entry?=null
    private var menuSelection=0
    private var menuEntry:LauncherApps.Entry?=null
    private val browseButton=Button(context)
    private val allApps=Button(context)
    private val controlButton=object:Button(context){
        private val icon=AppIcons.Vector("settings"){currentTextColor}
        override fun onDraw(canvas:Canvas){
            val size=(40*NativeUi.scale(context)).toInt()
            val left=(width-size)/2;val top=(height-size)/2
            icon.setBounds(left,top,left+size,top+size);icon.draw(canvas)
        }
    }
    private val launcherApps=LauncherApps(context)
    private val appRow=android.widget.GridLayout(context).apply{columnCount=5}
    private val appRail=android.widget.FrameLayout(context).apply{addView(appRow)}
    private var scale=1f;private var ox=0f;private var oy=0f
    private var styledScale=-1f
    private var qr:Bitmap?=null;private var payload="";private var address="";private var code="";private var connected=0;private var notice=""
    var drawsBackdrop=true
    private var darkHome=HomeAppearance.dark(context)
    private var appearanceKey=HomeAppearance.key(context)
    private val ink get()=GlassUi.ink(context,darkHome)
    private var appSignature:String?=null
    private var rememberedFocus=""
    private var activatedFocus=""
    private var lastClockMinute=-1L
    private var labelInk:Rect?=null
    private fun labelInkBounds():Rect{labelInk?.let{return it};val previousSize=paint.textSize;val previousTypeface=paint.typeface;paint.textSize=32f;paint.typeface=bold;val bounds=Rect();paint.getTextBounds("常用应用",0,4,bounds);paint.textSize=previousSize;paint.typeface=previousTypeface;labelInk=bounds;return bounds}
    private var connectionLabel=java.lang.ref.WeakReference<android.widget.TextView>(null)
    private var connectionImage=java.lang.ref.WeakReference<android.widget.ImageView>(null)
    private var connectionDot=java.lang.ref.WeakReference<android.view.View>(null)
    private fun homeTextColors()=GlassUi.colors(context)
    fun refreshAppearance(){
        val dark=HomeAppearance.dark(context)
        if(appearanceKey!=HomeAppearance.key(context)){appearanceKey=HomeAppearance.key(context);darkHome=dark;styledScale=-1f;appSignature=null;refreshApps();requestLayout();invalidate()}
    }

    init{setWillNotDraw(false);isFocusable=false
        listOf("电视直播" to live,"文件管理" to files,"控制中心" to settings,"手机连接" to refresh).forEach{(label,action)->
            val button=Button(context).apply{if(android.os.Build.VERSION.SDK_INT>=21)backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false;text=label;isAllCaps=false;isFocusableInTouchMode=true;setTextColor(homeTextColors());gravity=Gravity.CENTER;setPadding(0,0,0,0);minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;id=generateViewId();contentDescription=label;setOnClickListener{activatedFocus=label;rememberedFocus=label;if(label=="手机连接")showConnection(refresh)else action.run()};setOnFocusChangeListener{v,focus->if(focus&&label.isNotEmpty()){rememberedFocus=label;controlButton.nextFocusDownId=v.id};(v as Button).setTextColor(homeTextColors());v.animate().scaleX(if(focus)1.035f else 1f).scaleY(if(focus)1.035f else 1f).setDuration(120).start()}}
            NativeUi.touchClicks(button);buttons.add(button);addView(button)
        }
        browseButton.apply{text="浏览器";contentDescription="浏览器";id=generateViewId();isAllCaps=false;isFocusableInTouchMode=true;if(android.os.Build.VERSION.SDK_INT>=21)backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false;setTextColor(homeTextColors());setPadding(0,0,0,0);minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setOnClickListener{activatedFocus="浏览器";rememberedFocus="浏览器";browse.run()}}
        browseButton.setOnFocusChangeListener{v,focus->if(focus){rememberedFocus="浏览器";controlButton.nextFocusDownId=v.id}};AppIcons.bind(browseButton,"globe",34f);NativeUi.touchClicks(browseButton);addView(browseButton)
        allApps.apply{text="全部应用";contentDescription="全部应用";id=generateViewId();isAllCaps=false;isFocusableInTouchMode=true;if(android.os.Build.VERSION.SDK_INT>=21)backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false;setTextColor(homeTextColors());setPadding(0,0,0,0);minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setOnClickListener{activatedFocus="全部应用";rememberedFocus="全部应用";apps.run()}}
        allApps.setOnFocusChangeListener{v,focus->if(focus){rememberedFocus="全部应用";controlButton.nextFocusDownId=v.id}};AppIcons.bind(allApps,"grid",34f);NativeUi.touchClicks(allApps);addView(allApps)
        buttons[2].visibility=View.GONE;buttons[2].isFocusable=false
        buttons[3].visibility=View.GONE;buttons[3].isFocusable=false
        controlButton.apply{text="";contentDescription="控制中心";id=generateViewId();isAllCaps=false;isFocusableInTouchMode=true;if(android.os.Build.VERSION.SDK_INT>=21)backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false;setTextColor(homeTextColors());setPadding(0,0,0,0);minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setOnClickListener{activatedFocus="控制中心";rememberedFocus="控制中心";settings.run()}}
        controlButton.setOnFocusChangeListener{_,focus->if(focus)rememberedFocus="控制中心";controlButton.animate().scaleX(if(focus)1.08f else 1f).scaleY(if(focus)1.08f else 1f).setDuration(120).start()}
        NativeUi.touchClicks(controlButton);addView(controlButton)
        addView(appRail)
        refreshApps()
        buttons.forEachIndexed{i,b->b.nextFocusLeftId=buttons[(i-1).coerceAtLeast(0)].id;b.nextFocusRightId=if(i==2)allApps.id else buttons[(i+1).coerceAtMost(3)].id}
    }
    init { buttons[1].nextFocusRightId=browseButton.id;browseButton.nextFocusLeftId=buttons[1].id;browseButton.nextFocusRightId=allApps.id;allApps.nextFocusLeftId=browseButton.id;controlButton.nextFocusDownId=allApps.id;controlButton.nextFocusLeftId=controlButton.id;controlButton.nextFocusRightId=controlButton.id;controlButton.nextFocusUpId=controlButton.id }
    fun refreshApps(){
        val entries=launcherApps.favorites().take(LauncherApps.MAX_FAVORITES)
        val signature=entries.joinToString {it.component.flattenToString()+":"+it.label+":"+it.updated}+":"+darkHome
        if(appSignature==signature){requestLayout();return}
        val focused=(findFocus() as? Button)?.text?.toString()
        appSignature=signature
        appRow.removeAllViews();appButtons.clear()
        fun add(label:String,entry:LauncherApps.Entry?){
            val b=ShortcutButton(context).apply{tag=entry;text=label;contentDescription=label;id=generateViewId();isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;isFocusableInTouchMode=true;if(android.os.Build.VERSION.SDK_INT>=21)backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false;gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding((8*scale).toInt(),0,(8*scale).toInt(),0);setTextColor(homeTextColors());setOnClickListener{activatedFocus=label;rememberedFocus=label;if(moving!=null)finishMove()else if(entry==null)addApps.run()else if(!launcherApps.launch(entry))NativeUi.notice(context,"应用已移除或无法打开")}}
            b.setOnFocusChangeListener{_,focus->if(focus)rememberedFocus=label}
            val icon=if(entry!=null)try{launcherApps.icon(entry)}catch(_:Exception){AppIcons.Vector("grid"){b.currentTextColor}}else AppIcons.Vector("plus"){b.currentTextColor}
            val n=(46*scale).toInt();icon.setBounds(0,0,n,n);b.icon=icon
            if(entry!=null)b.setOnLongClickListener{showAppMenu(b,entry);true}
            NativeUi.touchClicks(b);appButtons.add(b);appRow.addView(b)
        }
        entries.forEach{add(it.label,it)};if(entries.size<LauncherApps.MAX_FAVORITES)add("添加应用",null)
        val tools=listOf(buttons[0],buttons[1],browseButton,allApps)
        val toolForColumn= {col:Int->when(col){0->buttons[0];1->buttons[1];2->browseButton;else->allApps}}
        appButtons.forEachIndexed{i,b->
            b.nextFocusLeftId=appButtons[if(i%5==0)i else i-1].id
            b.nextFocusRightId=appButtons.getOrNull(if(i%5==4)i else i+1)?.id?:b.id
            b.nextFocusUpId=if(i>=5)appButtons[i-5].id else toolForColumn(i%5).id
            b.nextFocusDownId=appButtons.getOrNull(i+5)?.id?:b.id
        }
        tools.forEachIndexed{i,b->
            b.nextFocusLeftId=tools[(i-1).coerceAtLeast(0)].id
            b.nextFocusRightId=tools[(i+1).coerceAtMost(3)].id
            b.nextFocusUpId=controlButton.id
            b.nextFocusDownId=appButtons.getOrNull(i)?.id?:appButtons.last().id
        }
        styledScale=-1f;requestLayout();if(focused!=null)appButtons.firstOrNull{it.text.toString()==focused}?.requestFocus()
    }
    private fun focusEntry(entry:LauncherApps.Entry){appButtons.firstOrNull{(it.tag as? LauncherApps.Entry)?.component==entry.component}?.requestFocus()}
    private fun finishMove(){val entry=moving?:return;moving=null;refreshApps();focusEntry(entry);invalidate()}
    private fun enterMove(entry:LauncherApps.Entry){appMenu?.dismiss();moving=entry;refreshApps();focusEntry(entry);invalidate()}
    private fun moveEntry(entry:LauncherApps.Entry,offset:Int){
        launcherApps.move(entry,offset)
        val order=launcherApps.saved();appButtons.sortBy{button->(button.tag as? LauncherApps.Entry)?.let{order.indexOf(it.component.flattenToString()).takeIf{n->n>=0}}?:Int.MAX_VALUE}
        styledScale=-1f
        requestLayout();invalidate();focusEntry(entry)
    }
    private fun showAppMenu(anchor:View,entry:LauncherApps.Entry){
        if(appMenu?.isShowing==true)return
        val popup=android.widget.ListPopupWindow(context);appMenu=popup;menuEntry=entry;moving=null;menuSelection=0
        popup.anchorView=anchor;popup.isModal=false;popup.width=if(anchor.width>0)anchor.width else (160*scale).toInt();popup.height=android.view.ViewGroup.LayoutParams.WRAP_CONTENT;popup.horizontalOffset=0;popup.verticalOffset=-anchor.height-(112*scale).toInt()
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.setAdapter(object:android.widget.ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,listOf("移动","删除")){override fun getView(position:Int,convert:View?,parent:ViewGroup):View=android.widget.TextView(context).apply{text=getItem(position);NativeUi.textSize(this,30f);gravity=Gravity.CENTER;val s=(14*scale).toInt();val half=(7*scale).toInt();setPadding((20*scale).toInt(),if(position==0)s else half,(20*scale).toInt(),if(position==0)half else s);isSingleLine=true;isSelected=position==menuSelection;background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);setTypeface(null,if(isSelected)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL);setTextColor(GlassUi.ink(context))}})
        popup.setOnItemClickListener{_,_,position,_->if(position==0)enterMove(entry)else{launcherApps.remove(entry);popup.dismiss()}}
        popup.setOnDismissListener{appMenu=null;menuEntry=null;moving=null;refreshApps();focusEntry(entry);invalidate()}
        popup.show();popup.listView?.let{it.background=GlassUi.surface(it,20f);it.setPadding(0,0,0,0);it.divider=null;it.dividerHeight=0;it.setHeaderDividersEnabled(false);it.setFooterDividersEnabled(false);it.selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)};post{if(popup.isShowing)popup.setSelection(0)}
        popup.listView?.setOnItemSelectedListener(object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){menuSelection=position;val list=popup.listView;if(list!=null)for(i in 0 until list.childCount){val child=list.getChildAt(i);val selected=list.firstVisiblePosition+i==position;child.isSelected=selected;child.background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);(child as? android.widget.TextView)?.let{it.setTypeface(null,if(selected)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL);it.setTextColor(GlassUi.ink(context))}};invalidate()}
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){ }
        })
        requestLayout();invalidate()
    }
    fun handleKey(code:Int):Boolean{
        moving?.let{entry->
            when(code){
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_MENU->finishMove()
                KeyEvent.KEYCODE_DPAD_LEFT->moveEntry(entry,-1)
                KeyEvent.KEYCODE_DPAD_RIGHT->moveEntry(entry,1)
                else->return false
            };return true
        }

        appMenu?.takeIf{it.isShowing}?.let{popup->
            val selected=menuSelection;val entry=menuEntry?:return false
            when(code){
                KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_MENU->popup.dismiss()
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->popup.performItemClick(selected)
                KeyEvent.KEYCODE_DPAD_UP->{menuSelection=0;popup.setSelection(0);popup.listView?.let{list->for(i in 0 until list.childCount){val child=list.getChildAt(i);val selected=list.firstVisiblePosition+i==0;child.isSelected=selected;(child as? android.widget.TextView)?.let{it.setTypeface(null,if(selected)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL);it.setTextColor(GlassUi.ink(context))}}};requestLayout();invalidate()}
                KeyEvent.KEYCODE_DPAD_DOWN->{menuSelection=1;popup.setSelection(1);popup.listView?.let{list->for(i in 0 until list.childCount){val child=list.getChildAt(i);val selected=list.firstVisiblePosition+i==1;child.isSelected=selected;(child as? android.widget.TextView)?.let{it.setTypeface(null,if(selected)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL);it.setTextColor(GlassUi.ink(context))}}};requestLayout();invalidate()}
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->if(selected==0)enterMove(entry)
                else->return false
            };return true
        }
        if(code==KeyEvent.KEYCODE_MENU){val tile=findFocus() as? ShortcutButton;val entry=tile?.tag as? LauncherApps.Entry;if(tile!=null&&entry!=null){showAppMenu(tile,entry);return true}}
        return false
    }
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);if(visibility!=View.VISIBLE){appMenu?.dismiss();moving=null}}
    override fun onDetachedFromWindow(){appMenu?.dismiss();super.onDetachedFromWindow()}
    private val twoRows get()=appButtons.size>5
    private fun homeButtonBackground(view:View)=GlassUi.surface(view,faint=true)
    private fun appTileBackground(view:View)=homeButtonBackground(view)
    override fun onMeasure(width:Int,height:Int){
        val w=MeasureSpec.getSize(width);val h=MeasureSpec.getSize(height);setMeasuredDimension(w,h)
        scale=minOf(w/1920f,h/1080f);ox=(w-1920*scale)/2;oy=(h-1080*scale)/2
        if(styledScale!=scale){
            styledScale=scale;updateButtonStyles()
            appButtons.forEachIndexed{i,button->
                button.layoutParams=android.widget.GridLayout.LayoutParams().apply{
                    this.width=(320*scale).toInt();this.height=((if(twoRows)168 else 200)*scale).toInt()
                    columnSpec=android.widget.GridLayout.spec(i%5);rowSpec=android.widget.GridLayout.spec(i/5)
                    marginEnd=if(i%5==4)0 else (32*scale).toInt();bottomMargin=if(i<5)(24*scale).toInt()else 0
                }
                button.iconPixels=if(button.tag==null)56f else if(twoRows)76f else 100f
            }
        }
        appRail.measure(MeasureSpec.makeMeasureSpec((1728*scale).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(((if(twoRows)360 else 200)*scale).toInt(),MeasureSpec.EXACTLY))
        listOf(buttons[0],buttons[1],browseButton,allApps).forEach{b->
            b.measure(MeasureSpec.makeMeasureSpec((320*scale).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((100*scale).toInt(),MeasureSpec.EXACTLY))
        }
        controlButton.measure(MeasureSpec.makeMeasureSpec((76*scale).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((76*scale).toInt(),MeasureSpec.EXACTLY))
    }
    private fun updateButtonStyles(){
        listOf(buttons[0],buttons[1],browseButton,allApps).forEach{b->
            b.setTextColor(homeTextColors());b.background=homeButtonBackground(b);NativeUi.textSize(b,30f)
            b.gravity=Gravity.CENTER;AppIcons.bind(b,when(b){browseButton->"globe";allApps->"grid";else->if(b===buttons[0])"tv"else "folder"},34f)
            val inset=((320*scale-34*scale-12*scale-b.paint.measureText(b.text.toString()))/2).toInt().coerceAtLeast((16*scale).toInt())
            b.setPadding(inset,0,inset,0)
        }
        appButtons.forEach{button->button.setTextColor(homeTextColors());NativeUi.textSize(button,28f);button.background=appTileBackground(button)}
        controlButton.setTextColor(Color.WHITE);controlButton.background=GlassUi.surface(controlButton,GlassUi.CIRCLE_RADIUS,video=true,dark=true,faint=true)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        val y=(oy+(if(twoRows)452 else 570)*scale).toInt()
        listOf(buttons[0],buttons[1],browseButton,allApps).forEachIndexed{i,button->
            val x=(ox+(96+i*352)*scale).toInt();button.layout(x,y,x+button.measuredWidth,y+button.measuredHeight)
        }
        val connection=controlButton;val right=(ox+1824*scale).toInt();val cy=(oy+58*scale).toInt();connection.layout(right-connection.measuredWidth,cy,right,cy+connection.measuredHeight)
        val ax=(ox+96*scale).toInt();val buttonsBottom=(if(twoRows)452 else 570)+100;val ay=(oy+(buttonsBottom+60+labelInkBounds().height())*scale).toInt();appRail.layout(ax,ay,ax+appRail.measuredWidth,ay+appRail.measuredHeight)
        appButtons.forEach{button->
            button.contentDescription=button.text.toString()+(if(moving?.component==(button.tag as? LauncherApps.Entry)?.component&&moving!=null)"，移动中"else "")
            button.isSelected=moving?.component==(button.tag as? LauncherApps.Entry)?.component&&moving!=null
        }
    }
    override fun dispatchDraw(canvas:Canvas){
        super.dispatchDraw(canvas)

        val entry=moving?:return
        val tile=appButtons.firstOrNull{(it.tag as? LauncherApps.Entry)?.component==entry.component}?:return
        val x=appRail.left+tile.left.toFloat();val y=appRail.top+tile.top+tile.height*.48f
        val order=launcherApps.saved();val index=order.indexOf(entry.component.flattenToString())
        val arrowPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink;style=Paint.Style.STROKE;strokeWidth=2.5f*scale;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        fun arrow(center:Float,left:Boolean,enabled:Boolean){arrowPaint.alpha=if(enabled)255 else 65;val sign=if(left)-1 else 1;val p=Path();p.moveTo(center-sign*3*scale,y-6*scale);p.lineTo(center+sign*3*scale,y);p.lineTo(center-sign*3*scale,y+6*scale);canvas.drawPath(p,arrowPaint)}
        arrow(x+13*scale,true,index>0);arrow(x+tile.width-13*scale,false,index<order.lastIndex)
    }
    fun update(base:String,pin:String,invite:String,count:Int,message:String){
        val minute=System.currentTimeMillis()/60000
        val connectionChanged=address!=base||code!=pin||payload!=invite||connected!=count
        val changed=connectionChanged||lastClockMinute!=minute
        lastClockMinute=minute
        address=base;code=pin;connected=count;controlButton.contentDescription="控制中心"+(if(count>0)"，手机已连接"else "");notice=message
        if(payload!=invite){payload=invite;qr=null;if(invite.isNotEmpty())try{val matrix=QRCodeWriter().encode(invite,BarcodeFormat.QR_CODE,480,480,mapOf(EncodeHintType.MARGIN to 2));val pixels=IntArray(480*480){i->if(matrix[i%480,i/480])0xff191727.toInt()else Color.WHITE};qr=Bitmap.createBitmap(pixels,480,480,Bitmap.Config.ARGB_8888)}catch(_:WriterException){notice="二维码生成失败，请使用地址和配对码"}}
        if(connectionChanged){connectionLabel.get()?.text=connectionLine();connectionImage.get()?.setImageBitmap(qr);connectionDot.get()?.background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(connected>0)0xff4caf50.toInt() else Color.WHITE)}}
        if(changed)invalidate()
    }
    fun focusFirst(){val entry=activatedFocus.ifEmpty{rememberedFocus};val target=if(entry.isEmpty())null else (appButtons+buttons+listOf(browseButton,allApps,controlButton)).firstOrNull{it.visibility==View.VISIBLE&&it.isFocusable&&(it.text?.toString()==entry||it.contentDescription?.toString()==entry||it.contentDescription?.toString()?.startsWith(entry+"，")==true)};(target?:buttons.first()).requestFocus()}
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,color:Int=ink,strong:Boolean=false){paint.shader=null;paint.color=color;paint.textSize=size;paint.typeface=if(strong)bold else regular;c.drawText(value,x,y,paint)}
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        paint.shader=null;paint.isFilterBitmap=true
        if(drawsBackdrop)HomeAppearance.drawBackdrop(context,c,width,height,darkHome,paint)
        c.save();c.translate(ox,oy);c.scale(scale,scale)
        val now=java.util.Date();val zone=java.util.TimeZone.getDefault();timeFormat.timeZone=zone;dateFormat.timeZone=zone
        val time=timeFormat.format(now);paint.textSize=if(twoRows)148f else 168f;paint.typeface=clockFont
        val timeY=if(twoRows)276f else 326f
        paint.shader=null;paint.style=Paint.Style.FILL
        paint.color=if(darkHome)0x55000000 else 0x55ffffff;c.drawText(time,96f+3f,timeY+4f,paint)
        paint.color=if(darkHome)0xd0f0ede0.toInt() else 0xd02a3a4a.toInt();c.drawText(time,96f,timeY,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=if(darkHome)0x66ffffff else 0x66404040.toInt();c.drawText(time,96f,timeY,paint)
        paint.style=Paint.Style.FILL;paint.strokeWidth=0f
        text(c,dateFormat.format(now),100f,if(twoRows)340f else 390f,36f)
        val ink=labelInkBounds();text(c,"常用应用",96f,(if(twoRows)552f else 670f)+30f-ink.top,32f,strong=true)
        c.restore()
    }
    private fun connectionSummary()=listOf(if(connected>0)"手机已连接"else "扫码连接",address.ifEmpty{"请先联网"},if(code.isNotEmpty())"配对码  $code"else "").filter{it.isNotEmpty()}.joinToString("\n")
    private fun connectionLine()=listOf(address.ifEmpty{"请先联网"},if(code.isNotEmpty())"配对码  $code"else "").filter{it.isNotEmpty()}.joinToString("    ")
    fun qrBitmap()=qr
    fun connectionSummaryText()=connectionSummary()
    fun connectionLineText()=connectionLine()
    fun connectionCount()=connected
    fun showConnection(refresh:Runnable){
        val body=android.widget.LinearLayout(context).apply{orientation=android.widget.LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL}
        val titleRow=android.widget.LinearLayout(context).apply{orientation=android.widget.LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val dot=android.view.View(context).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(connected>0)0xff4caf50.toInt() else Color.WHITE)}}
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
