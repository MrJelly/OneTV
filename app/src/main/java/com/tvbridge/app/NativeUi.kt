package com.tvbridge.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.*
import android.view.*
import java.lang.ref.WeakReference

object NativeUi {
    internal const val CONTROL_DETAIL="one.control.detail";internal const val QR_BOX="one.qr.box"
    private var controlOrigin=WeakReference<View>(null)
    internal fun selectControl(view:View){
        controlOrigin.get()?.isSelected=false
        controlOrigin=WeakReference(view);view.isSelected=true
    }
    private var noticeView=WeakReference<android.widget.TextView>(null)
    private val notices=android.os.Handler(android.os.Looper.getMainLooper())
    private var dismissNotice:Runnable?=null
    fun notice(context:Context,message:String){
        val activity=GlassUi.activity(context)?:return
        activity.runOnUiThread{
            if(activity.isFinishing)return@runOnUiThread
            dismissNotice?.let{notices.removeCallbacks(it)}
            noticeView.get()?.let{old->old.animate().cancel();(old.parent as? ViewGroup)?.removeView(old)}
            val root=activity.findViewById<android.widget.FrameLayout>(android.R.id.content)?:return@runOnUiThread
            val unit=scale(activity)
            val label=android.widget.TextView(activity).apply{
                text=message;gravity=Gravity.CENTER;maxLines=3;setTextColor(GlassUi.ink(activity));NativeUi.textSize(this,30f)
                setPadding((24*unit).toInt(),(16*unit).toInt(),(24*unit).toInt(),(16*unit).toInt())
                background=GlassUi.surface(this,22f,dense=true);isFocusable=false;isClickable=false
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
                alpha=0f;translationY=-24*unit
            }
            root.addView(label,android.widget.FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=(28*unit).toInt();leftMargin=(32*unit).toInt();rightMargin=(32*unit).toInt()})
            noticeView=WeakReference(label);label.animate().alpha(1f).translationY(0f).setDuration(160).start()
            dismissNotice=Runnable{label.animate().alpha(0f).translationY(-20*unit).setDuration(160).withEndAction{(label.parent as? ViewGroup)?.removeView(label)}.start()}
            notices.postDelayed(dismissNotice!!,1840)
        }
    }
    fun trailing(view:android.widget.TextView,value:String,ink:Boolean=false):Drawable {
        val unit=scale(view.context);val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{textSize=BODY_TEXT_SIZE*unit}
        val width=(paint.measureText(value)+56*unit).toInt();val height=(32*unit).toInt()
        return object:Drawable(){
            override fun draw(canvas:android.graphics.Canvas){
                paint.color=if(ink)view.currentTextColor else GlassUi.muted(view.context);canvas.drawText(value,bounds.left.toFloat(),bounds.centerY()-(paint.ascent()+paint.descent())/2,paint)
                val arrow=AppIcons.Vector("chevron-right"){view.currentTextColor};arrow.setBounds(bounds.right-(32*unit).toInt(),bounds.centerY()-(16*unit).toInt(),bounds.right,bounds.centerY()+(16*unit).toInt());arrow.draw(canvas)
            }
            override fun setAlpha(alpha:Int){};override fun setColorFilter(filter:android.graphics.ColorFilter?){}
            @Deprecated("Legacy Drawable") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
        }.apply{setBounds(0,0,width,height)}
    }
    fun touchClicks(view:View){
        view.isSoundEffectsEnabled=false
        view.isFocusableInTouchMode=false
        var longClicked=false
        val longPress=Runnable{if(view.isPressed&&view.isLongClickable)longClicked=view.performLongClick()}
        view.setOnTouchListener{target,event->
            if(!target.isEnabled)return@setOnTouchListener false
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{longClicked=false;target.isPressed=true;target.postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong());true}
                MotionEvent.ACTION_MOVE->{if(event.x<0||event.y<0||event.x>=target.width||event.y>=target.height){target.isPressed=false;target.removeCallbacks(longPress)};true}
                MotionEvent.ACTION_UP->{val click=target.isPressed&&!longClicked;target.isPressed=false;target.removeCallbacks(longPress);if(click){UiSounds.click(target.context);target.performClick()};true}
                MotionEvent.ACTION_CANCEL->{target.isPressed=false;target.removeCallbacks(longPress);true}
                else->false
            }
        }
    }
    fun scale(context:Context):Float {val metrics=context.resources.displayMetrics;return minOf(metrics.widthPixels/1920f,metrics.heightPixels/1080f)}
    const val BODY_TEXT_SIZE = 30f
    fun textSize(view:android.widget.TextView,pixels:Float){view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,pixels.coerceAtLeast(BODY_TEXT_SIZE)*scale(view.context))}
    private var dialog=WeakReference<AlertDialog>(null)
    private val dialogStack=mutableListOf<WeakReference<AlertDialog>>()
    private var hiddenQrBox:WeakReference<android.view.View>?=null
    private fun hideQrBox(qrBox:android.view.View){qrBox.visibility=android.view.View.GONE;hiddenQrBox=WeakReference(qrBox)}
    private fun restoreQrBox(){hiddenQrBox?.get()?.let{it.visibility=android.view.View.VISIBLE};hiddenQrBox=null}
    fun inputDialogText(text:String):Boolean?=dialog.get()?.takeIf{it.isShowing}?.let{RemoteText.insert(it.currentFocus,text)}
    fun settingsRowHeight(context:Context)=(104*scale(context)).toInt()
    fun settingsRowGap(context:Context)=(12*scale(context)).toInt()

    fun optionButton(context:Context,label:String,value:String="",selected:Boolean=false,center:Boolean=false):android.widget.Button{
        val scale=scale(context)
        return android.widget.Button(context).apply{
            text=label
            isAllCaps=false;isSingleLine=true
            ellipsize=android.text.TextUtils.TruncateAt.END
            gravity=(if(center)Gravity.CENTER_HORIZONTAL else Gravity.START) or Gravity.CENTER_VERTICAL
            minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
            val padding=(20*scale).toInt()
            setPadding(padding,0,padding,0)
            textSize(this,30f)
            setTextColor(GlassUi.colors(context))
            if(android.os.Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null}
            if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
            background=GlassUi.surface(this,16f,faint=true)
            if(value.isNotEmpty())setCompoundDrawablesRelative(null,null,trailing(this,value,ink=true),null)
            isSelected=selected
            NativeUi.touchClicks(this)
        }
    }
    fun show(builder:AlertDialog.Builder,tallList:Boolean=false,compact:Boolean=false,widthDp:Int?=null,anchor:View?=null,maxListHeightDp:Int?=null,initialFocus:View?=null,listIcons:List<String> = emptyList(),fillDetailWith:View?=null):AlertDialog {
        val video=GlassUi.video(builder.context)
        val frost=if(video)null else GlassUi.snapshot(builder.context)
        val value=builder.create()
        value.setOnDismissListener{
            dialogStack.removeAll{it.get()==null||it.get()===value||it.get()?.isShowing!=true}
            if(dialogStack.isEmpty())restoreQrBox()
            if(dialog.get()===value)dialog=WeakReference(dialogStack.lastOrNull()?.get())
            if(dialogStack.isEmpty())controlOrigin.get()?.isSelected=false
        }
        dialogStack.removeAll{it.get()?.isShowing!=true}
        if(android.os.Build.VERSION.SDK_INT>=21)value.create()else {
            value.window?.let{it.attributes=it.attributes.apply{alpha=0f;windowAnimations=0}}
            value.show()
        }
        GlassUi.activity(value.context)?.let{activity->UiSounds.observe(activity,value.window!!.decorView);value.setOnKeyListener{_,_,event->UiSounds.key(activity,event);false}}
        val context=value.context;val density=2*scale(context)
        fun dp(n:Int)=(n*density).toInt()
        fun detach(view:View){(view.parent as? ViewGroup)?.removeView(view)}
        val window=value.window?:return value
        GlassUi.activity(context)?.let{activity->
            window.decorView.systemUiVisibility=activity.window.decorView.systemUiVisibility
            if(android.os.Build.VERSION.SDK_INT>=28){
                window.attributes=window.attributes.apply{
                    layoutInDisplayCutoutMode=activity.window.attributes.layoutInDisplayCutoutMode
                }
            }
        }
        val old=window.decorView
        val titleId=context.resources.getIdentifier("alertTitle","id","android")
        val title=old.findViewById<android.widget.TextView>(titleId)
        val message=old.findViewById<android.widget.TextView>(android.R.id.message)
        val custom=old.findViewById<ViewGroup>(android.R.id.custom)?.takeIf{it.childCount>0}
        val list=value.listView
        val detail=GlassUi.activity(context)?.window?.decorView?.findViewWithTag<View>(CONTROL_DETAIL)?.takeIf{it.isShown&&it.width>0&&it.height>0}
        var dialogWidth=minOf(dp(widthDp?:if(compact)300 else if(custom!=null)430 else if(list!=null)380 else 340),detail?.width?:(context.resources.displayMetrics.widthPixels*.76f).toInt())
        val buttons=listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_POSITIVE).mapNotNull{which->value.getButton(which)?.takeIf{it.visibility==View.VISIBLE}?.let{which to it}}
        val panel=android.widget.LinearLayout(context).apply{orientation=android.widget.LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));background=GlassUi.surface(this,28f,frost,video=video,dense=true)}
        if(compact)panel.setPadding(dp(12),dp(8),dp(12),dp(8))
        if(title!=null&&title.text.isNotBlank()){
            detach(title);textSize(title,34f);title.setTextColor(GlassUi.ink(context));title.setTypeface(null,android.graphics.Typeface.BOLD);title.setPadding(0,0,0,0)
            panel.addView(title,android.widget.LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        }
        if(message!=null&&message.text.isNotBlank()){
            detach(message);textSize(message,30f);message.setTextColor(GlassUi.ink(context));message.setLineSpacing(dp(5).toFloat(),1f);message.setPadding(0,0,0,0)
            val scroll=android.widget.ScrollView(context).apply{isFillViewport=false;addView(message)}
            panel.addView(scroll,android.widget.LinearLayout.LayoutParams(-1,if(message.text.length>160)dp(150)else -2).apply{bottomMargin=dp(12)})
        }
        if(custom!=null){detach(custom);custom.setPadding(0,0,0,0);panel.addView(custom,android.widget.LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=if(compact)0 else dp(12)})}
        if(list!=null){
            detach(list);list.isSoundEffectsEnabled=false;list.divider=null;list.dividerHeight=dp(6);list.isVerticalScrollBarEnabled=false;list.setPadding(0,0,0,0)
            list.selector=GlassUi.selectedBorder(list,18f)
            val original=list.adapter
            val checked=(0 until original.count).filter{list.isItemChecked(it)}
            list.adapter=object:android.widget.BaseAdapter(){
                override fun getCount()=original.count
                override fun getItem(position:Int)=original.getItem(position)
                override fun getItemId(position:Int)=original.getItemId(position)
                override fun getView(position:Int,convert:View?,parent:ViewGroup):View=original.getView(position,convert,parent).also{row->
                    row.layoutParams=android.widget.AbsListView.LayoutParams(-1,dp(44))
                    if(row is android.widget.TextView){row.setTextColor(GlassUi.colors(context));textSize(row,30f);row.gravity=android.view.Gravity.CENTER_VERTICAL;row.setPadding(dp(16),0,dp(16),0);row.minimumHeight=dp(38);row.background=null
                        listIcons.getOrNull(position)?.let{key->
                            val size=dp(18)
                            val icon=AppIcons.Vector(key){row.currentTextColor}.apply{setBounds(0,0,size,size)}
                            row.setCompoundDrawablesRelative(icon,null,null,null)
                            row.compoundDrawablePadding=dp(12)
                        }
                    }
                }
            }
            checked.forEach{list.setItemChecked(it,true)}
            panel.addView(list,android.widget.LinearLayout.LayoutParams(-1,if(maxListHeightDp!=null)dp(maxListHeightDp)else if(tallList)minOf(dp(320),(context.resources.displayMetrics.heightPixels*.60f).toInt())else dp((original.count*54).coerceIn(60,300))).apply{bottomMargin=dp(12)})
        }
        if(buttons.isNotEmpty()){
            val row=android.widget.LinearLayout(context).apply{gravity=android.view.Gravity.CENTER;orientation=android.widget.LinearLayout.HORIZONTAL}
            val maxButtonWidth=buttons.maxOf{(_,button)->(button.paint.measureText(button.text.toString())+dp(16)).toInt()}.coerceIn(dp(80),dp(200))
            var totalWidth=0
            buttons.forEach{(which,button)->
                detach(button);button.isSoundEffectsEnabled=false;textSize(button,30f);button.isAllCaps=false;button.maxLines=1;button.ellipsize=android.text.TextUtils.TruncateAt.END;button.minWidth=0;button.minimumWidth=0;button.minHeight=0;button.minimumHeight=0;if(android.os.Build.VERSION.SDK_INT>=21)button.backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)button.stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=21)button.elevation=0f;button.setPadding(dp(8),0,dp(8),0);button.background=GlassUi.surface(button,24f,frost,video=video,faint=true);button.setTextColor(GlassUi.colors(context));button.setTypeface(null,android.graphics.Typeface.NORMAL)
                totalWidth+=maxButtonWidth
                row.addView(button,android.widget.LinearLayout.LayoutParams(maxButtonWidth,dp(32)).apply{if(row.childCount>0)leftMargin=dp(10)})
            }
            totalWidth+=dp(10)*(buttons.size-1)
            val available=dialogWidth-panel.paddingLeft-panel.paddingRight
            if(totalWidth>available){
                if(detail!=null){
                    val capped=detail.width-panel.paddingLeft-panel.paddingRight
                    if(capped>0&&totalWidth>capped){
                        val perButton=((capped-dp(10)*(buttons.size-1))/buttons.size).coerceAtLeast(dp(48))
                        buttons.forEachIndexed{index,(_,button)->
                            button.layoutParams=android.widget.LinearLayout.LayoutParams(perButton,dp(32)).apply{if(index>0)leftMargin=dp(10)}
                        }
                    }
                }else{
                    dialogWidth=minOf(dialogWidth+(totalWidth-available),context.resources.displayMetrics.widthPixels-dp(32))
                }
            }
            panel.addView(row,android.widget.LinearLayout.LayoutParams(-1,dp(32)))
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.setContentView(panel)
        fun clearDefaultHighlight(view:View){if(android.os.Build.VERSION.SDK_INT>=26)view.defaultFocusHighlightEnabled=false;if(view is ViewGroup)for(i in 0 until view.childCount)clearDefaultHighlight(view.getChildAt(i))}
        clearDefaultHighlight(panel)
        if(detail!=null)dialogWidth=detail.width
        if(detail!=null&&fillDetailWith!=null){
            val widthSpec=View.MeasureSpec.makeMeasureSpec(dialogWidth,View.MeasureSpec.EXACTLY)
            panel.measure(widthSpec,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            fillDetailWith.layoutParams=fillDetailWith.layoutParams.apply{
                height=(detail.height-panel.measuredHeight).coerceAtLeast(0)
            }
        }
        window.setLayout(dialogWidth,ViewGroup.LayoutParams.WRAP_CONTENT)
        if(detail!=null){
            window.attributes=window.attributes.apply{windowAnimations=0}
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val location=IntArray(2);detail.getLocationOnScreen(location)
            panel.measure(View.MeasureSpec.makeMeasureSpec(dialogWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(detail.height,View.MeasureSpec.AT_MOST))
            val origin=controlOrigin.get()?.takeIf{it.isShown&&GlassUi.activity(it.context)===GlassUi.activity(context)}
            origin?.isSelected=true
            val originPosition=IntArray(2);origin?.getLocationOnScreen(originPosition)
            val top=(if(origin!=null)originPosition[1] else location[1]).coerceIn(location[1],(location[1]+detail.height-panel.measuredHeight).coerceAtLeast(location[1]))
            window.setGravity(Gravity.TOP or Gravity.LEFT)
            window.attributes=window.attributes.apply{x=location[0];y=top}
        }else if(anchor==null){window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.28f)}else{
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val location=IntArray(2);anchor.getLocationOnScreen(location)
            val width=window.attributes.width
            val frame=android.graphics.Rect().also{anchor.getWindowVisibleDisplayFrame(it)}
            panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(frame.height(),View.MeasureSpec.AT_MOST))
            val below=location[1]+anchor.height+dp(8)
            val above=location[1]-panel.measuredHeight-dp(8)
            val top=(if(below+panel.measuredHeight<=frame.bottom)below else above)
                .coerceIn(frame.top,(frame.bottom-panel.measuredHeight).coerceAtLeast(frame.top))
            window.setGravity(Gravity.TOP or Gravity.LEFT)
            window.attributes=window.attributes.apply{x=(location[0]+anchor.width-width).coerceIn(dp(16),(context.resources.displayMetrics.widthPixels-width-dp(16)).coerceAtLeast(dp(16)));y=top}
        }
        var qrToHide:View?=null
        if(detail!=null){
            val qrBox=(detail as? ViewGroup)?.let{group->(0 until group.childCount).map{group.getChildAt(it)}.firstOrNull{it.tag==QR_BOX}}
            if(qrBox!=null&&qrBox.visibility==android.view.View.VISIBLE)qrToHide=qrBox
        }
        if(list!=null)list.requestFocus()else if(custom!=null){
            fun firstFocusable(view:android.view.View):android.view.View?{
                if(view.isFocusable&&view.visibility==android.view.View.VISIBLE)return view
                if(view is android.view.ViewGroup)for(i in 0 until view.childCount){firstFocusable(view.getChildAt(i))?.let{return it}}
                return null
            }
            (initialFocus?:firstFocusable(custom))?.requestFocusFromTouch() ?: buttons.lastOrNull()?.second?.requestFocus()
        }else buttons.lastOrNull()?.second?.requestFocus()
        window.attributes=window.attributes.apply{alpha=0f}
        panel.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{
            override fun onPreDraw():Boolean {
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                panel.postOnAnimation{
                    if(value.isShowing){
                        window.attributes=window.attributes.apply{alpha=1f}
                        panel.postOnAnimation{if(value.isShowing)qrToHide?.takeIf{it.isAttachedToWindow}?.let{hideQrBox(it)}}
                    }
                }
                return true
            }
        })
        value.show();dialog=WeakReference(value);dialogStack.add(WeakReference(value))
        return value
    }
    fun handleExternalDismiss(value:AlertDialog){
        dialogStack.removeAll{it.get()==null||it.get()===value||it.get()?.isShowing!=true}
        if(dialogStack.isEmpty())restoreQrBox()
        if(dialog.get()===value)dialog=WeakReference(dialogStack.lastOrNull()?.get())
        if(dialogStack.isEmpty())controlOrigin.get()?.isSelected=false
    }
    fun registerQrBox(qrBox:android.view.View){
        if(dialogStack.mapNotNull{it.get()}.any{it.isShowing})hideQrBox(qrBox)
    }
    fun dismissDialogs(){dialogStack.mapNotNull{it.get()}.asReversed().forEach{it.dismiss()}
    }
    fun dialogCommand(cmd:String):Boolean? {
        val value=dialog.get()?.takeIf{it.isShowing}?:return null
        if(cmd=="back"){value.dismiss();return true}
        if(cmd=="ok"){val list=value.listView;if(list!=null&&list.hasFocus()&&list.selectedItemPosition>=0)return list.performItemClick(list.selectedView,list.selectedItemPosition,list.selectedItemId);val focused=value.currentFocus;if(focused!=null&&focused.performClick())return true;return value.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()==true}
        val code=when(cmd){"up"->19;"down"->20;"left"->21;"right"->22;"ok"->23;else->return null}
        value.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));value.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code));return true
    }
    fun move(root:View,cmd:String):Boolean {
        val current=root.findFocus()
        if(cmd=="ok")return current?.performClick()==true
        val direction=when(cmd){"up"->View.FOCUS_UP;"down"->View.FOCUS_DOWN;"left"->View.FOCUS_LEFT;"right"->View.FOCUS_RIGHT;else->return false}
        val next=current?.focusSearch(direction)?:root.getFocusables(View.FOCUS_FORWARD).firstOrNull{it.isShown}
        return next?.requestFocusFromTouch()==true
    }
}

class SettingsSwitch(context:Context):android.widget.Switch(context){
    init{isSoundEffectsEnabled=false}
    override fun performClick():Boolean{UiSounds.click(context);return super.performClick()}
    private val ink=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:android.graphics.Canvas){
        val unit=NativeUi.scale(context);ink.color=currentTextColor;ink.textSize=textSize;ink.typeface=typeface
        canvas.drawText(text.toString(),paddingLeft.toFloat(),height/2f-(ink.ascent()+ink.descent())/2,ink)
        val right=width-paddingRight.toFloat();val left=right-116*unit;val center=height/2f
        val track=android.graphics.RectF(left,center-30*unit,right,center+30*unit)
        ink.style=android.graphics.Paint.Style.FILL
        ink.color=if(isChecked)0x9934c759.toInt()else if(HomeAppearance.dark(context))0x55ffffff else 0x558a8a8a.toInt()
        canvas.drawRoundRect(track,30*unit,30*unit,ink)
        ink.style=android.graphics.Paint.Style.STROKE;ink.strokeWidth=unit
        ink.color=if(isChecked)0x99ffffff.toInt() else 0x66ffffff.toInt()
        canvas.drawRoundRect(track,30*unit,30*unit,ink)
        ink.style=android.graphics.Paint.Style.FILL
        ink.color=Color.WHITE;canvas.drawCircle(if(isChecked)right-30*unit else left+30*unit,center,24*unit,ink)
        ink.style=android.graphics.Paint.Style.STROKE;ink.strokeWidth=unit;ink.color=0x33000000
        canvas.drawCircle(if(isChecked)right-30*unit else left+30*unit,center,24*unit,ink)
        ink.style=android.graphics.Paint.Style.FILL
    }
}
