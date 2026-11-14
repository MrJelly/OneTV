package com.tvbridge.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.*
import android.os.*
import android.view.*
import android.widget.*
import android.app.AlertDialog
import android.media.AudioManager
import androidx.media3.common.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.PlayerView


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LivePanel(context:Context,private val repo:LiveRepository,private val exit:()->Unit,private val noSources:(()->Unit)?=null,private val pageFactory:((Context,()->Unit,(String,Boolean)->Unit)->PagePlayback)?=null):FrameLayout(context) {
    private val touchDevice=(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK)!=android.content.res.Configuration.UI_MODE_TYPE_TELEVISION&&!context.packageManager.hasSystemFeature("android.software.leanback")
    private val ui=Handler(Looper.getMainLooper())
    private val routes=PlaybackRoutes(context)
    private val loadPolicy=object:DefaultLoadErrorHandlingPolicy(){
        override fun getRetryDelayMsFor(info:LoadErrorHandlingPolicy.LoadErrorInfo):Long {
            val http=generateSequence<Throwable>(info.exception){it.cause}.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            return if(info.exception is ParserException||http?.responseCode in listOf(401,403,404,410))C.TIME_UNSET else super.getRetryDelayMsFor(info)
        }
    }
    private val attempts=PlaybackAttempts()
    private var nativePlayer:androidx.media3.exoplayer.ExoPlayer?=null
    private var player:androidx.media3.exoplayer.ExoPlayer
        get(){
            nativePlayer?.let{return it}
            check(!released)
            val created=NativePlayback.player(context)
            nativePlayer=created;bindPlayer();playerView.player=created
            return created
        }
        set(value){nativePlayer=value}
    private var softwarePlayer=0
    private var multicast:android.net.wifi.WifiManager.MulticastLock?=null
    private val playerView=PlayerView(context).apply{useController=false;setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);setShutterBackgroundColor(Color.BLACK);setBackgroundColor(Color.BLACK);isFocusable=false}
    private val videoSurface=FrameLayout(context).apply{background=GradientDrawable().apply{setColor(0xff292d32.toInt())};if(android.os.Build.VERSION.SDK_INT>=21)clipToOutline=true;addView(playerView,LayoutParams(-1,-1))}
    private var officialPlayer:PagePlayback?=null
    private var officialFailed=false
    private fun officialPlaying()=current?.streams?.getOrNull(line)?.let(LiveCatalog::isPage)==true
    private var lastSource=""
    private val title=TextView(context)
    private val loading=LoadingSpinner(context).apply{visibility=View.GONE}
    private val groupButton=Button(context)
    private val favoriteButton=Button(context)
    private val filterRow=LinearLayout(context)
    private val overlay=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val fullTitle=TextView(context)
    private val controls=LinearLayout(context).apply{gravity=Gravity.CENTER}
    private var favoriteAction:Button?=null
    private var lineAction:Button?=null
    private val drawer=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;isClickable=true}
    private val drawerList=GridView(context).apply{numColumns=2;stretchMode=GridView.STRETCH_COLUMN_WIDTH;horizontalSpacing=dp(4);verticalSpacing=dp(4)}
    private lateinit var sourceButton:Button
    private var all=emptyList<LiveChannel>()
    private var shown=emptyList<LiveChannel>()
    private var current:LiveChannel?=null
    private var retries=0
    private var readySince=0L
    private var lastErrorAt=0L
    private var line=0
    private var category="全部频道"
    private var favoritesOnly=false
    private var qualityAction:Button?=null
    private var groupPopup:ListPopupWindow?=null
    private var lastLeftMs=0L;private var lastLeftColumn=-1
    private var lastRightMs=0L;private var lastRightColumn=-1
    private var released=false
    private var paused=false
    private var resumePlayback=false
    private var lastFailure=""
    private var pendingRetry:Runnable?=null
    private var playbackEpoch=0
    private var openedAt=0L
    private var bufferingAt=0L
    private var mediaProgressAt=0L
    private val hide=Runnable{if(drawer.visibility!=View.VISIBLE&&(touchDevice||!controls.hasFocus())){overlay.visibility=View.GONE;fullTitle.visibility=View.GONE;isFocusableInTouchMode=true;requestFocus()}}
    private val timeout=object:Runnable{override fun run(){
        if(released||paused||current==null||officialPlaying()||player.playbackState==Player.STATE_READY)return
        val now=SystemClock.elapsedRealtime()
        if(PlaybackAttempts.waitForMedia(now-bufferingAt,now-mediaProgressAt))ui.postDelayed(this,5000)
        else{lastFailure="缓冲超时，请检查网络或切换线路";recover(lastFailure)}
    }}
    init {
        setBackgroundColor(Color.BLACK);clipToPadding=false
        addView(videoSurface,LayoutParams(-1,-1))
        NativeUi.textSize(title,28f);title.setTextColor(0xffdce1e5.toInt());title.gravity=Gravity.CENTER
        title.background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xff292d32.toInt(),0xff353a41.toInt()))
        addView(title,LayoutParams(-1,-1));addView(loading,LayoutParams(dp(22),dp(22)))
        NativeUi.textSize(fullTitle,28f);fullTitle.setTextColor(Color.WHITE);fullTitle.setPadding(dp(20),dp(14),dp(20),dp(14));fullTitle.background=GlassUi.surface(fullTitle,0f,video=true,dark=true,dense=true,border=false);fullTitle.visibility=View.GONE
        overlay.background=GlassUi.surface(overlay,0f,video=true,dark=true,dense=true,border=false);overlay.setPadding(dp(12),dp(10),dp(12),dp(12));overlay.visibility=View.GONE;overlay.addView(controls,LinearLayout.LayoutParams(-1,dp(48)))
        buildControls(false)
        drawer.background=GlassUi.surface(drawer,0f,video=true,dark=true,dense=true,border=false);drawer.setPadding(dp(12),dp(12),dp(12),dp(16))
        val drawerHeader=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        val drawerTitle=TextView(context).apply{text="频道";gravity=Gravity.CENTER_VERTICAL;includeFontPadding=false;NativeUi.textSize(this,28f);setTextColor(Color.WHITE);setPadding(dp(12),0,0,0)}
        drawerHeader.addView(drawerTitle,LinearLayout.LayoutParams(0,-1,1f))
        sourceButton=action(drawerHeader,"直播源",true){sources()}
        drawer.addView(drawerHeader,LinearLayout.LayoutParams(-1,dp(40)))
        filterRow.background=null;filterRow.setPadding(dp(2),dp(2),dp(2),dp(2))
        drawer.addView(filterRow,LinearLayout.LayoutParams(-1,dp(36)).apply{bottomMargin=dp(10)})
        setupList(drawerList);drawer.addView(drawerList,LinearLayout.LayoutParams(-1,0,1f))
        addView(fullTitle,LayoutParams(-1,-2,Gravity.TOP))
        addView(overlay,LayoutParams(-1,-2,Gravity.BOTTOM))
        addView(drawer,LayoutParams(dp(280),-1,Gravity.START))
        if(touchDevice)playerView.setOnClickListener{touchVideo()}
        refresh();showEmptyCover();if(repo.activeSource()==LiveRepository.DEFAULT_SOURCE&&pageFactory!=null)official()
    }
    private fun bindPlayer(){
        val boundPlayer=player
        player.addListener(object:Player.Listener {
            override fun onPlaybackStateChanged(state:Int){
                if(boundPlayer!==nativePlayer||state!=player.playbackState||released||officialPlaying())return
                ui.removeCallbacks(timeout)
                PlaybackDiagnostics.update("state",state);PlaybackDiagnostics.update("bufferedMs",player.totalBufferedDuration);PlaybackDiagnostics.update("liveOffsetMs",player.currentLiveOffset)
                if(BuildConfig.DEBUG&&current!=null)android.util.Log.i("TVBridgeMetrics","channel=${current?.name} state=$state bufferedMs=${player.totalBufferedDuration} liveOffsetMs=${player.currentLiveOffset}")
                when(state){Player.STATE_IDLE->{readySince=0};Player.STATE_READY->{readySince=SystemClock.elapsedRealtime();lastFailure="";hideCover();refreshTitle("正在播放")};Player.STATE_BUFFERING->{readySince=0;bufferingAt=SystemClock.elapsedRealtime();showCover("${current?.name.orEmpty()}\n正在缓冲",true);refreshTitle("正在缓冲");if(!paused)ui.postDelayed(timeout,20000)};Player.STATE_ENDED->{readySince=0;if(current?.id=="cast")refreshTitle("播放结束")else{lastFailure="直播连接结束";recover(lastFailure)}}}
            }
            override fun onPlayerError(error:PlaybackException){if(boundPlayer!==nativePlayer||error!==player.playerError)return;if(BuildConfig.DEBUG)android.util.Log.e("TVBridgePlayer",error.errorCodeName,error);if(!released&&!paused&&!officialPlaying())handlePlaybackError(error)}
        })
        player.addAnalyticsListener(object:AnalyticsListener{
            override fun onRenderedFirstFrame(eventTime:AnalyticsListener.EventTime,output:Any,renderTimeMs:Long){if(boundPlayer===nativePlayer&&(eventTime.mediaPeriodId==null||eventTime.mediaPeriodId==eventTime.currentMediaPeriodId)){routes.rendered(line);PlaybackDiagnostics.update("firstFrameMs",SystemClock.elapsedRealtime()-openedAt);player.videoFormat?.let{PlaybackDiagnostics.update("width",it.width);PlaybackDiagnostics.update("height",it.height)}}}
            override fun onVideoDecoderInitialized(eventTime:AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long){PlaybackDiagnostics.update("decoder",decoderName)}
            override fun onAudioDecoderInitialized(eventTime:AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long){PlaybackDiagnostics.update("audioDecoder",decoderName)}
            override fun onDroppedVideoFrames(eventTime:AnalyticsListener.EventTime,droppedFrames:Int,elapsedMs:Long){PlaybackDiagnostics.update("lastDroppedFrames",droppedFrames);PlaybackDiagnostics.update("droppedIntervalMs",elapsedMs)}
            override fun onLoadCompleted(eventTime:AnalyticsListener.EventTime,loadEventInfo:LoadEventInfo,mediaLoadData:MediaLoadData){recordLoad(loadEventInfo,mediaLoadData,null)}
            override fun onLoadError(eventTime:AnalyticsListener.EventTime,loadEventInfo:LoadEventInfo,mediaLoadData:MediaLoadData,error:IOException,wasCanceled:Boolean){if(!wasCanceled)recordLoad(loadEventInfo,mediaLoadData,error)}
        })
    }
    private fun resetAttempt(){attempts.reset(current?.streams?.getOrNull(line).orEmpty())}
    private fun multicastFor(url:String?){
        multicast?.let{if(it.isHeld)it.release()};multicast=null
        if(url?.startsWith("udp:",true)!=true)return
        try{val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            multicast=wifi?.createMulticastLock("One:live").also{it?.setReferenceCounted(false);it?.acquire()}
        }catch(_:RuntimeException){ }
    }
    private fun configureDecoder(){
        if(softwarePlayer==attempts.softwareTracks)return
        val next=try{NativePlayback.player(context,attempts.softwareTracks)}catch(t:Throwable){android.util.Log.w("TVBridgePlayer","configureDecoder failed: ${t.javaClass.simpleName} ${t.message}");return}
        val previous=nativePlayer;playerView.player=null
        softwarePlayer=attempts.softwareTracks;player=next
        bindPlayer();playerView.player=player
        previous?.let{try{it.release()}catch(_:Throwable){}}
    }
    private fun dp(value:Int)=(value*2*NativeUi.scale(context)).toInt()
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        val w=minOf(measuredWidth,(measuredHeight*16f/9).toInt()).coerceAtLeast(1)
        val h=(w*9f/16).toInt().coerceAtLeast(1)
        videoSurface.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
        val coverWidth=measuredWidth
        val coverHeight=measuredHeight
        title.measure(MeasureSpec.makeMeasureSpec(coverWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(coverHeight,MeasureSpec.EXACTLY))
        drawer.measure(MeasureSpec.makeMeasureSpec(dp(280),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(measuredHeight,MeasureSpec.EXACTLY))
        fullTitle.measure(MeasureSpec.makeMeasureSpec(measuredWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        overlay.measure(MeasureSpec.makeMeasureSpec(measuredWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int){
        super.onLayout(changed,left,top,right,bottom)
        val w=videoSurface.measuredWidth;val h=videoSurface.measuredHeight
        val scale=minOf(width.toFloat()/w,height.toFloat()/h)
        videoSurface.layout(0,0,w,h);videoSurface.pivotX=0f;videoSurface.pivotY=0f;videoSurface.scaleX=scale;videoSurface.scaleY=scale
        (videoSurface.background as GradientDrawable).cornerRadius=0f
        videoSurface.translationX=(width-w*scale)/2;videoSurface.translationY=(height-h*scale)/2
        title.layout(0,0,width,height)
        val cx=width/2;val cy=height/2-dp(12);val half=dp(11)
        loading.layout(cx-half,cy-half,cx+half,cy+half)
        drawer.layout(0,0,drawer.measuredWidth,height);fullTitle.layout(0,0,width,fullTitle.measuredHeight);overlay.layout(0,height-overlay.measuredHeight,width,height)
    }
    private fun showCover(message:String,busy:Boolean){title.text=if(busy)"加载中"else message;title.setPadding(dp(16),if(busy)dp(25)else 0,dp(16),0);title.visibility=View.VISIBLE;loading.visibility=if(busy)View.VISIBLE else View.GONE}
    private fun showEmptyCover(){showCover(if(repo.allChannels().isEmpty())"暂无频道，请添加直播源"else "请选择频道",false)}
    private fun hideCover(){title.visibility=View.GONE;loading.visibility=View.GONE}
    private fun categoryLabel(value:String)=when(value){"全部频道"->"全部";"收藏频道"->"收藏";"央视频道"->"央视";"地方卫视"->"卫视";else->value}
    private fun renderFilters(){
        val focused=filterRow.findFocus()?.tag
        filterRow.removeAllViews()
        val groups=all.map{it.group}.distinct()
        fun tab(button:Button,key:String,label:String,selected:Boolean,run:()->Unit){
            button.setCompoundDrawablesRelative(null,null,null,null);button.gravity=Gravity.CENTER;button.tag=key;button.text=label
            NativeUi.textSize(button,when{label.length<=2->26f;label.length<=4->23f;else->20f});button.isAllCaps=false;button.minWidth=0;button.minimumWidth=0;button.minHeight=0;button.minimumHeight=0;button.setPadding(dp(2),0,dp(2),0);button.includeFontPadding=false;button.maxLines=1;button.ellipsize=android.text.TextUtils.TruncateAt.END;if(android.os.Build.VERSION.SDK_INT>=21)button.backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)button.stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=26)button.defaultFocusHighlightEnabled=false
            button.background=GlassUi.surface(button,GlassUi.CHIP_RADIUS,video=true,dark=true,faint=true)
            button.setTextColor(liveFocusColors())
            button.isSelected=selected&&(!favoritesOnly||key=="收藏频道");button.setOnClickListener{run()}
            button.onFocusChangeListener=View.OnFocusChangeListener{_,hasFocus->if(hasFocus){
                if(key=="分类"){
                    if(favoritesOnly){favoritesOnly=false;refresh(false)}
                }else run()
            }}
            NativeUi.touchClicks(button)
            filterRow.addView(button,LinearLayout.LayoutParams(0,-1,1f).apply{if(filterRow.childCount>0)marginStart=dp(2)})
        }
        fun choose(value:String){if(category!=value||favoritesOnly){category=value;favoritesOnly=false;refresh(false)}}
        if(groups.size<=3){
            tab(groupButton,"全部频道","全部",category=="全部频道"){choose("全部频道")}
            groups.forEach{group->tab(Button(context),group,categoryLabel(group),category==group){choose(group)}}
        }else {
            tab(groupButton,"分类",categoryLabel(category),true){if(favoritesOnly){favoritesOnly=false;refresh(false)}else groups()}
            groupButton.gravity=Gravity.CENTER_VERTICAL or Gravity.START;groupButton.setPadding(dp(12),0,dp(12),0)
            val arrow=object:android.graphics.drawable.Drawable(){
                private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=groupButton.currentTextColor;style=android.graphics.Paint.Style.STROKE;strokeWidth=2*NativeUi.scale(context);strokeCap=android.graphics.Paint.Cap.ROUND}
                override fun draw(canvas:android.graphics.Canvas){paint.color=groupButton.currentTextColor;val b=bounds;val path=android.graphics.Path();path.moveTo(b.left+b.width()*.2f,b.top+b.height()*.4f);path.lineTo(b.centerX().toFloat(),b.top+b.height()*.7f);path.lineTo(b.left+b.width()*.8f,b.top+b.height()*.4f);canvas.drawPath(path,paint)}
                override fun setAlpha(alpha:Int){paint.alpha=alpha};override fun setColorFilter(filter:android.graphics.ColorFilter?){paint.colorFilter=filter};override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
            }.apply{setBounds(0,0,dp(10),dp(10))}
            groupButton.setCompoundDrawablesRelative(null,null,arrow,null)
        }
        tab(favoriteButton,"收藏频道","收藏",favoritesOnly){if(!favoritesOnly){favoritesOnly=true;refresh(false)}}
        if(focused!=null&&!isInTouchMode){val target=if(focused=="收藏频道"&&!favoritesOnly)groupButton.tag else focused;filterRow.findViewWithTag<View>(target)?.requestFocus()}
    }
    private var outsideDrawerGesture=false
    private var outsideDrawerX=0f
    private var outsideDrawerY=0f
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            outsideDrawerGesture=drawer.visibility==View.VISIBLE&&event.x>=drawer.right
            if(outsideDrawerGesture){outsideDrawerX=event.x;outsideDrawerY=event.y;return true}
        }else if(outsideDrawerGesture){
            if(event.actionMasked==MotionEvent.ACTION_UP){
                val slop=ViewConfiguration.get(context).scaledTouchSlop
                val tapped=kotlin.math.abs(event.x-outsideDrawerX)<=slop&&kotlin.math.abs(event.y-outsideDrawerY)<=slop
                outsideDrawerGesture=false
                if(tapped&&event.x>=drawer.right&&drawer.visibility==View.VISIBLE){drawer.visibility=View.GONE;hideControls()}
            }else if(event.actionMasked==MotionEvent.ACTION_CANCEL)outsideDrawerGesture=false
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    private fun touchVideo(){if(!touchDevice)return;if(drawer.visibility==View.VISIBLE){drawer.visibility=View.GONE;hideControls()}else if(overlay.visibility==View.VISIBLE)hideControls()else showControls()}
    private fun buildControls(cast:Boolean){
        controls.removeAllViews();favoriteAction=null;lineAction=null;qualityAction=null
        if(cast){
            action(controls,"后退 10 秒",true){command("videoBack","")}
            action(controls,"播放 / 暂停",true){command("videoToggle","")}
            action(controls,"前进 10 秒",true){command("videoForward","")}
        }else{
            if(touchDevice)action(controls,"上一台",true){stepChannel(-1)}
            action(controls,"频道列表",true){showDrawer()}
            favoriteAction=action(controls,"收藏",true){current?.let{repo.toggleFavorite(it.id);refreshTitle();refresh()}}
            lineAction=action(controls,"切换线路",true){nextLine()}
            qualityAction=action(controls,"超清",true){chooseQuality()}
            if(touchDevice)action(controls,"下一台",true){stepChannel(1)}
        }
        if(touchDevice)action(controls,"返回",true){exit()}
        val buttons = (0 until controls.childCount).map { controls.getChildAt(it) as Button }
        for (button in buttons) {
            button.setCompoundDrawablesRelative(null, null, null, null)
            button.maxLines = 1
            NativeUi.textSize(button, 24f)
        }
        var widest = 0
        for (button in buttons) {
            val textWidth = button.paint.measureText(button.text.toString()).toInt() + 1
            val natural = textWidth + button.paddingLeft + button.paddingRight
            if (natural > widest) {
                widest = natural
            }
        }
        for (i in buttons.indices) {
            val button = buttons[i]
            val last = i == buttons.size - 1
            button.layoutParams = LinearLayout.LayoutParams(widest, dp(40)).apply {
                marginEnd = if (last) 0 else dp(8)
            }
        }
    }
    private fun recordLoad(load:LoadEventInfo,data:MediaLoadData,error:IOException?){
        if(error==null&&data.dataType==C.DATA_TYPE_MEDIA&&load.bytesLoaded>0)mediaProgressAt=SystemClock.elapsedRealtime()
        val http=generateSequence<Throwable>(error){it.cause}.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val phase=when(data.dataType){C.DATA_TYPE_MANIFEST->"manifest";C.DATA_TYPE_MEDIA->"segment";else->"other"}
        val detail=if(http!=null)http.responseBody.toString(Charsets.UTF_8).take(500)else error?.javaClass?.simpleName.orEmpty()
        PlaybackDiagnostics.load(phase,load.dataSpec.uri.toString(),load.uri.toString(),load.loadDurationMs,load.bytesLoaded,http?.responseCode,http?.headerFields?:load.responseHeaders,detail)
    }
    private fun liveFocusColors(normal:Int=Color.WHITE)=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_pressed),intArrayOf(android.R.attr.state_selected),intArrayOf()),intArrayOf(normal,normal,normal,normal))
    private fun liveFocusBackground(normal:Int=Color.TRANSPARENT)=StateListDrawable().apply{
        fun border(stroke:Float)=GradientDrawable().apply{setColor(normal);cornerRadius=dp(10).toFloat();setStroke(stroke.toInt(),0xf0ffffff.toInt())}
        addState(intArrayOf(android.R.attr.state_focused),border(2.0f*NativeUi.scale(context)))
        addState(intArrayOf(android.R.attr.state_pressed),border(2.0f*NativeUi.scale(context)))
        addState(intArrayOf(android.R.attr.state_selected),border(2.0f*NativeUi.scale(context)))
        addState(intArrayOf(),GradientDrawable().apply{setColor(normal);cornerRadius=dp(10).toFloat()})
    }
    private fun showLiveDialog(builder:AlertDialog.Builder)=NativeUi.show(builder)
    private fun action(parent:LinearLayout,label:String,dark:Boolean=false,run:()->Unit):Button {
        val b=Button(context);if(android.os.Build.VERSION.SDK_INT>=21)b.backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)b.stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=21)b.elevation=0f;if(android.os.Build.VERSION.SDK_INT>=26)b.defaultFocusHighlightEnabled=false;b.text=label;b.includeFontPadding=false;AppIcons.bind(b,size=25f)
        val longLabel=label.length>=5;NativeUi.textSize(b,if(longLabel)22f else 24f);b.isAllCaps=false;b.minWidth=0;b.minimumWidth=0;b.minHeight=0;b.minimumHeight=0;b.setPadding(dp(if(longLabel)10 else 14),0,dp(if(longLabel)10 else 14),0)
        b.background=GlassUi.surface(b,24f,video=true,dark=true,faint=true)
        b.setTextColor(liveFocusColors(if(dark)Color.WHITE else 0xff292f32.toInt()))
        b.setOnFocusChangeListener{_,focus->if(focus){ui.removeCallbacks(hide);ui.postDelayed(hide,6000)}}
        b.setOnClickListener{run()};NativeUi.touchClicks(b);parent.addView(b,LinearLayout.LayoutParams(-2,dp(32)).apply{marginEnd=dp(8)});return b
    }
    private fun setupList(view:AbsListView,channelClicks:Boolean=true){if(android.os.Build.VERSION.SDK_INT>=26)view.defaultFocusHighlightEnabled=false;view.isVerticalScrollBarEnabled=false;view.setSelector(if(channelClicks)android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) else liveFocusBackground());view.isFocusable=true;view.isFocusableInTouchMode=true
        view.setOnFocusChangeListener{_,focused->
            for(i in 0 until view.childCount)(view.getChildAt(i) as? TextView)?.let{label->label.isSelected=focused&&view.firstVisiblePosition+i==view.selectedItemPosition;label.setTextColor(liveFocusColors())}
        }
        view.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,child:View?,position:Int,id:Long){for(i in 0 until view.childCount)view.getChildAt(i).isSelected=view.hasFocus()&&view.firstVisiblePosition+i==position}
        }
        if(channelClicks)view.setOnItemClickListener{_,_,position,_->shown.getOrNull(position)?.let{play(it,true)}}}
    fun refresh(rebuildFilters:Boolean=true){
        all=repo.allChannels()
        val empty=all.isEmpty()&&repo.favoriteChannels().isEmpty()&&current?.id!="cast"
        if(empty&&isAttachedToWindow){
            noSources?.invoke();return
        }
        val active=repo.activeSource()
        if(lastSource!=active||category !in (listOf("全部频道")+all.map{it.group})){category="全部频道";lastSource=active}
        if(current!=null&&current?.id!="cast"&&repo.findChannel(current!!.id)==null){cancelRecovery();officialPlayer?.stop();nativePlayer?.stop();current=null;ui.removeCallbacks(timeout);showEmptyCover()}
        shown=if(favoritesOnly)repo.favoriteChannels()else all.filter{category=="全部频道"||it.group==category}
        if(rebuildFilters)renderFilters()else for(i in 0 until filterRow.childCount){val tab=filterRow.getChildAt(i);tab.isSelected=when(tab.tag){"收藏频道"->favoritesOnly;"分类"->!favoritesOnly;else->!favoritesOnly&&tab.tag==category}}
        fun adapter()=object:ArrayAdapter<LiveChannel>(context,android.R.layout.simple_list_item_1,shown){override fun getView(position:Int,convert:View?,parent:ViewGroup):View{val item=getItem(position)!!;val label=(convert as? TextView)?:TextView(context);label.text=item.name;label.setCompoundDrawablesRelative(null,null,null,null);if(current?.id==item.id||repo.favorite(item.id))AppIcons.bind(label,if(current?.id==item.id)"play"else "star",16f);NativeUi.textSize(label,24f);label.setTextColor(liveFocusColors());label.isSelected=parent.hasFocus()&&(parent as? AbsListView)?.selectedItemPosition==position;label.gravity=Gravity.CENTER_VERTICAL;label.setPadding(dp(8),0,dp(6),0);label.minHeight=dp(if(touchDevice)42 else 36);label.maxLines=2;label.ellipsize=android.text.TextUtils.TruncateAt.END;label.background=GlassUi.surface(label,GlassUi.CHIP_RADIUS,video=true,dark=true,faint=true);return label}}
        drawerList.adapter=adapter()
        if(current!=null)refreshTitle()
    }
    fun play(channel:LiveChannel,full:Boolean=false){
        if(current?.id==channel.id&&current?.streams==channel.streams){if(full)closePanels();if(if(officialPlaying())officialFailed else player.playbackState==Player.STATE_IDLE||player.playerError!=null){routes.manual();retries=0;lastFailure="";resetAttempt();startLine()};return}
        if((current?.id=="cast")!=(channel.id=="cast"))buildControls(channel.id=="cast")
        cancelRecovery();repo.rememberChannel(channel.id);current=channel;line=routes.begin(channel);retries=0;lastFailure="";resetAttempt();paused=false;drawer.visibility=View.GONE
        if(full)closePanels()
        startLine();drawerList.invalidateViews()
    }
    private fun official():PagePlayback=officialPlayer?:requireNotNull(pageFactory).invoke(context,{touchVideo()},{message,ready->
                if(!released&&officialPlaying()){
                    officialFailed=message.contains("失败")||message.contains("无法")||message.contains("未能")||message.contains("异常")||message.contains("HTTP")
                    if(ready)hideCover()else showCover("${current?.name.orEmpty()}\n$message",!officialFailed);refreshTitle(message)
                }
            }).also{officialPlayer=it;videoSurface.addView(it.view,LayoutParams(-1,-1))}
    private fun startLine(forceOfficialReload:Boolean=false){
        val channel=current?:return;val url=channel.streams.getOrNull(line)?:return
        cancelRecovery();playbackEpoch++
        openedAt=SystemClock.elapsedRealtime()
        bufferingAt=openedAt;mediaProgressAt=openedAt
        multicastFor(if(paused)null else url)
        PlaybackDiagnostics.start(channel.name,url,line)
        if(BuildConfig.DEBUG)android.util.Log.i("TVBridgeMetrics","open channel=${channel.name} line=$line host=${android.net.Uri.parse(url).host}")
        ui.removeCallbacks(timeout);refreshTitle("连接中");showCover("${channel.name}\n正在连接",true)
        if(LiveCatalog.isPage(url)){
            try{nativePlayer?.stop()}catch(_:Throwable){}
            try{nativePlayer?.release()}catch(_:Throwable){}
            nativePlayer=null;playerView.player=null;softwarePlayer=-1
            playerView.visibility=View.GONE;officialFailed=false
            if(pageFactory==null){showCover("直播暂不可用，请重试",false);return}
            val web=official()
            web.open(url,forceOfficialReload);return
        }
        officialPlayer?.stop();playerView.visibility=View.VISIBLE
        try{
            configureDecoder()
            PlaybackDiagnostics.update("formatAttempt",attempts.format.name)
            PlaybackDiagnostics.update("softwareFallback",attempts.software)
            PlaybackDiagnostics.update("softwareTracks",attempts.softwareTracks)
            PlaybackDiagnostics.update("rtspTcp",attempts.rtspTcp)
            player.setMediaSource(NativePlayback.source(context,url,attempts,loadPolicy));player.prepare();player.playWhenReady=true;ui.postDelayed(timeout,20000);fullTitle.visibility=View.VISIBLE;ui.removeCallbacks(hide);ui.postDelayed(hide,1800)
        }catch(t:Throwable){
            android.util.Log.w("TVBridgePlayer","startLine failed: ${t.javaClass.simpleName} ${t.message}")
            failLine()
        }
    }
    private fun maybeResetRetries(){val now=SystemClock.elapsedRealtime();if((readySince>0&&now-readySince>=10_000)||(lastErrorAt>0&&now-lastErrorAt>=60_000))retries=0}
    private fun recover(reason:String){
        if(released||paused||current==null)return
        maybeResetRetries();lastErrorAt=SystemClock.elapsedRealtime()
        if(BuildConfig.DEBUG)android.util.Log.w("TVBridgePlayer","$reason; line=$line retry=$retries")
        cancelRecovery()
        if(retries<2){retries++;showCover("${current?.name.orEmpty()}\n正在重新连接",true);refreshTitle("重新连接 $retries/2");ui.removeCallbacks(timeout);val epoch=playbackEpoch;pendingRetry=Runnable{pendingRetry=null;if(!released&&!paused&&epoch==playbackEpoch)startLine()}.also{ui.postDelayed(it,1200)}}else{retries=0;failLine()}
    }
    private fun cancelRecovery(){pendingRetry?.let{ui.removeCallbacks(it)};pendingRetry=null}
    private fun handlePlaybackError(error:PlaybackException){
        if(current==null)return
        maybeResetRetries();lastErrorAt=SystemClock.elapsedRealtime()
        PlaybackDiagnostics.update("error",error.errorCodeName)
        val http=generateSequence<Throwable>(error){it.cause}.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        when(error.errorCode){
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW->{
                cancelRecovery();ui.removeCallbacks(timeout);lastFailure="直播进度落后，请切换线路重试"
                if(retries>=2){failLine();return}
                retries++;refreshTitle("正在同步直播进度")
                player.seekToDefaultPosition();player.prepare();player.playWhenReady=true;ui.postDelayed(timeout,20000)
            }
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS->{lastFailure=if(http!=null)"源站返回 HTTP $http"else "源站拒绝请求";if(http in listOf(401,403,404,410))failLine()else recover(error.errorCodeName)}
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED->{lastFailure="无法连接直播源";if(attempts.tcpFallback())startLine()else recover(error.errorCodeName)}
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT->{lastFailure="直播源连接超时";if(attempts.tcpFallback())startLine()else recover(error.errorCodeName)}
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,PlaybackException.ERROR_CODE_DECODING_FAILED,PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED->{lastFailure="设备无法解码此线路";val mime=(error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType.orEmpty();val track=if(mime.startsWith("audio/"))1 else if(mime.startsWith("video/"))2 else 3;if(attempts.softwareFallback(NativePlayback.softwareAvailable(),track))startLine()else failLine()}
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED->{lastFailure="直播流格式异常";failLine(true)}
            else->{lastFailure="读取直播流失败";if(attempts.tcpFallback())startLine()else recover(error.errorCodeName)}
        }
    }
    private fun failLine(allowFormatFallback:Boolean=false){
        val channel=current?:return;ui.removeCallbacks(timeout)
        cancelRecovery()
        if(allowFormatFallback&&attempts.nextFormat()){startLine();return}
        val next=routes.next(line)
        if(next!=null){line=next;retries=0;resetAttempt();startLine()}else{multicastFor(null);player.stop();val reason=lastFailure.ifBlank{"播放失败，请切换线路"};refreshTitle(reason);showCover(reason,false);fullTitle.visibility=View.VISIBLE}
    }
    private fun refreshTitle(state:String=""){qualityAction?.visibility=if(officialPlaying())View.VISIBLE else View.GONE;qualityAction?.text=when(officialPlayer?.quality){"hd"->"高清";else->"超清"};favoriteAction?.text=if(current?.let{repo.favorite(it.id)}==true)"已收藏"else "收藏";lineAction?.text=if(officialPlaying())"重新加载"else "切换线路";val channel=current;val text=channel?.let{it.name+(if(it.id=="cast")""else if(officialPlaying())" · 默认"else " · 线路 ${line+1}/${it.streams.size}")+(if(repo.favorite(it.id))" · 已收藏" else "")+(if(state.isNotEmpty())" · $state" else "")}?:"直播";fullTitle.text=text}
    private fun chooseQuality(){
        val anchor=qualityAction?:return;val web=officialPlayer?:return
        groupPopup?.dismiss();val popup=ListPopupWindow(context);groupPopup=popup
        val options=listOf("高清","超清");val values=listOf("hd","fhd")
        popup.anchorView=anchor;popup.width=dp(128);popup.height=dp(84);popup.isModal=true;popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popup.setAdapter(object:ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,options){override fun getView(position:Int,convert:View?,parent:ViewGroup):View=TextView(context).apply{text=options[position];NativeUi.textSize(this,26f);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0);minHeight=dp(38);setTextColor(Color.WHITE)}})
        popup.setOnItemClickListener{_,_,position,_->web.setQuality(values[position]);refreshTitle();popup.dismiss();anchor.requestFocus()};popup.show();popup.listView?.let{it.background=GlassUi.surface(it,20f,video=true,dark=true,dense=true);it.selector=liveFocusBackground()}
    }
    private fun nextLine(){val channel=current?:return;if(officialPlaying()){startLine(true);return};routes.manual();line=(line+1)%channel.streams.size;retries=0;lastFailure="";resetAttempt();startLine()}
    private fun stepChannel(step:Int){
        val items=shown.ifEmpty{all}
        if(items.isEmpty())return
        val index=items.indexOfFirst{it.id==current?.id}
        val next=(index+step)%items.size
        play(items[if(next<0)next+items.size else next],true)
    }
    private fun groups(){
        groupPopup?.dismiss()
        val groups=listOf("全部频道")+all.map{it.group}.distinct()
        val popup=ListPopupWindow(context);groupPopup=popup
        popup.anchorView=groupButton;popup.isModal=true;popup.width=if(groupButton.width>0)groupButton.width else dp(128);popup.height=dp((groups.size*38+8).coerceAtMost(250));popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popup.setAdapter(object:ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,groups){override fun getView(position:Int,convert:View?,parent:ViewGroup):View=TextView(context).apply{text=categoryLabel(groups[position]);NativeUi.textSize(this,24f);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0);minHeight=dp(36);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;val selPos=(parent as? AbsListView)?.selectedItemPosition;val selected=if(selPos!=null&&selPos>=0)selPos==position else groups[position]==category;setTextColor(if(selected)Color.WHITE else 0xffc8d0d8.toInt());setTypeface(null,if(selected)Typeface.BOLD else Typeface.NORMAL)}})
        popup.setOnItemClickListener{_,_,position,_->category=groups[position];favoritesOnly=false;popup.dismiss();groupButton.text=categoryLabel(category);refresh(false);if(!isInTouchMode)groupButton.requestFocus()}
        popup.show();popup.listView?.let{it.background=GlassUi.surface(it,20f,video=true,dark=true,dense=true,alphaBoost=0x20);it.setPadding(dp(8),dp(8),dp(8),dp(8));it.selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);it.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(parent:AdapterView<*>?){} override fun onItemSelected(parent:AdapterView<*>?,child:View?,position:Int,id:Long){for(i in 0 until it.childCount){val row=it.getChildAt(i) as? TextView ?: continue;val selected=it.firstVisiblePosition+i==position;row.setTypeface(null,if(selected)Typeface.BOLD else Typeface.NORMAL);row.setTextColor(if(selected)Color.WHITE else 0xffc8d0d8.toInt())}}};it.post{it.requestFocusFromTouch();it.setSelection(groups.indexOf(category).coerceAtLeast(0))}}
    }
    private fun sources(){
        groupPopup?.dismiss();val entries=repo.sourceList()
        val labels=entries.map{"${it.name} · ${it.channels.size} 台"}+"更新订阅"+"删除当前源"
        val popup=ListPopupWindow(context);groupPopup=popup;popup.anchorView=sourceButton.parent as View;popup.isModal=true;popup.width=if(drawer.width>0)drawer.width-dp(24) else dp(280)-dp(24);popup.height=dp((labels.size*38+16).coerceAtMost(250));popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popup.setAdapter(object:ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,labels){override fun getView(position:Int,convert:View?,parent:ViewGroup)=TextView(context).apply{text=labels[position];NativeUi.textSize(this,24f);val selPos=(parent as? AbsListView)?.selectedItemPosition;val selected=if(selPos!=null&&selPos>=0)selPos==position else entries.getOrNull(position)?.url==repo.activeSource();setTextColor(if(selected)Color.WHITE else 0xffc8d0d8.toInt());setTypeface(null,if(selected)Typeface.BOLD else Typeface.NORMAL);background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0);minHeight=dp(38);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            if(entries.getOrNull(position)?.url==repo.activeSource()){
                val mark=AppIcons.Vector("check"){currentTextColor}.apply{val size=(22*NativeUi.scale(context)).toInt();setBounds(0,0,size,size)}
                setCompoundDrawablesRelative(null,null,mark,null);compoundDrawablePadding=dp(8);contentDescription=labels[position]+"，已选择"
            }
        }})
        popup.setOnItemClickListener{_,_,index,_->popup.dismiss();when{
            index<entries.size->{repo.selectSource(entries[index].url);favoritesOnly=false;refresh();all.firstOrNull()?.let{play(it,true)}}
            index==entries.size->{val active=repo.activeSource();if(active!="local")Thread({repo.subscribe(active);ui.post{if(!released){refresh();NativeUi.notice(context,repo.lastMessage)}}},"live-update").start()}
            else->showLiveDialog(AlertDialog.Builder(context).setMessage("删除当前直播源？").setPositiveButton("删除"){_,_->repo.removeSource(repo.activeSource());refresh();all.firstOrNull()?.let{play(it,true)}}.setNegativeButton("取消",null))
        }};popup.show();popup.listView?.let{it.background=GlassUi.surface(it,20f,video=true,dark=true,dense=true,alphaBoost=0x20);it.setPadding(dp(8),dp(8),dp(8),dp(8));it.divider=null;it.dividerHeight=0;setupList(it,false);it.selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);it.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(parent:AdapterView<*>?){} override fun onItemSelected(parent:AdapterView<*>?,child:View?,position:Int,id:Long){for(i in 0 until it.childCount){val row=it.getChildAt(i) as? TextView ?: continue;val selected=it.firstVisiblePosition+i==position;row.setTypeface(null,if(selected)Typeface.BOLD else Typeface.NORMAL);row.setTextColor(if(selected)Color.WHITE else 0xffc8d0d8.toInt())}}};it.post{it.requestFocusFromTouch();it.setSelection(entries.indexOfFirst{it.url==repo.activeSource()}.coerceAtLeast(0))}}
    }
    private fun closePanels(){drawer.visibility=View.GONE;hideControls()}
    private fun showControls(focus:Boolean=!touchDevice){fullTitle.visibility=View.VISIBLE;overlay.visibility=View.VISIBLE;ui.removeCallbacks(hide);if(focus){if(!controls.hasFocus())controls.getChildAt(0)?.requestFocus()}else ui.postDelayed(hide,5000)}
    private fun hideControls(){ui.removeCallbacks(hide);overlay.visibility=View.GONE;fullTitle.visibility=View.GONE;isFocusableInTouchMode=true;requestFocus()}
    private fun showDrawer(){ui.removeCallbacks(hide);overlay.visibility=View.GONE;fullTitle.visibility=View.GONE;drawer.visibility=View.VISIBLE;drawerList.setSelection(shown.indexOfFirst{it.id==current?.id}.coerceAtLeast(0));drawerList.requestFocus()}
    fun back():Boolean{if(groupPopup?.isShowing==true){groupPopup?.dismiss();return true};if(drawer.visibility==View.VISIBLE){drawer.visibility=View.GONE;hideControls();return true};if(overlay.visibility==View.VISIBLE){hideControls();return true};return false}
    private var channelDigits=""
    private val selectNumber=Runnable{
        val number=channelDigits.toIntOrNull();channelDigits=""
        val channels=shown.ifEmpty{all}
        if(number!=null&&number in 1..channels.size){drawer.visibility=View.GONE;hideControls();play(channels[number-1],true)}
        else if(number!=null)NativeUi.notice(context,"频道不存在")
    }
    fun handleKey(code:Int):Boolean {
        val digit=when(code){in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9->code-KeyEvent.KEYCODE_0;in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9->code-KeyEvent.KEYCODE_NUMPAD_0;else->-1}
        if(digit>=0&&groupPopup?.isShowing!=true){
            channelDigits=(channelDigits+digit).takeLast(4);ui.removeCallbacks(selectNumber)
            NativeUi.notice(context,"频道 "+channelDigits);ui.postDelayed(selectNumber,850);return true
        }
        if(channelDigits.isNotEmpty()){
            if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){ui.removeCallbacks(selectNumber);selectNumber.run();return true}
            ui.removeCallbacks(selectNumber);channelDigits=""
        }

        groupPopup?.takeIf{it.isShowing}?.let{popup->
            if(code==KeyEvent.KEYCODE_BACK){popup.dismiss();return true}
            if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){val index=popup.selectedItemPosition;if(index>=0)popup.performItemClick(index);return true}
            popup.listView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));popup.listView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code));return true
        }
        if(code==KeyEvent.KEYCODE_MENU){if(current?.id=="cast")showControls(true)else if(drawer.visibility==View.VISIBLE){drawer.visibility=View.GONE;hideControls()}else showDrawer();return true}
        if(drawer.visibility==View.VISIBLE){
            if(code==KeyEvent.KEYCODE_DPAD_LEFT){
                val now=System.currentTimeMillis();val col=drawerList.selectedItemPosition.let{if(it<0)-1 else it%2}
                if(now-lastLeftMs<500&&lastLeftColumn==0){lastLeftMs=0;lastLeftColumn=-1;drawerList.setSelection(0);drawerList.requestFocus();return true}
                lastLeftMs=now;lastLeftColumn=col;lastRightMs=0;lastRightColumn=-1;return false
            }
            if(code==KeyEvent.KEYCODE_DPAD_RIGHT){
                val now=System.currentTimeMillis();val col=drawerList.selectedItemPosition.let{if(it<0)-1 else it%2}
                if(now-lastRightMs<500&&lastRightColumn==1){lastRightMs=0;lastRightColumn=-1;val last=(drawerList.adapter?.count?:0)-1;if(last>=0){drawerList.setSelection(last);drawerList.requestFocus()};return true}
                lastRightMs=now;lastRightColumn=col;lastLeftMs=0;lastLeftColumn=-1;return false
            }
            lastLeftMs=0;lastLeftColumn=-1;lastRightMs=0;lastRightColumn=-1;return false
        }
        val navigation=code in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)
        if(!navigation)return false
        if(overlay.visibility!=View.VISIBLE){
            if(current?.id=="cast"){
                when(code){KeyEvent.KEYCODE_DPAD_LEFT->command("videoBack","");KeyEvent.KEYCODE_DPAD_RIGHT->command("videoForward","");else->showControls(true)};return true
            }
            when(code){
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_LEFT->stepChannel(-1)
                KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT->stepChannel(1)
                else->showControls(true)
            }
            return true
        }
        if(!controls.hasFocus()){showControls(true);return true}
        ui.removeCallbacks(hide)
        val buttons=(0 until controls.childCount).map{controls.getChildAt(it)}.filter{it.visibility==View.VISIBLE}
        if(buttons.isEmpty())return true
        val index=buttons.indexOfFirst{it.hasFocus()}.coerceAtLeast(0)
        when(code){
            KeyEvent.KEYCODE_DPAD_LEFT->buttons[(index-1).coerceAtLeast(0)].requestFocus()
            KeyEvent.KEYCODE_DPAD_RIGHT->buttons[(index+1).coerceAtMost(buttons.size-1)].requestFocus()
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->buttons[index].performClick()
        }
        return true
    }
    fun command(cmd:String,value:String):Boolean=when(cmd){"videoToggle"->{if(officialPlaying())officialPlayer?.toggle()else if(player.playWhenReady)player.pause()else player.play();true};"videoPause"->{if(officialPlaying())officialPlayer?.pauseVideo()else player.pause();true};"videoBack"->{if(!officialPlaying())player.seekTo((player.currentPosition-10000).coerceAtLeast(0));true};"videoForward"->{if(!officialPlaying())player.seekTo(player.currentPosition+10000);true};"videoFull"->{showControls();true};"liveNext"->{stepChannel(1);true};"livePrev"->{stepChannel(-1);true};"liveLine"->{nextLine();true};"liveFull"->{showDrawer();true};"volumeUp","volumeDown"->{(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC,if(cmd=="volumeUp")AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI);true};else->false}
    fun pause(){ui.removeCallbacks(selectNumber);channelDigits="";multicastFor(null);resumePlayback=nativePlayer?.playWhenReady==true;paused=true;cancelRecovery();officialPlayer?.suspend();nativePlayer?.pause();ui.removeCallbacks(timeout);ui.removeCallbacks(hide)}
    fun resume(){if(!released&&paused&&current!=null){paused=false;multicastFor(current?.streams?.getOrNull(line));if(officialPlaying()){officialPlayer?.resume();return};if(resumePlayback){if(current?.id!="cast")player.seekToDefaultPosition();if(player.playerError!=null)player.prepare();player.play();ui.postDelayed(timeout,20000)}}}
    fun stop(){ui.removeCallbacks(selectNumber);channelDigits="";multicastFor(null);groupPopup?.dismiss();released=true;loading.visibility=View.GONE;ui.removeCallbacksAndMessages(null);officialPlayer?.release();officialPlayer=null;playerView.player=null;nativePlayer?.release();nativePlayer=null}
}
