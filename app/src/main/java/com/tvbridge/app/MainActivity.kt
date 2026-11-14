package com.tvbridge.app

import android.app.*
import android.media.AudioManager
import android.os.*
import android.content.*
import android.graphics.Color
import android.graphics.drawable.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.net.*
import java.io.File

class MainActivity:Activity() {
    @JvmField var visible=false
    private lateinit var root:FrameLayout
    private lateinit var home:TvHomeView
    private var livePanel:LivePanel?=null
    private lateinit var fileExporter:FileExporter
    private lateinit var apkInstaller:ApkInstaller
    private val updates by lazy{UpdateController(this,apkInstaller)}
    private var appsPanel:View?=null
    private var controlQr:ImageView?=null
    private var controlQrLabel:TextView?=null
    private var controlQrDot:View?=null
    private var applications:LauncherApps?=null
    private var appsScanGeneration=0
    private var appsEditing=false
    private var catalogReceiverRegistered=false
    private val catalogReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){LauncherApps.invalidate(context);if(!intent.getBooleanExtra(Intent.EXTRA_REPLACING,false))LiveLaunch.component(context);if(::home.isInitialized){home.refreshApps();if(screen=="apps")showApps(appsEditing)}}}
    private val appearanceUi by lazy{AppearanceController(this,{keep->appearanceUpdated(keep)})}
    private var pointerPermissionPending=false
    private var pointerConnecting=false
    private val startupPrefs by lazy{getSharedPreferences("startup",MODE_PRIVATE)}
    private var screen="home"
    private val handler=Handler(Looper.getMainLooper())
    private var networkAddress=""
    private val ticker=object:Runnable{override fun run(){if(visible){if(screen=="home")updateHome();handler.postDelayed(this,2000)}}}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if(HomeAppearance.dark(this))0xff162233.toInt()else 0xffe9eff4.toInt()))
        if(intent.getBooleanExtra("from_boot",false))BootReceiver.markArrived(this)
        apkInstaller=ApkInstaller(this);apkInstaller.restore(state);fileExporter=FileExporter(this)
        window.decorView.systemUiVisibility=5894 or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        Hub.activity=this;Hub.start(this)
        run{
            try{androidx.core.content.ContextCompat.startForegroundService(this,Intent(this,RemoteHostService::class.java))}catch(_:RuntimeException){}
            if(Hub.adb.enabled())Thread({Hub.adb.connect(Hub.adb.port())},"restore-remote").start()
        }
        root=FrameLayout(this);sceneBackground(false);setContentView(root);UiSounds.attach(this)
        val packages=IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addAction(Intent.ACTION_PACKAGE_REPLACED);addDataScheme("package")}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(catalogReceiver,packages,Context.RECEIVER_NOT_EXPORTED)else registerReceiver(catalogReceiver,packages);catalogReceiverRegistered=true
        home=TvHomeView(this,{openTelevision()},{showFiles()},{showAppSettings()},{Hub.server?.refreshInvite();updateHome()},{showApps()},{showApps(true)},{startActivity(Intent(this,BrowserActivity::class.java))}).apply{drawsBackdrop=false}
        root.addView(home,FrameLayout.LayoutParams(-1,-1));updateHome();home.focusFirst()
        Thread({runCatching{LauncherApps(this).installed()}},"apps-prewarm").start()
        HomeAppearance.prewarm(this)
        if(intent.getBooleanExtra("pointer_setup",false))root.post{requestGlobalPointer()}
        if(intent.getBooleanExtra("open_live",false))root.post{openSelectedLive()}
        else if(state==null&&!intent.getBooleanExtra("show_home",false))scheduleLiveStartup()
    }
    private var startupDialog:AlertDialog?=null
    private fun scheduleLiveStartup(){
        startupDialog?.dismiss()
        if(screen=="live"||!startupPrefs.getBoolean("live",false))return
        var seconds=3
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val notice=TextView(this).apply{gravity=Gravity.CENTER_VERTICAL;isSingleLine=true;NativeUi.textSize(this,30f);setTextColor(GlassUi.ink(this@MainActivity))}
        row.addView(notice,LinearLayout.LayoutParams(0,dp(32),1f))
        var countdown:AlertDialog?=null
        val cancel=Button(this).apply{text="取消";isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,0,0,0);NativeUi.textSize(this,28f);setTextColor(GlassUi.colors(this@MainActivity));if(Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null;elevation=0f};background=GlassUi.surface(this);setOnClickListener{countdown?.dismiss()};NativeUi.touchClicks(this)}
        row.addView(cancel,LinearLayout.LayoutParams(dp(58),dp(32)).apply{leftMargin=dp(12)})
        fun update(){notice.text="${seconds} 秒后进入直播"}
        update()
        val dialog=NativeUi.show(AlertDialog.Builder(this).setView(row),compact=true)
        countdown=dialog;cancel.requestFocus()
        startupDialog=dialog
        val tick=object:Runnable{override fun run(){
            if(!dialog.isShowing||isFinishing||isDestroyed)return
            seconds--
            if(seconds<=0){
                dialog.dismiss()
                if(intent.getBooleanExtra("from_boot",false))DesktopRecovery.noteBootTargetOpened(this@MainActivity)
                if(intent.getBooleanExtra("reopen_boot",false))nudgeVolumeAfterReopen()
                openTelevision()
            }else{update();handler.postDelayed(this,1000)}
        }}
        dialog.setOnDismissListener{NativeUi.handleExternalDismiss(dialog);handler.removeCallbacks(tick);if(startupDialog===dialog)startupDialog=null}
        handler.postDelayed(tick,1000)
    }
    private fun nudgeVolumeAfterReopen(){
        handler.postDelayed({
            val am=getSystemService(Context.AUDIO_SERVICE) as? AudioManager?:return@postDelayed
            runCatching{
                if(Build.VERSION.SDK_INT>=23&&am.isStreamMute(AudioManager.STREAM_MUSIC))return@runCatching
                val level=am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val other=if(level>0)level-1 else 1
                if(other>am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))return@runCatching
                am.setStreamVolume(AudioManager.STREAM_MUSIC,other,0)
                am.setStreamVolume(AudioManager.STREAM_MUSIC,level,0)
            }
        },4000)
    }
    private fun dp(value:Int)=(value*2*NativeUi.scale(this)).toInt()
    private fun button(parent:LinearLayout,label:String,action:()->Unit):Button {
        val b=Button(this);if(android.os.Build.VERSION.SDK_INT>=21)b.backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)b.stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=26)b.defaultFocusHighlightEnabled=false;b.text=label;b.isAllCaps=false;NativeUi.textSize(b,30f);AppIcons.bind(b,size=30f);b.minWidth=0;b.minimumWidth=0;b.setPadding(dp(16),0,dp(16),0)
        b.id=View.generateViewId();b.setTextColor(GlassUi.colors(this));b.background=GlassUi.surface(b,faint=true);b.setOnClickListener{action()};NativeUi.touchClicks(b)
        parent.addView(b,LinearLayout.LayoutParams(-2,dp(40)).apply{marginEnd=dp(8)});return b
    }
    private fun isDefaultHome():Boolean=packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName==packageName
    private fun openHomeSettings(){
        for(action in listOf(Settings.ACTION_HOME_SETTINGS,Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,Settings.ACTION_SETTINGS)){
            try{startActivity(Intent(action));return}catch(_:ActivityNotFoundException){}catch(_:SecurityException){}
        }
        NativeUi.notice(this,"系统未提供默认桌面设置")
    }
    private fun chooseDefaultHome(){
        try{
            if(Build.VERSION.SDK_INT>=29){val roles=getSystemService(android.app.role.RoleManager::class.java)
                if(roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)&&!roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)){
                    startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME),20);return
                }
            }
        }catch(_:RuntimeException){}
        val homeChoice=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolver=packageManager.resolveActivity(homeChoice,0)?.activityInfo
        if(resolver?.packageName=="android"&&resolver.name.endsWith("ResolverActivity")){
            try{startActivityForResult(homeChoice,20);return}catch(_:RuntimeException){}
        }
        openHomeSettings()
    }
    private var desktopButton:Button?=null
    private var lastHomeBackMs=0L
    private val homeBackExitDelay=Runnable{showExitDialog()}
    private fun desktopLabel()=if(isDefaultHome()||DesktopTakeover.wanted(this)&&DesktopGuardService.connected)"已设置"else "未设置"
    internal fun desktopConnectionChanged(){runOnUiThread{if(!isDestroyed)desktopButton?.let{it.setCompoundDrawablesRelative(null,null,NativeUi.trailing(it,desktopLabel(),ink=true),null);it.contentDescription="桌面应用，"+desktopLabel()}}}
    private val liveSettings by lazy{LiveSettings(this)}
    internal fun openSelectedLive(){showHome();openTelevision()}
    private fun openTelevision(){
        startupDialog?.dismiss()
        if(LiveLaunch.open(this))return
        showLive()
    }
    private fun showAppSettings(keepDialog:Boolean=false){
        showHome(keepDialog);screen="settings";home.visibility=View.GONE
        val page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(48),dp(28),dp(48),dp(28))}
        appsPanel=page;root.addView(page,FrameLayout.LayoutParams(-1,-1))
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        page.addView(header,LinearLayout.LayoutParams(-1,dp(48)))
        button(header,"返回"){showHome()}
        header.addView(TextView(this).apply{text="设置";NativeUi.textSize(this,40f);setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(GlassUi.ink(this@MainActivity));setPadding(dp(18),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
        val shortcuts=SystemControls.quickActions(this)
        val shortcutWidth=(0 until shortcuts.childCount).sumOf{(shortcuts.getChildAt(it).layoutParams as LinearLayout.LayoutParams).let{p->p.width+p.leftMargin+p.rightMargin}}
        header.addView(shortcuts,LinearLayout.LayoutParams(shortcutWidth,dp(40)))
        val columns=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        page.addView(columns,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(24)})
        val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false;isFillViewport=true}
        columns.addView(scroll,LinearLayout.LayoutParams(0,-1,1f))
        val detail=FrameLayout(this).apply{tag=NativeUi.CONTROL_DETAIL}
        columns.addView(detail,LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=dp(20)})
        val qrBox=connectionCard()
        detail.addView(qrBox,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val refreshQr=qrBox.findViewWithTag<Button>("refresh-connection")
        NativeUi.registerQrBox(qrBox)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(20),dp(22),dp(20));background=GlassUi.surface(this,GlassUi.PANEL_RADIUS)}
        scroll.addView(body,FrameLayout.LayoutParams(-1,-1))
        fun action(parent:LinearLayout,label:String,detail:String="",run:()->Unit):Button=button(parent,label,run).apply{
            setOnClickListener{NativeUi.selectControl(this);run()}
            layoutParams=LinearLayout.LayoutParams(-1,dp(46)).apply{bottomMargin=dp(6)}
            gravity=Gravity.START or Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(16),0);NativeUi.textSize(this,30f)
            setCompoundDrawablesRelative(null,null,NativeUi.trailing(this,detail,ink=true),null)
        }
        fun toggle(parent:LinearLayout,label:String,checked:Boolean,change:(Boolean)->Unit){
            val v=SettingsSwitch(this).apply{text=label;isChecked=checked;NativeUi.textSize(this,30f);setTextColor(GlassUi.colors(this@MainActivity));setPadding(dp(16),0,dp(16),0);background=GlassUi.surface(this,20f,faint=true);if(Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null};if(Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false;setOnCheckedChangeListener{_,on->change(on)};NativeUi.touchClicks(this)}
            parent.addView(v,LinearLayout.LayoutParams(-1,dp(46)).apply{bottomMargin=dp(6)})
        }
        val desktop=action(body,"桌面应用",desktopLabel()){DesktopTakeover.settings(this,{chooseDefaultHome()},{if(screen=="settings")showAppSettings()})}
        action(body,"桌面背景",HomeAppearance.label(this)){appearanceUi.wallpaper()}
        action(body,"直播管理"){liveSettings.show()}
        toggle(body,"打开时进入直播",startupPrefs.getBoolean("live",false)){startupPrefs.edit().putBoolean("live",it).apply()}
        toggle(body,"按键音",UiSounds.enabled(this)){UiSounds.setEnabled(this,it)}
        action(body,"检查更新"){updates.check()}
        val settingsRows=(0 until body.childCount).map{body.getChildAt(it)}
        refreshQr.id=View.generateViewId()
        settingsRows.forEach{if(it.id==View.NO_ID)it.id=View.generateViewId()}
        settingsRows.forEachIndexed{index,row->
            row.nextFocusRightId=refreshQr.id
            row.nextFocusUpId=settingsRows.getOrNull(index-1)?.id?:header.getChildAt(0).id
            row.nextFocusDownId=settingsRows.getOrNull(index+1)?.id?:row.id
            row.setOnFocusChangeListener{_,focused->if(focused)refreshQr.nextFocusLeftId=row.id}
        }
        for(index in 0 until shortcuts.childCount){
            val shortcut=shortcuts.getChildAt(index)
            if(shortcut.id==View.NO_ID)shortcut.id=View.generateViewId()
            shortcut.nextFocusDownId=refreshQr.id
        }
        refreshQr.nextFocusUpId=shortcuts.getChildAt(0).id
        refreshQr.nextFocusDownId=refreshQr.id;refreshQr.nextFocusRightId=refreshQr.id
        header.getChildAt(0).nextFocusDownId=desktop.id
        desktopButton=desktop;desktopConnectionChanged();desktop.requestFocus()
    }
    private fun showApps(addMode:Boolean=false){
        if(screen!="home")showHome()
        screen="apps";home.visibility=View.GONE
        val repository=applications?:LauncherApps(this).also{applications=it}
        var entries=listOf<LauncherApps.Entry>()
        val generation=++appsScanGeneration
        val editing=addMode
        appsEditing=editing
        val page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(56),dp(24),dp(56),dp(24));background=GlassUi.backdrop(this)};appsPanel=page;root.addView(page,FrameLayout.LayoutParams(-1,-1))
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};page.addView(header,LinearLayout.LayoutParams(-1,dp(44)))
        button(header,"返回"){showHome()}
        header.addView(TextView(this).apply{text=if(addMode)"添加常用应用"else "全部应用";NativeUi.textSize(this,40f);setTextColor(GlassUi.ink(this@MainActivity))},LinearLayout.LayoutParams(0,-2,1f))
        if(addMode)button(header,"完成"){showHome()}
        val scroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false};page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(14)})
        val grid=GridLayout(this).apply{columnCount=5};scroll.addView(grid,FrameLayout.LayoutParams(-1,-2))
        fun render(){
            grid.removeAllViews();val saved=repository.saved()
            entries.forEachIndexed{i,entry->
                val selected=entry.component.flattenToString() in saved
                val b=object:Button(this@MainActivity){
                    private val mark=AppIcons.Vector("star"){currentTextColor}
                    override fun onDraw(canvas:android.graphics.Canvas){super.onDraw(canvas);if(editing&&isSelected){val size=dp(11);mark.setBounds(width-dp(9)-size,dp(9),width-dp(9),dp(9)+size);mark.draw(canvas)}}
                }.apply{text=entry.label;contentDescription=entry.label+(if(editing)if(selected)"，已添加到首页"else "，添加到首页"else "");isAllCaps=false;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;NativeUi.textSize(this,30f);setPadding(dp(12),dp(12),dp(12),dp(12));if(android.os.Build.VERSION.SDK_INT>=21)backgroundTintList=null;if(android.os.Build.VERSION.SDK_INT>=21)stateListAnimator=null;if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setTextColor(GlassUi.colors(this@MainActivity));background=GlassUi.surface(this,faint=true);isSelected=editing&&selected;isFocusableInTouchMode=true}
                try{val icon=repository.icon(entry);icon.setBounds(0,0,dp(34),dp(34));b.setCompoundDrawables(null,icon,null,null);b.compoundDrawablePadding=dp(8)}catch(_:Exception){}
                b.tag=entry
                if(!editing)b.setOnLongClickListener{showInstalledAppMenu(entry);true}
                b.setOnClickListener{if(editing){if(!repository.toggle(entry))NativeUi.notice(this,"常用应用最多添加 10 个");render();grid.getChildAt(i)?.requestFocus()}else if(!repository.launch(entry))NativeUi.notice(this,"无法打开应用")}
                NativeUi.touchClicks(b);grid.addView(b,GridLayout.LayoutParams().apply{width=if(Build.VERSION.SDK_INT>=21)0 else (resources.displayMetrics.widthPixels-dp(152))/5;height=dp(100);columnSpec=if(Build.VERSION.SDK_INT>=21)GridLayout.spec(i%5,1f)else GridLayout.spec(i%5);rowSpec=GridLayout.spec(i/5);setMargins(dp(4),dp(4),dp(4),dp(4))})
            }
            if(entries.isEmpty())grid.addView(TextView(this).apply{text="暂无可打开的应用";NativeUi.textSize(this,28f)})
        }
        Thread({val found=runCatching{repository.installed()}.getOrDefault(emptyList());runOnUiThread{if(generation!=appsScanGeneration||isFinishing||isDestroyed||screen!="apps")return@runOnUiThread;entries=found;render();grid.getChildAt(0)?.requestFocus()}},"apps-scan").start()
    }
    private fun showInstalledAppMenu(entry:LauncherApps.Entry){
        val flags=try{packageManager.getApplicationInfo(entry.component.packageName,0).flags}catch(_:Exception){android.content.pm.ApplicationInfo.FLAG_SYSTEM}
        val choices=mutableListOf("打开")
        if(entry.component.flattenToString() in applications?.saved().orEmpty())choices.add("移出常用")
        if(flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM==0)choices.add("卸载应用")
        else if(flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP!=0)choices.add("卸载更新")
        choices.add("应用信息")
        NativeUi.show(AlertDialog.Builder(this).setTitle(entry.label).setItems(choices.toTypedArray()){_,which->
            when(choices[which]){
                "打开"->applications?.launch(entry)
                "移出常用"->{applications?.remove(entry);home.refreshApps();NativeUi.notice(this,"已移出常用")}
                "卸载应用","卸载更新"->try{startActivityForResult(Intent(Intent.ACTION_DELETE,android.net.Uri.parse("package:${entry.component.packageName}")),52)}catch(_:RuntimeException){NativeUi.notice(this,"系统未提供卸载入口")}
                else->try{startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${entry.component.packageName}")))}catch(_:RuntimeException){NativeUi.notice(this,"无法打开应用信息")}
            }
        })
    }
    private fun showFocusedAppMenu():Boolean {
        if(screen!="apps"||appsEditing)return false
        var view=appsPanel?.findFocus()
        while(view!=null){
            (view.tag as? LauncherApps.Entry)?.let{showInstalledAppMenu(it);return true}
            view=view.parent as? View
        }
        return false
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("from_boot",false))BootReceiver.markArrived(this);if(intent.getBooleanExtra("pointer_setup",false)){requestGlobalPointer();return};if(::home.isInitialized){if(intent.getBooleanExtra("open_live",false))openSelectedLive()else if(intent.getBooleanExtra("show_home",false)||intent.hasCategory(Intent.CATEGORY_HOME))showHome()else scheduleLiveStartup()}}
    private var cachedAddresses=emptyList<String>();private var cachedAddressesMs=0L
    private fun addresses():List<String> {
        val now=SystemClock.elapsedRealtime()
        val currentPort=Hub.server?.port()?.takeIf{it>0}?:8888
        if(now-cachedAddressesMs<30000&&cachedAddresses.isNotEmpty()&&cachedAddresses.all{it.endsWith(":$currentPort")})return cachedAddresses
        val port=Hub.server?.port()?.takeIf{it>0}?:8888
        val found=try{
            val preferred=if(Build.VERSION.SDK_INT>=23){
                val manager=getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                manager.activeNetwork?.let(manager::getLinkProperties)?.interfaceName
            }else null
            NetworkInterface.getNetworkInterfaces().toList()
                .filter{it.isUp&&!it.isLoopback&&!it.isPointToPoint}
                .sortedBy{if(it.name==preferred)0 else if(it.name.startsWith("eth")||it.name.startsWith("wlan"))1 else 2}
                .flatMap{it.inetAddresses.toList()}.filterIsInstance<Inet4Address>()
                .filter{it.isSiteLocalAddress}.map{"http://${it.hostAddress}:$port"}
        }catch(_:Exception){emptyList()}
        if(found.isNotEmpty()){cachedAddresses=found;cachedAddressesMs=now}
        return found
    }
    private var lastControlQr:android.graphics.Bitmap?=null
    private var lastControlSummary=""
    private var lastControlConnected:Boolean?=null
    private fun connectionCard(title:String="手机连接"):LinearLayout {
        val qrBox=LinearLayout(this).apply{tag=NativeUi.QR_BOX;orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(16),dp(16),dp(16),dp(16));background=GlassUi.surface(this,28f)}
        val qrTitleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL}
        val qrDot=View(this).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(home.connectionCount()>0)0xff4caf50.toInt() else Color.WHITE)}}
        qrTitleRow.addView(qrDot,LinearLayout.LayoutParams(dp(6),dp(6)))
        qrTitleRow.addView(TextView(this).apply{text=title;NativeUi.textSize(this,30f);setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(GlassUi.ink(this@MainActivity))},LinearLayout.LayoutParams(-2,-2).apply{leftMargin=dp(5)})
        qrBox.addView(qrTitleRow,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)});controlQrDot=qrDot
        val qrSize=minOf(dp(160), (resources.displayMetrics.heightPixels*.36f).toInt()).coerceAtLeast(1)
        val qrImage=ImageView(this).apply{setImageBitmap(home.qrBitmap());scaleType=ImageView.ScaleType.FIT_CENTER;background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(8).toFloat()}}
        qrBox.addView(qrImage,LinearLayout.LayoutParams(qrSize,qrSize));controlQr=qrImage
        val qrLabel=TextView(this).apply{text=home.connectionLineText();gravity=Gravity.CENTER;NativeUi.textSize(this,28f);setTextColor(GlassUi.ink(this@MainActivity));setPadding(0,dp(12),0,dp(12));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        qrBox.addView(qrLabel);controlQrLabel=qrLabel
        val refreshQr=Button(this).apply{tag="refresh-connection";text="刷新二维码";isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;if(android.os.Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null;elevation=0f};background=GlassUi.surface(this,faint=true);NativeUi.textSize(this,28f);setTextColor(GlassUi.colors(this@MainActivity));setPadding(dp(12),0,dp(12),0);setOnClickListener{cachedAddresses=emptyList();networkAddress="";if(Hub.server==null)Hub.start(this@MainActivity);Hub.server?.refreshInvite();updateHome()};NativeUi.touchClicks(this)}
        qrBox.addView(refreshQr,LinearLayout.LayoutParams(-2,dp(32)))
        return qrBox
    }
    private fun updateHome(){
        val addresses=addresses();if(networkAddress !in addresses)networkAddress=addresses.firstOrNull().orEmpty()
        val server=Hub.server
        val payload=if(server!=null&&networkAddress.isNotEmpty())"$networkAddress/#connect=${server.scanSecret()}" else ""
        home.update(networkAddress,server?.pin().orEmpty(),payload,server?.sessionCount()?:0,if(server==null)"服务未启动：${Hub.error}" else server.lastAction())
        val qr=home.qrBitmap();val summary=home.connectionLineText()
        if(qr!==lastControlQr){controlQr?.setImageBitmap(qr);lastControlQr=qr}
        if(summary!=lastControlSummary){controlQrLabel?.text=summary;lastControlSummary=summary}
        val connected=home.connectionCount()>0
        if(lastControlConnected!=connected){controlQrDot?.background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(connected)0xff4caf50.toInt() else Color.WHITE)};lastControlConnected=connected}
    }
    private fun sceneBackground(live:Boolean){
        val color=if(live)0xff121820.toInt()else if(HomeAppearance.dark(this))0xff162233.toInt()else 0xffe9eff4.toInt()
        window.setBackgroundDrawable(ColorDrawable(color))
        if(::root.isInitialized)root.background=if(live)ColorDrawable(color)else GlassUi.backdrop(root)
    }
    fun showHome(keepDialog:Boolean=false){sceneBackground(false);appsPanel?.let{root.removeView(it)};appsPanel=null;controlQr=null;controlQrLabel=null;controlQrDot=null;lastControlQr=null;lastControlSummary="";if(!keepDialog)NativeUi.dialogCommand("back");screen="home";livePanel?.stop();livePanel?.let{root.removeView(it)};livePanel=null;home.visibility=View.VISIBLE;home.refreshAppearance();home.refreshApps();home.focusFirst();window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateHome();if(HomeAppearance.mode(this)=="bing")HomeAppearance.refreshBingIfStale(this){if(!isDestroyed){home.refreshAppearance();home.invalidate()}}}
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun showLive(autoPlay:Boolean=true){
        startupDialog?.dismiss()
        Hub.live.refreshPlugin()
        val previous=if(autoPlay)Hub.live.resumeChannel()else null
        if(autoPlay&&previous==null){showLiveSetup();return}
        if(screen=="live")return
        if(screen!="home"){showHome()}
        sceneBackground(true)
        home.visibility=View.GONE
        screen="live"
        livePanel=LivePanel(this,Hub.live,{showHome()},noSources={showLiveSetup()},pageFactory={c,tap,state->YangshipinPlayer(c,tap,state)});root.addView(livePanel,FrameLayout.LayoutParams(-1,-1));window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(autoPlay&&previous!=null)livePanel?.play(previous,false)
    }
    private fun showLiveSetup(){
        showHome();home.visibility=View.GONE;screen="liveSetup"
        val setup=FrameLayout(this).apply{background=GlassUi.backdrop(this)}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        button(header,"返回"){showHome()}
        setup.addView(header,FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP or Gravity.START).apply{leftMargin=dp(48);topMargin=dp(28)})
        val card=connectionCard("手机连接，导入直播源")
        setup.addView(card,FrameLayout.LayoutParams(dp(360),-2,Gravity.CENTER))
        appsPanel=setup;root.addView(setup,FrameLayout.LayoutParams(-1,-1))
        card.findViewWithTag<View>("refresh-connection")?.requestFocus()
    }
    fun liveUpdated(){if(screen=="liveSetup"&&Hub.live.resumeChannel()!=null)showLive()else livePanel?.refresh()}
    fun appearanceUpdated(keepDialog:Boolean=false){if(!keepDialog)NativeUi.dialogCommand("back");home.refreshAppearance();if(screen=="control"||screen=="settings")showAppSettings(keepDialog)else root.invalidate();HomeAppearance.prewarm(this)}
    fun playChannel(id:String):Boolean{val channel=Hub.live.findChannel(id)?:return false;showLive(false);livePanel?.play(channel,true);return true}
    fun command(cmd:String,value:String):Boolean {
        BrowserActivity.active?.let{if(it.remoteCommand(cmd,value))return true}
        if(cmd=="inputText")return NativeUi.inputDialogText(value)?:RemoteText.insert(currentFocus,value)
        NativeUi.dialogCommand(cmd)?.let{return it}
        if(cmd=="filesOpen"){showFiles();return true}
        if(cmd=="remoteSettings"){systemRemote();return true}
        if(cmd=="liveOpen"){openTelevision();return true};if(cmd=="livePlay")return playChannel(value)
        if(screen=="live"&&livePanel?.command(cmd,value)==true)return true
        if(cmd=="menu"&&showFocusedAppMenu())return true
        if(cmd=="menu"&&screen=="home"){if(!home.handleKey(KeyEvent.KEYCODE_MENU))showAppSettings();return true}
        if(cmd=="back"){
            if(screen=="home")home.handleKey(KeyEvent.KEYCODE_BACK) else onBackPressed()
            return true
        }
        val code=when(cmd){"up"->KeyEvent.KEYCODE_DPAD_UP;"down"->KeyEvent.KEYCODE_DPAD_DOWN;"left"->KeyEvent.KEYCODE_DPAD_LEFT;"right"->KeyEvent.KEYCODE_DPAD_RIGHT;"ok"->KeyEvent.KEYCODE_DPAD_CENTER;else->return false}
        if(screen=="home")return if(home.handleKey(code))true else NativeUi.move(root,cmd)
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code));return true
    }
    private fun requestGlobalPointer(){
        GlobalPointer.requested=true
        if(pointerConnecting)return
        if(!GlobalPointer.permitted(this)){
            pointerPermissionPending=true
            try{startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:$packageName")),41)}
            catch(_:RuntimeException){pointerPermissionPending=false;NativeUi.notice(this,"请在系统设置允许 One 显示悬浮窗")}
            return
        }
        pointerConnecting=true
        Thread({val ok=Hub.adb.ready||Hub.adb.connect(Hub.adb.port());runOnUiThread{
            pointerConnecting=false
            if(isFinishing||isDestroyed||!GlobalPointer.requested)return@runOnUiThread
            if(ok)try{androidx.core.content.ContextCompat.startForegroundService(this,Intent(this,RemoteHostService::class.java));if(!GlobalPointer.show(this))NativeUi.notice(this,"无法显示光标，请检查悬浮窗权限")}catch(_:RuntimeException){NativeUi.notice(this,"无法开启全局光标")}
            else NativeUi.notice(this,"请开启 ADB 调试并确认系统遥控授权")
        }},"global-pointer-connect").start()
    }
    private var remoteConnecting=false
    private fun systemRemote(){
        if(Hub.adb.ready){NativeUi.notice(this,"手机遥控已就绪");return}
        if(remoteConnecting)return
        remoteConnecting=true
        Thread({val ok=Hub.adb.connect(Hub.adb.port());runOnUiThread{
            remoteConnecting=false
            if(!isFinishing)NativeUi.notice(this,if(ok)"手机遥控已就绪"else "请开启设备 ADB 调试，并确认连接授权")
        }},"remote-authorize").start()
    }
    private fun previewFile(file:File){
        if(file.extension.lowercase() in listOf("jpg","jpeg","png","webp","gif","bmp")){
            Thread({
                val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeFile(file.absolutePath,options)
                options.inSampleSize=1;while(options.outWidth/options.inSampleSize>1920||options.outHeight/options.inSampleSize>1080)options.inSampleSize*=2
                options.inJustDecodeBounds=false
                val bitmap=try{android.graphics.BitmapFactory.decodeFile(file.absolutePath,options)}catch(_:OutOfMemoryError){null}
                runOnUiThread{if(isFinishing||isDestroyed){bitmap?.recycle();return@runOnUiThread};if(bitmap==null){NativeUi.notice(this,"无法预览此图片");return@runOnUiThread}
                    val image=ImageView(this).apply{setImageBitmap(bitmap);adjustViewBounds=true;maxHeight=dp(260);scaleType=ImageView.ScaleType.FIT_CENTER;minimumHeight=dp(220)}
                    val dialog=NativeUi.show(AlertDialog.Builder(this).setTitle(file.name).setView(image).setPositiveButton("关闭",null))
                    dialog.setOnDismissListener{NativeUi.handleExternalDismiss(dialog);image.setImageDrawable(null);bitmap.recycle()}
                }
            },"image-preview").start()
        }else{showLive(false);livePanel?.play(LiveChannel("cast",file.name,"文件",listOf(android.net.Uri.fromFile(file).toString())),true)}
    }
    private fun installApk(file:File){apkInstaller.request(file)}
    private fun showFiles(){
        val receivedDir=File(filesDir,"received").apply{if(!isDirectory)mkdirs()}
        fun currentFiles()=receivedDir.listFiles{f->!f.name.endsWith(".part")}.orEmpty().sortedByDescending{it.lastModified()}
        var files=currentFiles()
        fun label(file:File)="${file.name}  (${file.length()/1024} KB)"
        val fileAdapter=android.widget.ArrayAdapter(this,android.R.layout.simple_list_item_1,files.map(::label).toMutableList())
        var filesDialog:AlertDialog?=null
        var fileObserver:android.os.FileObserver?=null
        fun refreshFiles(){
            val dialog=filesDialog?.takeIf{it.isShowing}?:run{fileObserver?.stopWatching();return}
            val updated=currentFiles()
            if(updated.map{it.name to it.length()}==files.map{it.name to it.length()})return
            files=updated
            val hadFocus=dialog.listView?.hasFocus()==true
            val selected=dialog.listView?.selectedItemPosition?:-1
            fileAdapter.clear();fileAdapter.addAll(files.map(::label))
            (dialog.listView?.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
            if(hadFocus&&files.isNotEmpty()){dialog.listView?.let{list->list.setSelection(selected.coerceIn(0,files.size-1));list.requestFocus()}}
        }
        fileObserver=object:android.os.FileObserver(receivedDir.path,android.os.FileObserver.CREATE or android.os.FileObserver.MOVED_TO or android.os.FileObserver.DELETE or android.os.FileObserver.MOVED_FROM){
            override fun onEvent(event:Int,path:String?){handler.post{refreshFiles()}}
        }
        filesDialog=NativeUi.show(AlertDialog.Builder(this).setTitle("收到的文件").setAdapter(fileAdapter){_,which->val file=files[which]
            val extension=file.extension.lowercase()
            val options=(if(extension in listOf("jpg","jpeg","png","webp","gif","bmp","mp4","mkv","webm","mov","m4v","ts"))listOf("预览")else emptyList())+(if(extension in listOf("jpg","jpeg","png","webp","gif","bmp"))listOf("设为桌面背景")else emptyList())+(if(extension=="apk")listOf("安装 APK")else emptyList())+(if(extension in listOf("m3u","m3u8","txt","json","csv","pls","xspf"))listOf("导入直播节目单")else emptyList())+listOf("保存到下载","删除")
            NativeUi.show(AlertDialog.Builder(this).setTitle(file.name).setItems(options.toTypedArray()){_,index->when(options[index]){
                "预览"->previewFile(file)
                "安装 APK"->installApk(file)
                "设为桌面背景"->Thread({val error=try{HomeAppearance.importImage(this,file.inputStream());null}catch(_:OutOfMemoryError){"图片尺寸过大"}catch(e:Throwable){e.message?:"背景导入失败"};runOnUiThread{if(error==null){appearanceUpdated();NativeUi.notice(this,"背景已更新")}else NativeUi.notice(this,error)}},"file-wallpaper").start()
                "保存到下载"->fileExporter.request(file)
                "导入直播节目单"->Thread({val ok=if(file.length()<=PlaylistParser.MAX_BYTES)Hub.live.importText(file.readText())else false;runOnUiThread{NativeUi.notice(this,if(ok)"节目单已导入" else Hub.live.lastMessage);liveUpdated()}},"live-file").start()
                else->NativeUi.show(AlertDialog.Builder(this).setMessage("确认删除？").setPositiveButton("删除"){_,_->file.delete();showFiles()}.setNegativeButton("取消",null))
            }}.setNegativeButton("关闭",null))
        }.setNegativeButton("关闭",null),maxListHeightDp=240)
        fileObserver?.startWatching()
    }
    override fun onActivityResult(request:Int,result:Int,data:Intent?){super.onActivityResult(request,result,data);if(appearanceUi.onResult(request,result,data)){startupDialog?.dismiss();return};if(request==41){pointerPermissionPending=false;if(GlobalPointer.permitted(this)&&GlobalPointer.requested)requestGlobalPointer()else NativeUi.notice(this,"未授予悬浮窗权限");return};if(apkInstaller.onResult(request))return;if(request==20)return;if(request==52){showApps(false);return}}
    override fun onRequestPermissionsResult(request:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(request,permissions,results);fileExporter.onPermissionResult(request,results)}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        UiSounds.key(this,event)
        if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0&&screen=="home"&&event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)&&::home.isInitialized&&home.visibility==View.VISIBLE){
            val focus=currentFocus
            val bad=focus==null||focus.visibility!=View.VISIBLE||!focus.isFocusable||!isInHome(focus)||focus===home
            if(bad){home.focusFirst();return super.dispatchKeyEvent(event)}
        }
        if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0&&event.keyCode==KeyEvent.KEYCODE_MENU&&showFocusedAppMenu())return true
        if(event.action==KeyEvent.ACTION_DOWN&&screen=="home"&&event.keyCode==KeyEvent.KEYCODE_SETTINGS){showAppSettings();return true}
        if(event.action==KeyEvent.ACTION_DOWN&&screen=="home"){if(home.handleKey(event.keyCode))return true;if(event.keyCode==KeyEvent.KEYCODE_MENU){showAppSettings();return true}}
        if(event.action==KeyEvent.ACTION_DOWN&&screen=="live"&&livePanel?.handleKey(event.keyCode)==true)return true
        return super.dispatchKeyEvent(event)
    }
    private fun exitOne(){DesktopTakeover.cancel(this);DesktopGuardService.allowStockLauncher();startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));if(Build.VERSION.SDK_INT>=21)finishAndRemoveTask()else finish()}
    private fun showExitDialog(){NativeUi.show(AlertDialog.Builder(this).setMessage("退出One？").setPositiveButton("退出"){_,_->exitOne()}.setNegativeButton("继续使用",null))}
    override fun onBackPressed(){when(screen){"apps","settings","control","liveSetup"->showHome();"live"->if(livePanel?.back()!=true)showHome();else->if(!isDefaultHome()&&!DesktopTakeover.wanted(this)){
        val now=System.currentTimeMillis()
        if(now-lastHomeBackMs<500){handler.removeCallbacks(homeBackExitDelay);lastHomeBackMs=0;exitOne()}
        else{lastHomeBackMs=now;handler.removeCallbacks(homeBackExitDelay);handler.postDelayed(homeBackExitDelay,500)}
    }}}
    override fun onSaveInstanceState(state:Bundle){super.onSaveInstanceState(state);apkInstaller.save(state)}
    override fun onResume(){super.onResume();DesktopTakeover.recover(this);DesktopTakeover.finishSetup(this){};LiveLaunch.component(this);apkInstaller.onResume();apkInstaller.checkInstallStatus();if(::home.isInitialized){Hub.live.refreshPlugin();if(screen=="liveSetup"&&Hub.live.resumeChannel()!=null){showLive(false);livePanel?.command("liveFull","")}};visible=true;DesktopRecovery.setHomeInFront(this,true);if(startupPrefs.getBoolean("boot",false)&&!BootReceiver.arrivedThisBoot(this))BootReceiver.markArrived(this);DesktopGuardService.onOneResumed();desktopConnectionChanged();if(::home.isInitialized&&screen=="home"){home.refreshApps();home.focusFirst()};if(::home.isInitialized){handler.removeCallbacks(ticker);handler.post(ticker)};livePanel?.resume()}
    override fun onPause(){apkInstaller.onPause();startupDialog?.dismiss();visible=false;DesktopRecovery.setHomeInFront(this,false);DesktopGuardService.onOnePaused();handler.removeCallbacks(ticker);livePanel?.pause();super.onPause()}
    private fun isInHome(view:View):Boolean{var current:View?=view;while(current!=null){if(current===home)return true;current=current.parent as? View};return false}
    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus&&screen=="home"&&::home.isInitialized&&home.visibility==View.VISIBLE){
            val focus=currentFocus
            if(focus==null||focus.visibility!=View.VISIBLE||!focus.isFocusable||!isInHome(focus)){home.focusFirst()}
        }
    }
    override fun onDestroy(){apkInstaller.destroy();updates.destroy();if(catalogReceiverRegistered){unregisterReceiver(catalogReceiver);catalogReceiverRegistered=false};handler.removeCallbacksAndMessages(null);livePanel?.stop();if(Hub.activity===this)Hub.activity=null;if(!RemoteHostService.running)Hub.stop();super.onDestroy()}
}
