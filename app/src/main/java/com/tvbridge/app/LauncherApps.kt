package com.tvbridge.app

import android.content.*
import android.content.pm.PackageManager
import org.json.JSONArray

class LauncherApps(private val context:Context){
    companion object{
        private val orderLock=Any()
        private val catalogs=java.util.WeakHashMap<PackageManager,List<Entry>>()
        private val icons=android.util.LruCache<String,android.graphics.drawable.Drawable.ConstantState>(48)
        private val squares=object:android.util.LruCache<String,android.graphics.Bitmap>(16*1024*1024){override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.byteCount}
        fun invalidate(context:Context)=synchronized(catalogs){catalogs.remove(context.packageManager);synchronized(icons){icons.evictAll()};synchronized(squares){squares.evictAll()}}
    }
    data class Entry(val component:ComponentName,val label:String,val category:String=Intent.CATEGORY_LAUNCHER,val updated:Long=0)
    private val prefs=context.getSharedPreferences("launcher-apps",Context.MODE_PRIVATE)
    fun installed():List<Entry> = synchronized(catalogs){
        val pm=context.packageManager
        catalogs[pm]?.let{return@synchronized it}
        val found=linkedMapOf<String,Entry>()
        for(category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER,"android.intent.category.APPSTORE_LAUNCHER")){
            val matches=runCatching{pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category),0)}.getOrDefault(emptyList())
            for(result in matches){
                val info=result.activityInfo?:continue
                if(info.packageName==context.packageName||!info.exported||!info.enabled||found.containsKey(info.packageName))continue
                val stamp=try{pm.getPackageInfo(info.packageName,0).lastUpdateTime}catch(_:PackageManager.NameNotFoundException){0L}
                found[info.packageName]=Entry(ComponentName(info.packageName,info.name),result.loadLabel(pm).toString(),category,stamp)
            }
        }
        try{
            for(app in pm.getInstalledApplications(0)){
                if(app.packageName==context.packageName||found.containsKey(app.packageName))continue
                val intent=try{pm.getLaunchIntentForPackage(app.packageName)}catch(_:Exception){null}?:continue
                val component=intent.component?:continue
                val stamp=try{pm.getPackageInfo(app.packageName,0).lastUpdateTime}catch(_:PackageManager.NameNotFoundException){0L}
                found[app.packageName]=Entry(component,try{pm.getApplicationLabel(app).toString()}catch(_:Exception){app.packageName},Intent.CATEGORY_LAUNCHER,stamp)
            }
        }catch(_:Exception){}
        // Firmware apps that declare MAIN with no launcher category never
        // match the queries above; add the known ones by component, only
        // where installed, enabled and exported (Aerial: GITV #1378,
        // Xiaomi's app store on older firmware #1568).
        for(component in listOf(
            ComponentName("com.gitv.dvb.live","com.gitv.dvb.live.activity.PlayActivity"),
            ComponentName("com.xiaomi.mitv.appstore","com.xiaomi.mitv.appstore.MainActivity")
        )){
            if(component.packageName in found)continue
            runCatching{pm.getActivityInfo(component,0)}.getOrNull()?.takeIf{it.exported&&it.enabled&&it.applicationInfo.enabled}?.let{info->
                found[component.packageName]=Entry(component,info.loadLabel(pm).toString(),category="")
            }
        }
        found.values.sortedBy{it.label.lowercase()}.also{if(it.isNotEmpty())catalogs[pm]=it}
    }
    fun icon(entry:Entry):android.graphics.drawable.Drawable {
        val key="${entry.component.flattenToString()}:${entry.updated}"
        synchronized(icons){icons.get(key)?.let{return it.newDrawable(context.resources)}}
        val original=try{context.packageManager.getActivityIcon(entry.component)}catch(_:Exception){context.packageManager.defaultActivityIcon}
        val image=android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888)
        val w=original.intrinsicWidth.coerceAtLeast(1);val h=original.intrinsicHeight.coerceAtLeast(1);val scale=128f/maxOf(w,h)
        val width=(w*scale).toInt();val height=(h*scale).toInt()
        original.setBounds((128-width)/2,(128-height)/2,(128+width)/2,(128+height)/2);original.draw(android.graphics.Canvas(image))
        val drawable=android.graphics.drawable.BitmapDrawable(context.resources,image)
        drawable.constantState?.let{synchronized(icons){icons.put(key,it)}}
        return drawable
    }
    private fun squareCacheFile(key: String): java.io.File {
        val dir = java.io.File(context.cacheDir, "square-icons-v1")
        if (!dir.isDirectory) dir.mkdirs()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$digest.webp")
    }

    fun squareCached(entry: Entry): Boolean {
        val key = "${entry.component.flattenToString()}:${entry.updated}"
        return synchronized(squares) { squares.get(key) != null }
    }

    /**
     * iOS-style square app icon: the app's own artwork, full-bleed (the tile applies the
     * rounded mask and the gloss). Adaptive icons draw their own background + foreground
     * layers cropped to the safe zone; opaque square legacy icons fill edge-to-edge;
     * strongly non-square opaque icons sit fitted over their corner colour so nothing is
     * chopped off; transparent glyphs sit centred on plain white.
     * Results are cached in memory and persisted as WebP in cacheDir so cold starts skip
     * APK icon decoding.
     */
    fun squareIcon(entry: Entry): android.graphics.drawable.Drawable {
        val key = "${entry.component.flattenToString()}:${entry.updated}"
        synchronized(squares) {
            squares.get(key)?.let { return android.graphics.drawable.BitmapDrawable(context.resources, it) }
        }
        val cacheFile = runCatching { squareCacheFile(key) }.getOrNull()
        if (cacheFile != null && cacheFile.isFile) {
            val cached = runCatching { android.graphics.BitmapFactory.decodeFile(cacheFile.path) }.getOrNull()
            if (cached != null) {
                synchronized(squares) { squares.put(key, cached) }
                return android.graphics.drawable.BitmapDrawable(context.resources, cached)
            }
        }
        val size = 256
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val pm = context.packageManager
        val original = try { pm.getActivityIcon(entry.component) } catch (_: Exception) { pm.defaultActivityIcon }
        if (android.os.Build.VERSION.SDK_INT >= 26 && original is android.graphics.drawable.AdaptiveIconDrawable) {
            canvas.drawColor(android.graphics.Color.WHITE)
            val lo = -size / 4
            val hi = size + size / 4
            original.background?.let { it.setBounds(lo, lo, hi, hi); it.draw(canvas) }
            original.foreground?.let { it.setBounds(lo, lo, hi, hi); it.draw(canvas) }
        } else {
            val bitmap = (icon(entry) as? android.graphics.drawable.BitmapDrawable)?.bitmap
            val iw = original.intrinsicWidth.coerceAtLeast(1)
            val ih = original.intrinsicHeight.coerceAtLeast(1)
            val nearSquare = maxOf(iw, ih) <= minOf(iw, ih) * 1.25f
            if (bitmap != null && isOpaque(bitmap) && nearSquare) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                val side = minOf(bitmap.width, bitmap.height)
                val src = android.graphics.Rect(
                    (bitmap.width - side) / 2,
                    (bitmap.height - side) / 2,
                    (bitmap.width + side) / 2,
                    (bitmap.height + side) / 2
                )
                canvas.drawBitmap(bitmap, src, android.graphics.Rect(0, 0, size, size), paint)
            } else if (bitmap != null && isOpaque(bitmap)) {
                canvas.drawColor(bitmap.getPixel(0, 0))
                val inner = size * 0.88f
                val s = inner / maxOf(iw, ih)
                val dw = iw * s
                val dh = ih * s
                original.setBounds(((size - dw) / 2).toInt(), ((size - dh) / 2).toInt(), ((size + dw) / 2).toInt(), ((size + dh) / 2).toInt())
                original.draw(canvas)
            } else {
                canvas.drawColor(android.graphics.Color.WHITE)
                val inner = size * 0.72f
                val s = inner / maxOf(iw, ih)
                val dw = iw * s
                val dh = ih * s
                original.setBounds(((size - dw) / 2).toInt(), ((size - dh) / 2).toInt(), ((size + dw) / 2).toInt(), ((size + dh) / 2).toInt())
                original.draw(canvas)
            }
        }
        synchronized(squares) { squares.put(key, out) }
        if (cacheFile != null) {
            AppThreads.onIo {
                runCatching {
                    val tmp = java.io.File(cacheFile.parentFile, cacheFile.name + ".tmp")
                    tmp.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.WEBP, 90, it) }
                    if (!tmp.renameTo(cacheFile)) tmp.delete()
                }
            }
        }
        return android.graphics.drawable.BitmapDrawable(context.resources, out)
    }

    /** Returns cached WebP bytes for [entry] directly from disk when available, avoiding redundant re-encoding. */
    fun squareIconWebp(entry: AppEntry): ByteArray? {
        val key = "${entry.component.flattenToString()}:${entry.updated}"
        val file = squareCacheFile(context, key)
        if (file != null && file.isFile) {
            runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** Alpha-only probe: does this artwork paint (nearly) the whole square by itself? */
    private fun isOpaque(bitmap: android.graphics.Bitmap): Boolean {
        val stepX = maxOf(1, bitmap.width / 16)
        val stepY = maxOf(1, bitmap.height / 16)
        var total = 0
        var clear = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                total++
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) < 250) clear++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && clear * 50 <= total
    }
    private fun readIds(key:String):List<String>? = try{val raw=prefs.getString(key,null)?:return null;val a=JSONArray(raw);(0 until a.length()).map{a.getString(it)}}catch(_:Exception){null}
    private fun writeIds(key:String,ids:List<String>){prefs.edit().putString(key,JSONArray(ids).toString()).apply()}
    private object Defaults{
        /** Bumped key: the pre-Aerial order (seeded from the retired favourites) is void. */
        const val ORDER_KEY="app-order-v2"
        /** Aerial's first-run arrangement (LayoutStore.Defaults): streaming apps first. */
        val preferredOrder=listOf("com.ktcp.video","com.gitvdemo.video","com.cibn.tv","com.hunantv.license","com.xiaodianshi.tv.yst","com.netflix.ninja","com.google.android.youtube.tv","com.apple.atve.androidtv.appletv","com.disney.disneyplus","com.amazon.amazonvideo.livingroom","com.hbo.hbonow","com.spotify.tv.android","com.mitv.mivideoplayer","com.quark.yun.tv")
        /** The TV's own utilities go last, in this order among themselves. */
        val utilityOrder=listOf("com.xiaomi.mitv.settings","com.android.tv.settings","com.xiaomi.mitv.tvmanager","com.mitv.tvhome","com.google.android.tvlauncher","com.google.android.apps.tv.launcherx")
        fun isUtility(pkg:String)=pkg in utilityOrder||pkg.startsWith("com.android.")
        val defaultComparator=compareBy<Entry>({preferredOrder.indexOf(it.component.packageName).let{i->if(i<0)Int.MAX_VALUE else i}},{if(isUtility(it.component.packageName))1 else 0},{utilityOrder.indexOf(it.component.packageName)},{it.label.lowercase()})
    }
    /** The one app order shared by the home grid and the phone layout page. With no saved
     *  order the arrangement is Aerial's default (preferred apps first, utilities last, the
     *  rest by label); afterwards the saved order rules and new installs join at the end. */
    fun orderedIds():List<String> = synchronized(orderLock){
        val modules=AppModules.all.map{it.id}
        val defaults=modules+installed().sortedWith(Defaults.defaultComparator).map{it.component.flattenToString()}
        val valid=defaults.toSet()
        val stored=readIds(Defaults.ORDER_KEY)
        val saved=stored.orEmpty().filter{it in valid}
        val base=if(saved.none{it in modules})modules+saved else saved
        val merged=(base+defaults).distinct()
        if(merged!=stored)writeIds(Defaults.ORDER_KEY,merged)
        merged
    }
    fun setOrder(ids:List<String>)=synchronized(orderLock){
        val current=orderedIds()
        val valid=current.toSet()
        val chosen=ids.filter{it in valid}.distinct()
        val modules=AppModules.all.map{it.id}
        val order=if(chosen.none{it in modules})modules+chosen else chosen
        writeIds(Defaults.ORDER_KEY,(order+current).distinct())
    }
    fun move(id:String,offset:Int):Boolean=synchronized(orderLock){
        val ids=orderedIds().toMutableList()
        val from=ids.indexOf(id)
        if(from<0)return@synchronized false
        ids.removeAt(from)
        ids.add((from+offset).coerceIn(0,ids.size),id)
        writeIds(Defaults.ORDER_KEY,ids)
        true
    }
    fun hiddenIds()=readIds("home_hidden").orEmpty()
    fun setHidden(id:String,hidden:Boolean)=synchronized(orderLock){
        val ids=hiddenIds()
        writeIds("home_hidden",if(hidden)(ids+id).distinct() else ids-id)
    }
    fun folderNames()=readIds("home_folders").orEmpty()
    fun folderMembers(name:String)=readIds("home_folder:"+name).orEmpty()
    fun folderLayout(order:List<String>):Map<String,List<String>> = synchronized(orderLock){
        val positions=order.withIndex().associate{it.value to it.index}
        folderNames().associateWith{folder->
            folderMembers(folder).filter{it in positions}.distinct().sortedBy{positions.getValue(it)}
        }.filterValues{it.isNotEmpty()}
    }
    fun moveToFolder(id:String,name:String?)=synchronized(orderLock){
        val folders=folderNames()
        val names=if(name==null)folders else (folders+name).distinct()
        val editor=prefs.edit()
        val remaining=names.filter{folder->
            val before=folderMembers(folder)
            val after=if(folder==name)(before+id).distinct() else before-id
            if(after.isEmpty())editor.remove("home_folder:"+folder)
            else if(after!=before)editor.putString("home_folder:"+folder,JSONArray(after).toString())
            after.isNotEmpty()
        }
        editor.putString("home_folders",JSONArray(remaining).toString()).apply()
    }
    fun renameFolder(old:String,name:String):Boolean=synchronized(orderLock){
        val folders=folderNames()
        if(old !in folders||name.isBlank()||name in folders)return@synchronized false
        prefs.edit().putString("home_folders",JSONArray(folders.map{if(it==old)name else it}).toString())
            .putString("home_folder:"+name,JSONArray(folderMembers(old)).toString()).remove("home_folder:"+old).apply()
        true
    }
    fun dissolveFolder(name:String)=synchronized(orderLock){
        prefs.edit().putString("home_folders",JSONArray(folderNames()-name).toString())
            .remove("home_folder:"+name).apply()
    }
    fun bootApp():String?{
        val startup=context.getSharedPreferences("startup",0)
        return startup.getString("boot_app",null)?:if(startup.getBoolean("live",false))"module:live" else null
    }
    fun toggleBootApp(id:String):Boolean{
        val enable=bootApp()!=id
        context.getSharedPreferences("startup",0).edit().putString("boot_app",if(enable)id else null).remove("live").apply()
        return enable
    }
    fun displayTitle(id:String,fallback:String)=prefs.getString("home_title:"+id,null)?:fallback
    fun setTitle(id:String,name:String){
        prefs.edit().putString("home_title:"+id,name.takeIf{it.isNotBlank()}).apply()
    }
    fun appTitle(id:String)=(AppModules.all.firstOrNull{it.id==id}?.title
        ?:installed().firstOrNull{it.component.flattenToString()==id}?.label)?.let{displayTitle(id,it)}

    fun swap(first:String,second:String)=synchronized(orderLock){
        val ids=orderedIds().toMutableList()
        val from=ids.indexOf(first)
        val to=ids.indexOf(second)
        if(from<0||to<0||from==to)return@synchronized
        ids[from]=second
        ids[to]=first
        writeIds(Defaults.ORDER_KEY,ids)
    }
    fun launch(entry:Entry):Boolean {
        if(entry.component.packageName in setOf("com.android.settings","com.android.tv.settings","com.changhong.easysetting")){
            val intent=ChanghongSettings.intent(context)
            if(intent!=null&&ChanghongSettings.open(context,intent))return true
        }
        fun direct()=try{context.startActivity(Intent(Intent.ACTION_MAIN).apply{if(entry.category.isNotEmpty())addCategory(entry.category)}.setComponent(entry.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));true}catch(_:RuntimeException){false}
        val stock=DesktopTakeover.isStockHome(context,entry.component)
        val opened=if(stock)DesktopGuardService.openStock(context,entry.component,::direct)else direct()
        if(opened&&!stock)DesktopRecovery.noteLeftForApp()
        if(!opened)DesktopGuardService.allowStockLauncher(false)
        return opened
    }
}
