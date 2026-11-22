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

class TvHomeView(context:Context,private val liveAction:Runnable,private val filesAction:Runnable,settings:Runnable,refresh:Runnable,private val browseAction:Runnable=Runnable{}):ViewGroup(context){
    private val paint=android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val regular=Typeface.create("sans-serif",Typeface.NORMAL)
    private val bold=Typeface.create("sans-serif",Typeface.BOLD)
    private val clockFont=Typeface.create("sans-serif",Typeface.BOLD)
    private val timeFormat=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault())
    private val dateFormat=java.text.SimpleDateFormat("yyyy年M月d日  EEEE",java.util.Locale.SIMPLIFIED_CHINESE)
    private val tiles=mutableListOf<AppTileView>()
    private var moving:String?=null
    private val moveHint=android.widget.TextView(context).apply{
        text="方向键移动，或点击要放入的位置，按确认键完成"
        setTextColor(Color.WHITE)
        typeface=bold
        isSingleLine=true
        gravity=Gravity.CENTER
        NativeUi.textSize(this,30f)
        val sx=NativeUi.scale(context)
        setPadding((20*sx).toInt(),(10*sx).toInt(),(20*sx).toInt(),(10*sx).toInt())
        visibility=View.GONE
        isFocusable=false
    }
    private val controlButton=object:Button(context){
        private val icon=AppIcons.Vector("settings"){currentTextColor}
        override fun onDraw(canvas:Canvas){
            val size=(40*NativeUi.scale(context)).toInt()
            val left=(width-size)/2;val top=(height-size)/2
            icon.setBounds(left,top,left+size,top+size);icon.draw(canvas)
        }
    }
    private val launcherApps=LauncherApps(context)
    private val connection=HomeConnection(controlButton){invalidate()}
    private val appMenu=HomeAppMenu(context,launcherApps,object:HomeAppMenu.Host{
        override val scale get()=this@TvHomeView.scale
        override val darkHome get()=this@TvHomeView.darkHome
        override fun tileKey(tile:AppTileView)=this@TvHomeView.tileKey(tile)
        override fun isMoving()=this@TvHomeView.moving!=null
        override fun enterMove(id:String)=this@TvHomeView.enterMove(id)
        override fun refreshApps()=this@TvHomeView.refreshApps()
        override fun focusEntry(id:String)=this@TvHomeView.focusEntry(id)
        override fun focusFirst()=this@TvHomeView.focusFirst()
        override fun invalidate()=this@TvHomeView.invalidate()
        override fun requestLayout()=this@TvHomeView.requestLayout()
    })
    private var appRow=android.widget.GridLayout(context).apply{columnCount=6;clipChildren=false;clipToPadding=false}
    private val appRail=android.widget.FrameLayout(context).apply{clipChildren=false;addView(appRow)}
    private var scale=1f;private var gridMargin=0;private var tileGap=0;private var tileWidth=0;private var tileHeight=0;private var oy=0f
    private var styledScale=-1f
    private var styledWidth=-1
    var drawsBackdrop=true
    private var darkHome=HomeAppearance.dark(context)
    private var appearanceKey=HomeAppearance.key(context)
    private val ink get()=GlassUi.ink(context,darkHome)
    private var appSignature:String?=null
    private var initialRefreshPending=true
    private var initialGridFocusPending=true
    private var catalogRefreshPending=false
    private val artInFlight=mutableSetOf<String>()
    private var currentFolders: Map<String, List<String>> = emptyMap()
    private var currentEntries: Map<String, LauncherApps.Entry> = emptyMap()
    private val stockHomeId get() = LauncherApps.STOCK_HOME_ID
    private var stockHomeEntry: LauncherApps.Entry? = null
    private val mainHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val labelCache=java.util.WeakHashMap<AppTileView,Array<Any?>>()
    private var rememberedFocus=""
    private var lastGridFocus=""
    private var focusedTile:AppTileView?=null
    private fun tileKey(tile:AppTileView)=(tile.tag as? LauncherApps.Entry)?.component?.flattenToString() ?: tile.tag as String
    private var lastClockMinute=-1L
    private val rows get()=maxOf(1,(tiles.size+cols-1)/cols)
    private var cols=6
    private val topRowY get()=992f-tileHeight/scale
    private val restY=409f
    private val collapseDip=382f
    private val pitchY get()=tileHeight/scale+108f
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
            val accel=-280f*(scrollY-scrollTarget)-33.5f*scrollVel
            scrollVel+=accel*dt;scrollY+=scrollVel*dt
            if(Math.abs(scrollY-scrollTarget)<0.6f&&Math.abs(scrollVel)<8f){scrollY=scrollTarget;scrollVel=0f;applyScroll();return}
            applyScroll()
            springPosted=true;choreographer.postFrameCallback(this)
        }
    }
    private fun applyScrollTransforms() {
        val railOffset = -scrollY * scale
        appRail.translationY = railOffset
        val lift = (liftFrac / .55f).coerceIn(0f, 1f)
        val dissolve = lift * lift * (3f - 2f * lift)
        val swell = 28f * scale * dissolve
        plateView.translationY = railOffset
        plateView.scaleX = 1f + 2f * swell / plateView.measuredWidth.coerceAtLeast(1)
        plateView.scaleY = 1f + 2f * swell / plateView.measuredHeight.coerceAtLeast(1)
        plateView.alpha = 1f - dissolve
    }
    private fun applyScroll() {
        applyRowAlphas()
        applyScrollTransforms()
        invalidate()
    }
    private fun railTopY(): Float = appRail.top + appRail.translationY
    private fun invalidateTileRegion(tile: AppTileView) {
        val padX = tile.width * 2
        val padTop = (64f * scale).toInt()
        val padBottom = (104f * scale).toInt()
        val l = (appRail.left + tile.left - padX).coerceAtLeast(0)
        val t = (railTopY() + tile.top - padTop).toInt().coerceAtLeast(0)
        val r = (appRail.left + tile.right + padX).coerceAtMost(width)
        val b = (railTopY() + tile.bottom + padBottom).toInt().coerceAtMost(height)
        if (r > l && b > t) invalidate(l, t, r, b)
    }
    private fun invalidateTileShadow(tile: AppTileView) {
        if (liftFrac <= 0.01f || moving != null) return
        val pad = (64f * scale).toInt()
        val l = (appRail.left + tile.left - pad).coerceAtLeast(0)
        val t = (railTopY() + tile.top - pad).toInt().coerceAtLeast(0)
        val r = (appRail.left + tile.right + pad).coerceAtMost(width)
        val b = (railTopY() + tile.bottom + pad + (30f * scale).toInt()).toInt().coerceAtMost(height)
        if (r > l && b > t) invalidate(l, t, r, b)
    }
    private fun applyRowAlphas(){val a=gridAlpha();tiles.forEachIndexed{i,tile->val focusedDrag=touchDragging&&tile===focusedTile;tile.alpha=if(i<cols||focusedDrag)1f else a;if(!focusedDrag)tile.plateFrac=1f-liftFrac}}
    private fun animateScrollTo(target:Float){
        scrollTarget=target.coerceAtLeast(0f)
        if(!springPosted&&scrollY!=scrollTarget){springPosted=true;lastFrameNanos=0L;choreographer.postFrameCallback(springFrame)}
    }
    private fun scrollTargetFor(row:Int):Float{
        if(row<=0)return 0f
        val wanted=topRowY+pitchY*row-restY
        val bottom=topRowY+pitchY*rows+16f-1080f
        return minOf(wanted,maxOf(bottom,topRowY+pitchY-restY))
    }
    private val liftFrac get()=(scrollY/collapseDip).coerceIn(0f,1f)
    private fun shelfAlpha()=(1f-1.6f*liftFrac).coerceIn(0f,1f)
    private fun gridAlpha()=(2.5f*liftFrac).coerceIn(0f,1f)
    private fun onTileFocused(tile:AppTileView){
        if(touchDragging)return
        val index=tiles.indexOf(tile);if(index>=0)animateScrollTo(scrollTargetFor(index/cols))
    }

    private data class Module(val id:String,val title:String,val glyph:String,val top:Int,val bottom:Int,val run:Runnable)
    private val modules get()=AppModules.all.zip(listOf(liveAction,filesAction,browseAction)).map{Module(it.first.id,it.first.title,it.first.glyph,it.first.top,it.first.bottom,it.second)}

    fun refreshAppearance(){
        val dark=HomeAppearance.dark(context)
        if(appearanceKey!=HomeAppearance.key(context)){appearanceKey=HomeAppearance.key(context);darkHome=dark;styledScale=-1f;refreshApps();requestLayout();invalidate()}
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
        addView(moveHint)
        viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{
            override fun onPreDraw():Boolean{
                viewTreeObserver.removeOnPreDrawListener(this)
                if(initialRefreshPending)post{refreshApps();if(findFocus()==null)focusFirst()}
                return true
            }
        })
    }

    fun refreshApps(){
        if(!launcherApps.catalogReady()){
            if(!catalogRefreshPending){
                catalogRefreshPending=true
                AppThreads.onIo{
                    launcherApps.installed()
                    mainHandler.post{catalogRefreshPending=false;refreshApps();if(findFocus()==null)focusFirst()}
                }
            }
            return
        }
        initialRefreshPending=false
        val newCols=HomeAppearance.homeColumns(context)
        var focusBeforeSwap:String?=null
        if(newCols!=cols){
            focusBeforeSwap=(findFocus() as? AppTileView)?.let(::tileKey)
            cols=newCols;appSignature=null
            val fresh=android.widget.GridLayout(context).apply{columnCount=newCols;clipChildren=false;clipToPadding=false}
            appRail.removeView(appRow);appRow=fresh;appRail.addView(fresh)
        }
        val all=launcherApps.orderedIds()
        val hidden=launcherApps.hiddenIds().toSet()
        val folders=launcherApps.folderLayout(all).mapKeys{"folder:"+it.key}.mapValues{(_,members)->members.filterNot{it in hidden}}.filterValues{it.isNotEmpty()}
        val grouped=folders.entries.flatMap{(folder,members)->members.map{it to folder}}.toMap()
        val baseOrder=all.filterNot{it in hidden}.map{grouped[it]?:it}.distinct()
        val entries=launcherApps.installed().associateBy{it.component.flattenToString()}
        currentFolders=folders
        currentEntries=entries
        val stockEntry=launcherApps.stockHomeEntry()
        stockHomeEntry=stockEntry
        val order=baseOrder
        val cold=entries.values.filter{!launcherApps.squareCached(it)}
        if(cold.isNotEmpty()||(stockEntry!=null&&!launcherApps.squareCached(stockEntry))){
            val queue=(order.mapNotNull{entries[it]}+cold+listOfNotNull(stockEntry)).distinct()
            val fresh=queue.filter{artInFlight.add(it.component.flattenToString())}
            if(fresh.isNotEmpty()){
                AppThreads.onIo{
                    fresh.forEach{runCatching{launcherApps.readTileHeader(it)}}
                    mainHandler.post{fresh.forEach{applyTileTint(it)}}
                }
                fresh.forEach{entry->
                    AppThreads.onIo{
                        runCatching{launcherApps.squareIcon(entry)}
                        mainHandler.post{
                            artInFlight.remove(entry.component.flattenToString())
                            applyTileArt(entry)
                        }
                    }
                }
                AppThreads.onIo{launcherApps.keepTiles(entries.values.toList()+listOfNotNull(stockEntry))}
            }
        }
        val builtIns=modules.associateBy{it.id}
        val moduleTitles=AppModules.all.associate{it.id to it.title}
        val signature="B3:"+order.sorted().joinToString{id->val entry=entries[id];val fallback=moduleTitles[id]?:entry?.label?:if(id==stockHomeId)stockEntry?.label else null;"$id:${fallback?.let{title->launcherApps.displayTitle(id,title)}}:${entry?.updated}"}+":"+folders.toSortedMap()+":"+darkHome+":"+(stockEntry?.component?.flattenToString()?:"")
        if(appSignature==signature){
            if (tiles.map(::tileKey) != order) {
                val byId = tiles.associateBy(::tileKey)
                if (order.all(byId::containsKey)) {
                    tiles.clear()
                    tiles.addAll(order.map(byId::getValue))
                    updateTileLayout()
                    return
                }
            } else {
                requestLayout()
                return
            }
        }
        val focused=focusBeforeSwap?:(findFocus() as? AppTileView)?.let(::tileKey)
        appSignature=signature
        val oldTiles=tiles.associateBy(::tileKey)
        appRow.removeAllViews();tiles.clear()
        fun baseTile(title:String):AppTileView{
            val tile=AppTileView(context)
            tile.title=title;tile.contentDescription=title;tile.id=generateViewId()
            tile.focusHook={focus->if(focus){val key=tileKey(tile);rememberedFocus=key;lastGridFocus=key;controlButton.nextFocusDownId=tile.id;focusedTile=tile;onTileFocused(tile)}else if(focusedTile==tile)focusedTile=null;invalidateTileRegion(tile)}
            tile.focusAnimHook = { invalidateTileShadow(tile) }
            tiles.add(tile);appRow.addView(tile)
            return tile
        }
        order.forEach{id->
            if(id in folders){
                val name=id.removePrefix("folder:")
                val tile=baseTile(name)
                tile.tag=id
                tile.role="module"
                tile.banner=folderArtwork(folders.getValue(id))
                tile.setOnClickListener{if(!placeMovingTile(tile))showFolder(name)}
                tile.setOnLongClickListener{appMenu.openerDownTime=appMenu.heldConfirmDownTime;appMenu.show(tile);true}
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
                tile.setOnLongClickListener{appMenu.openerDownTime=appMenu.heldConfirmDownTime;appMenu.show(tile);true}
                NativeUi.touchClicks(tile);tile.isFocusableInTouchMode=true
                return@forEach
            }
            if(id==stockHomeId){
                val entry=stockEntry
                if(entry!=null){
                    val tile=baseTile("原桌面")
                    tile.role="app";tile.tag=stockHomeId
                    val old=oldTiles[stockHomeId]
                    tile.banner=if(launcherApps.squareCached(entry))try{launcherApps.squareIcon(entry)}catch(_:Exception){null} else old?.banner
                    if(tile.banner==null)tile.icon=old?.icon?:try{launcherApps.icon(entry)}catch(_:Exception){null}
                    tile.placeholderTint=launcherApps.tileTint(entry)
                    tile.setOnClickListener{rememberedFocus=tileKey(tile);if(!placeMovingTile(tile))openStockHome()}
                    tile.setOnLongClickListener{appMenu.openerDownTime=appMenu.heldConfirmDownTime;appMenu.show(tile);true}
                    NativeUi.touchClicks(tile);tile.isFocusableInTouchMode=true
                }
                return@forEach
            }
            val entry=entries[id]?:return@forEach
            val tile=baseTile(launcherApps.displayTitle(id,entry.label))
            tile.role="app";tile.tag=entry
            val old=oldTiles[id]
            tile.banner=if(launcherApps.squareCached(entry))try{launcherApps.squareIcon(entry)}catch(_:Exception){null} else old?.banner
            if(tile.banner==null)tile.icon=old?.icon?:try{launcherApps.icon(entry)}catch(_:Exception){null}
            tile.placeholderTint=launcherApps.tileTint(entry)
            tile.setOnClickListener{rememberedFocus=tileKey(tile);if(!placeMovingTile(tile)&&!launcherApps.launch(entry))NativeUi.notice(context,"应用已移除或无法打开")}
            tile.setOnLongClickListener{appMenu.openerDownTime=appMenu.heldConfirmDownTime;appMenu.show(tile);true}
            NativeUi.touchClicks(tile);tile.isFocusableInTouchMode=true
        }
        updateTileLayout()
        if(initialGridFocusPending&&tiles.isNotEmpty()){
            initialGridFocusPending=false
            rememberedFocus=tileKey(tiles.first())
            tiles.first().requestFocus()
        }else if(focused!=null)(tiles.firstOrNull{tileKey(it)==focused}?:tiles.firstOrNull())?.requestFocus()
    }

    private fun updateTileLayout() {
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
    }

    private fun rowIndex(row:Int,column:Int)=(row*cols+column).coerceAtMost(tiles.lastIndex)

    private fun focusEntry(entry:String){tiles.firstOrNull{tileKey(it)==entry}?.requestFocus()}
    private fun finishMove(){val entry=moving?:return;stopWobble();hideMoveHint();moving=null;refreshApps();focusEntry(entry);invalidate()}
    private fun enterMove(entry:String){appMenu.dismiss();moving=entry;refreshApps();focusEntry(entry);invalidate();startWobble();showMoveHint()}

    private var wobbleAnimator:android.animation.ValueAnimator?=null
    private var wobbleTile:AppTileView?=null
    private fun startWobble(){
        if(wobbleAnimator?.isRunning==true)return
        wobbleAnimator=android.animation.ValueAnimator.ofFloat(0f,(2*Math.PI).toFloat()).apply{
            duration=260L
            repeatCount=android.animation.ValueAnimator.INFINITE
            interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener{anim->
                val key=moving
                if(key==null){stopWobble();return@addUpdateListener}
                val tile=tiles.firstOrNull{tileKey(it)==key}
                if(tile!==wobbleTile){wobbleTile?.rotation=0f;wobbleTile=tile}
                tile?.rotation=(1.3f* kotlin.math.sin((anim.animatedValue as Float).toDouble())).toFloat()
            }
            start()
        }
    }
    private fun stopWobble(){
        wobbleAnimator?.cancel();wobbleAnimator=null
        wobbleTile?.rotation=0f;wobbleTile=null
    }

    private fun showMoveHint(){
        moveHint.animate().cancel()
        moveHint.visibility=View.VISIBLE
        moveHint.alpha=0f
        moveHint.translationY=8f*scale
        moveHint.animate().alpha(1f).translationY(0f).setDuration(150).start()
        requestLayout()
    }
    private fun hideMoveHint(){
        if(moveHint.visibility!=View.VISIBLE)return
        moveHint.animate().cancel()
        moveHint.animate().alpha(0f).setDuration(150).withEndAction{moveHint.visibility=View.GONE}.start()
    }
    private fun moveEntry(entry:String,offset:Int){
        val from=tiles.indexOfFirst{tileKey(it)==entry}
        if(from<0)return
        val target=when(offset){
            -1,1->(from+offset).takeIf{it in tiles.indices&&it/cols==from/cols}
            else->(from/cols+offset/cols).takeIf{it in 0 until rows}?.let{rowIndex(it,from%cols)}
        }?:return
        val all=launcherApps.orderedIds()
        val targetId=folderRepresentative(tileKey(tiles[target]),all)
        if(target==from||!launcherApps.move(folderRepresentative(entry,all),all.indexOf(targetId)-all.indexOf(folderRepresentative(entry,all)),all))return
        refreshApps()
        focusEntry(entry)
        startWobble()
        invalidate()
    }
    private fun placeMovingTile(tile:AppTileView):Boolean{
        val source=moving?:return false
        val all=launcherApps.orderedIds()
        launcherApps.swap(folderRepresentative(source,all),folderRepresentative(tileKey(tile),all))
        finishMove()
        return true
    }
    private fun folderRepresentative(id:String,order:List<String>?=null)=if(id.startsWith("folder:"))
        (order?:launcherApps.orderedIds()).firstOrNull{it in launcherApps.folderMembers(id.removePrefix("folder:"))}?:id else id

    private fun moduleArtwork(id:String):android.graphics.drawable.Drawable?=AppModules.square(context,id)

    private fun folderArtwork(members:List<String>):HomeFolderArtwork{
        val previews=members.take(9).mapNotNull{key->
            val entry=currentEntries[key]?:if(key==stockHomeId)stockHomeEntry else null
            if(entry!=null)runCatching{(if(launcherApps.squareCached(entry))launcherApps.squareIcon(entry) else launcherApps.icon(entry)).let{it to true}}.getOrNull()
            else moduleArtwork(key)?.let{it to true}
        }
        return HomeFolderArtwork(previews)
    }

    private fun applyTileArt(entry:LauncherApps.Entry){
        if(!launcherApps.squareCached(entry))return
        val key=entry.component.flattenToString()
        val art=runCatching{launcherApps.squareIcon(entry)}.getOrNull()?:return
        tiles.forEach{tile->
            val tag=tile.tag
            if(tag==stockHomeId&&stockHomeEntry?.component?.flattenToString()==key){
                tile.banner=art
                tile.invalidate()
            }else if(tag is LauncherApps.Entry&&tileKey(tile)==key){
                tile.banner=art
                tile.invalidate()
            }else if(tag is String&&tag.startsWith("folder:")){
                val members=currentFolders[tag].orEmpty()
                if(key in members){
                    tile.banner=folderArtwork(members)
                    tile.invalidate()
                }
            }
        }
    }

    private fun applyTileTint(entry:LauncherApps.Entry){
        val tint=launcherApps.tileTint(entry)
        if(tint==0)return
        val key=entry.component.flattenToString()
        tiles.forEach{tile->
            if(tile.tag is LauncherApps.Entry&&tileKey(tile)==key&&tile.banner==null){
                tile.placeholderTint=tint
                tile.invalidate()
            }else if(tile.tag==stockHomeId&&stockHomeEntry?.component?.flattenToString()==key&&tile.banner==null){
                tile.placeholderTint=tint
                tile.invalidate()
            }
        }
    }

    private fun openStockHome(){
        val target=DesktopTakeover.originalHome(context)
        if(target==null){NativeUi.notice(context,"未找到原桌面");return}
        val activity=context as? android.app.Activity
        if(activity==null){NativeUi.notice(context,"原桌面无法打开");return}
        if(!DesktopGuardService.openStock(activity,target){
            runCatching{activity.startActivity(Intent(Intent.ACTION_MAIN).setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));true}.getOrDefault(false)
        }){DesktopGuardService.allowStockLauncher(false);NativeUi.notice(context,"原桌面无法打开")}
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
                HomeAppearance.drawBackdrop(context,canvas,width,height,darkHome,backdropPaint){invalidate()}
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
            content.addView(row,android.widget.LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=(64*scale).toInt()})
            items.forEachIndexed{column,(id,title)->
                val tile=AppTileView(context).apply{
                    this.title=title
                    if(id.startsWith("module:"))role="module"
                    contentDescription=title
                    val entry=entries[id]?:if(id==LauncherApps.STOCK_HOME_ID)launcherApps.stockHomeEntry() else null
                    if(entry!=null){
                        banner=if(launcherApps.squareCached(entry))runCatching{launcherApps.squareIcon(entry)}.getOrNull() else null
                        if(banner==null)icon=runCatching{launcherApps.icon(entry)}.getOrNull()
                        placeholderTint=launcherApps.tileTint(entry)
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
                val cell=android.widget.LinearLayout(context).apply{orientation=android.widget.LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false}
                cell.addView(tile,android.widget.LinearLayout.LayoutParams(tileHeight,tileHeight))
                val nameView=android.widget.TextView(context).apply{
                    text=title;setTextColor(GlassUi.ink(context));gravity=android.view.Gravity.CENTER
                    isSingleLine=true;ellipsize=android.text.TextUtils.TruncateAt.END
                    NativeUi.textSize(this,30f)
                }
                cell.addView(nameView,android.widget.LinearLayout.LayoutParams(tileHeight,-2).apply{topMargin=(8*scale).toInt()})
                row.addView(cell,android.widget.LinearLayout.LayoutParams(-2,-2).apply{if(column>0)leftMargin=tileGap})
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
        NativeUi.registerDialog(dialog)
        dialog.window?.setLayout(-1,-1)
        first?.requestFocusFromTouch()
    }
    private fun launchHomeApp(id:String):Boolean{
        modules.firstOrNull{it.id==id}?.let{it.run.run();return true}
        if(id==LauncherApps.STOCK_HOME_ID){openStockHome();return true}
        return launcherApps.installed().firstOrNull{it.component.flattenToString()==id}?.let(launcherApps::launch)==true
    }

    fun consumeMenuConfirm(event:KeyEvent):Boolean=appMenu.consumeMenuConfirm(event)

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
        if(appMenu.handleKey(code))return true
        if(code==KeyEvent.KEYCODE_BACK&&(scrollY>1f||(findFocus() as? AppTileView)?.let{tiles.indexOf(it)>=cols}==true)){
            val focused=findFocus() as? AppTileView
            val column=focused?.let{tiles.indexOf(it)%cols}?:0
            (tiles.getOrNull(column)?:tiles.firstOrNull())?.requestFocus()
            animateScrollTo(0f)
            return true
        }
        if(code==KeyEvent.KEYCODE_MENU){val tile=findFocus() as? AppTileView;if(tile!=null){appMenu.show(tile);return true}}
        return false
    }
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
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);if(visibility!=View.VISIBLE){appMenu.dismiss();moving=null;moveHint.animate().cancel();moveHint.visibility=View.GONE}}
    override fun onDetachedFromWindow(){appMenu.dismiss();stopWobble();cancelSpring();mainHandler.removeCallbacksAndMessages(null);super.onDetachedFromWindow()}

    override fun onMeasure(width:Int,height:Int){
        val w=MeasureSpec.getSize(width);val h=MeasureSpec.getSize(height);setMeasuredDimension(w,h)
        scale=minOf(w/1920f,h/1080f).coerceAtLeast(0.001f);oy=(h-1080*scale)/2
        if(styledScale!=scale||styledWidth!=w){
            styledWidth=w
            cols=HomeAppearance.homeColumns(context)
            appRow.columnCount=cols
            val refSlot=((w-2*(112*scale).toInt()-5*(40*scale).toInt())/6).coerceAtLeast(0)
            tileHeight=(refSlot*9f/16f).toInt()
            tileWidth=tileHeight
            tileGap=if(cols>1)minOf((80*scale).toInt(),((w-cols*tileWidth)/(cols-1)).coerceAtLeast(0)) else (80*scale).toInt()
            gridMargin=((w-(cols*tileWidth+(cols-1)*tileGap))/2).coerceAtLeast(0)
            styledScale=scale
            controlButton.setTextColor(Color.WHITE);controlButton.background=GlassUi.surface(controlButton,GlassUi.CIRCLE_RADIUS)
            plateView.background=GlassUi.homeGlass(plateView,64f)
            tiles.forEachIndexed{i,tile->
                tile.layoutParams=android.widget.GridLayout.LayoutParams().apply{
                    this.width=tileHeight;this.height=tileHeight
                    columnSpec=android.widget.GridLayout.spec(i%cols);rowSpec=android.widget.GridLayout.spec(i/cols)
                    val side=(tileWidth-tileHeight)/2
                    leftMargin=side;rightMargin=if(i%cols==cols-1)side else tileGap+side;bottomMargin=(108*scale).toInt()
                }
            }
        }
        appRail.measure(MeasureSpec.makeMeasureSpec((cols*tileWidth+(cols-1)*tileGap),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((rows*(tileHeight+(108*scale).toInt())),MeasureSpec.EXACTLY))
        plateView.measure(MeasureSpec.makeMeasureSpec(minOf(cols*tileWidth+(cols-1)*tileGap+(80*scale).toInt(),w).coerceAtLeast(0),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((tileHeight+(80*scale).toInt()),MeasureSpec.EXACTLY))
        controlButton.measure(MeasureSpec.makeMeasureSpec((76*scale).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((76*scale).toInt(),MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val railTop = (oy + topRowY * scale).toInt()
        val ax = gridMargin
        appRail.layout(ax, railTop, ax + appRail.measuredWidth, railTop + appRail.measuredHeight)
        val plateLeft = (gridMargin - (40 * scale).toInt()).coerceAtLeast(0)
        val plateTop = (oy + (topRowY - 40) * scale).toInt()
        plateView.layout(plateLeft, plateTop, plateLeft + plateView.measuredWidth, plateTop + plateView.measuredHeight)
        applyScrollTransforms()
        val right = (width - 96 * scale).toInt()
        val cy = (oy + 58 * scale).toInt()
        controlButton.layout(right - controlButton.measuredWidth, cy, right, cy + controlButton.measuredHeight)
        if (moveHint.visibility != View.GONE) {
            if (moveHint.background == null) moveHint.background = GlassUi.homeGlass(moveHint, 22f)
            moveHint.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val pw = moveHint.measuredWidth
            val ph = moveHint.measuredHeight
            val pl = (width - pw) / 2
            val pb = height - (36 * scale).toInt()
            moveHint.layout(pl, pb - ph, pl + pw, pb)
        }
        tiles.forEach { tile ->
            val isMoving = moving == tileKey(tile)
            val expectedDesc = tile.title + (if (isMoving) "，移动中" else "")
            if (tile.contentDescription != expectedDesc) tile.contentDescription = expectedDesc
            if (tile.isSelected != isMoving) tile.isSelected = isMoving
            if (tile.background == null && (tile.tag as? String)?.startsWith("folder:") == true) {
                tile.background = GlassUi.homeGlass(tile, (tile.height / scale * 0.225f).coerceAtLeast(1f))
            }
        }
    }
    private fun tileLabel(tile: AppTileView, focused: Boolean): String {
        val key = "${tile.title}|${tile.width}|$focused|${(28f * scale).toInt()}"
        labelCache[tile]?.let { if (it[0] == key) return it[1] as String }
        paint.typeface = if (focused) bold else regular
        paint.textSize = 28f * scale
        val label = android.text.TextUtils.ellipsize(tile.title, paint, tile.width.toFloat(), android.text.TextUtils.TruncateAt.END).toString()
        labelCache[tile] = arrayOf(key, label)
        return label
    }
    private val selectedTitlePaint=android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var selectedTitleKey=""
    private var selectedTitleLayout:android.text.StaticLayout?=null
    private val selectedTitleBounds=RectF()
    @Suppress("DEPRECATION")
    private fun fullTileLabel(tile:AppTileView):android.text.StaticLayout{
        selectedTitlePaint.textSize=28f*scale;selectedTitlePaint.typeface=bold
        val desired=kotlin.math.ceil(selectedTitlePaint.measureText(tile.title)).toInt().coerceAtLeast(tile.width)
        val labelWidth=minOf(desired,(tile.width*2.4f).toInt(),(width-48*scale).toInt()).coerceAtLeast(1)
        val key="${tile.title}|$labelWidth|$scale"
        if(key!=selectedTitleKey||selectedTitleLayout==null){
            val first=selectedTitlePaint.breakText(tile.title,true,labelWidth.toFloat(),null)
            val text=if(first>=tile.title.length)tile.title else tile.title.take(first)+"\n"+
                android.text.TextUtils.ellipsize(tile.title.substring(first),selectedTitlePaint,labelWidth.toFloat(),android.text.TextUtils.TruncateAt.END)
            selectedTitleLayout=android.text.StaticLayout(text,selectedTitlePaint,labelWidth,android.text.Layout.Alignment.ALIGN_CENTER,1f,0f,false)
            selectedTitleKey=key
        }
        return selectedTitleLayout!!
    }
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val frostRect = RectF()
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPath = Path()
    private var cachedClockKey = ""
    private var cachedTimeText = ""
    private var cachedDateText = ""
    private class BakedShadow(val bitmap: Bitmap, val ext: Int)
    private var bakedShadow: BakedShadow? = null
    private var bakedShadowW = 0
    private var bakedShadowH = 0
    private fun focusShadow(w: Int, h: Int): BakedShadow? {
        if (w <= 0 || h <= 0) return null
        bakedShadow?.takeIf { bakedShadowW == w && bakedShadowH == h }?.let { return it }
        val blur = (26f * scale).coerceAtLeast(1f)
        val ext = kotlin.math.ceil(blur * 1.5f).toInt()
        val bitmap = Bitmap.createBitmap(w + 2 * ext, h + 2 * ext, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bitmap)
        bc.translate(ext.toFloat(), ext.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xb3000000.toInt()
        p.maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        bc.drawPath(GlassUi.SmoothCorners.smoothRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h * 0.225f, h * 0.225f), p)
        return BakedShadow(bitmap, ext).also { bakedShadow?.bitmap?.takeIf{!it.isRecycled}?.recycle(); bakedShadow = it; bakedShadowW = w; bakedShadowH = h }
    }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val railY = railTopY()
        val focused = focusedTile
        selectedTitleBounds.setEmpty()
        if (focused != null && moving == null && scrollTarget > 0f) {
            val focusedIndex = tiles.indexOf(focused)
            val fade = liftFrac * (if (focusedIndex in 0 until cols) 1f else gridAlpha())
            val fadeAlpha = (fade * 255).toInt().coerceIn(0, 255)
            if (fadeAlpha > 2) {
                val label=fullTileLabel(focused)
                selectedTitlePaint.color=ink
                selectedTitlePaint.alpha=fadeAlpha
                selectedTitlePaint.setShadowLayer(9f*scale,0f,3f*scale,if(darkHome)Color.argb((0x99*fade).toInt(),0,0,0) else Color.argb((0x70*fade).toInt(),255,255,255))
                val cx = appRail.left + focused.left + focused.width / 2f
                val bottom = railY + focused.top + focused.height
                val left=(cx-label.width/2f).coerceIn(24f*scale,(width-label.width-24f*scale).coerceAtLeast(24f*scale))
                val top=bottom+12f*scale
                selectedTitleBounds.set(left,top,left+label.width,top+label.height)
                val save=canvas.save();canvas.translate(left,top);label.draw(canvas);canvas.restoreToCount(save)
            }
        }

        val lift = liftFrac
        if (lift > 0.01f && moving == null && scrollTarget > 0f) {
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = regular
            paint.textSize = 28f * scale
            paint.color = ink
            paint.setShadowLayer(
                9f * scale, 0f, 3f * scale,
                if (darkHome) Color.argb((0x65 * lift).toInt(), 0, 0, 0) else Color.argb((0x50 * lift).toInt(), 255, 255, 255)
            )
            val gAlpha = gridAlpha()
            val captionOffset = 36f * scale
            val viewH = height.toFloat()
            tiles.forEachIndexed { i, tile ->
                if (tile === focused) return@forEachIndexed
                val bottom = railY + tile.top + tile.height
                val textY = bottom + captionOffset
                if (textY < -40f * scale || textY - 40f * scale > viewH) return@forEachIndexed
                val a = (lift * (if (i < cols) 1f else gAlpha) * 255).toInt().coerceIn(0, 255)
                if (a <= 2) return@forEachIndexed
                paint.alpha = a
                val cx = appRail.left + tile.left + tile.width / 2f
                if(!selectedTitleBounds.isEmpty&&textY>=selectedTitleBounds.top&&textY-36f*scale<=selectedTitleBounds.bottom&&
                    cx+tile.width/2f>selectedTitleBounds.left&&cx-tile.width/2f<selectedTitleBounds.right)return@forEachIndexed
                canvas.drawText(tileLabel(tile, false), cx, textY, paint)
            }
            paint.textAlign = Paint.Align.LEFT
            paint.clearShadowLayer()
            paint.alpha = 255
        }

        val entry = moving ?: return
        val tile = tiles.firstOrNull { tileKey(it) == entry } ?: return
        val x = appRail.left + tile.left.toFloat()
        val y = railY + tile.top + tile.height * .5f
        val index = tiles.indexOf(tile)
        arrowPaint.color = ink
        arrowPaint.strokeWidth = 2.5f * scale
        fun arrow(center: Float, left: Boolean, enabled: Boolean) {
            arrowPaint.alpha = if (enabled) 255 else 65
            val sign = if (left) -1 else 1
            arrowPath.reset()
            arrowPath.moveTo(center - sign * 3 * scale, y - 6 * scale)
            arrowPath.lineTo(center + sign * 3 * scale, y)
            arrowPath.lineTo(center - sign * 3 * scale, y + 6 * scale)
            canvas.drawPath(arrowPath, arrowPaint)
        }
        arrow(x + 13 * scale, true, index % cols > 0)
        arrow(x + tile.width - 13 * scale, false, index % cols < cols - 1 && index < tiles.lastIndex)
        val cx = x + tile.width / 2f
        fun vArrow(centerY: Float, up: Boolean, enabled: Boolean) {
            arrowPaint.alpha = if (enabled) 255 else 65
            val sign = if (up) -1 else 1
            arrowPath.reset()
            arrowPath.moveTo(cx - 6 * scale, centerY - sign * 3 * scale)
            arrowPath.lineTo(cx, centerY + sign * 3 * scale)
            arrowPath.lineTo(cx + 6 * scale, centerY - sign * 3 * scale)
            canvas.drawPath(arrowPath, arrowPaint)
        }
        vArrow(railY + tile.top + 13 * scale, true, index >= cols)
        vArrow(railY + tile.top + tile.height - 13 * scale, false, index / cols + 1 < rows)
    }
    fun update(base:String,pin:String,invite:String,count:Int,message:String){
        val minute=System.currentTimeMillis()/60000
        val minuteChanged=lastClockMinute!=minute
        lastClockMinute=minute
        val connectionChanged=connection.update(base,pin,invite,count,message)
        if(connectionChanged||minuteChanged)invalidate()
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
    fun resetToInitial(){
        animateScrollTo(0f)
        tiles.firstOrNull()?.requestFocus()
    }
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,color:Int=ink,strong:Boolean=false){paint.shader=null;paint.color=color;paint.textSize=size;paint.typeface=if(strong)bold else regular;c.drawText(value,x,y,paint)}
    private fun ensureClockStrings() {
        val nowMs = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        val key = "${nowMs / 60000}:${zone.id}"
        if (cachedClockKey == key) return
        cachedClockKey = key
        timeFormat.timeZone = zone
        dateFormat.timeZone = zone
        val date = java.util.Date(nowMs)
        cachedTimeText = timeFormat.format(date)
        cachedDateText = dateFormat.format(date)
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        paint.shader = null
        paint.isFilterBitmap = true
        if (drawsBackdrop) HomeAppearance.drawBackdrop(context, c, width, height, darkHome, paint){invalidate()}
        val lift = liftFrac
        if (lift > 0.01f) {
            GlassUi.snapshot(context)?.let { frost ->
                frostRect.set(0f, 0f, width.toFloat(), height.toFloat())
                paint.alpha = (lift * 255).toInt().coerceIn(0, 255)
                c.drawBitmap(frost.bitmap, null, frostRect, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                paint.alpha = (lift * 96).toInt().coerceIn(0, 96)
                c.drawRect(frostRect, paint)
                paint.alpha = 255
            }
        }
        val ft = focusedTile
        if (ft != null && moving == null && lift > 0.01f) {
            val frac = ft.focusFraction
            if (frac > 0.01f) {
                focusShadow(ft.width, ft.height)?.let { shadow ->
                    val railY = railTopY()
                    val cx = appRail.left + ft.left + ft.width / 2f
                    val cy = railY + ft.top + ft.height / 2f
                    val grow = 1f + 0.05f * frac
                    shadowPaint.alpha = (frac * lift * 255).toInt().coerceIn(0, 255)
                    c.save()
                    c.translate(cx, cy + (24f * scale) * frac)
                    c.scale(grow, grow)
                    c.translate(-cx, -cy)
                    c.drawBitmap(shadow.bitmap, cx - ft.width / 2f - shadow.ext, cy - ft.height / 2f - shadow.ext, shadowPaint)
                    c.restore()
                    shadowPaint.alpha = 255
                }
            }
        }
        val fade = shelfAlpha()
        if (fade > 0f) {
            ensureClockStrings()
            c.save()
            c.translate(0f, oy)
            c.scale(scale, scale)
            fun faded(color: Int) = Color.argb((Color.alpha(color) * fade).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            paint.style = Paint.Style.FILL
            val foreground = if (darkHome) Color.WHITE else 0xff162638.toInt()
            paint.setShadowLayer(9f, 0f, 3f, faded(if (darkHome) 0x65000000 else 0x50ffffff))
            text(c, cachedDateText, 120f, 364f, 43f, color = faded(foreground), strong = true)
            paint.shader = null
            paint.typeface = clockFont
            paint.textSize = 168f
            paint.color = faded(foreground)
            c.drawText(cachedTimeText, 120f, 297f, paint)
            paint.clearShadowLayer()
            c.restore()
        }
    }
    fun qrBitmap()=connection.qrBitmap()
    fun connectionLineText()=connection.lineText()
    fun connectionCount()=connection.count()
}
