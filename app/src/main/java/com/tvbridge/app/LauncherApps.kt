package com.tvbridge.app

import android.content.*
import android.content.pm.PackageManager
import org.json.JSONArray

class LauncherApps(context:Context){
    private val context=context.applicationContext
    companion object{
        const val STOCK_HOME_ID="one:stock-home"
        private val orderLock=Any()
        private val scanLock=Any()
        private var catalogGeneration=0
        private val catalogs=java.util.WeakHashMap<PackageManager,List<Entry>>()
        private val icons=android.util.LruCache<String,android.graphics.drawable.Drawable.ConstantState>(48)
        private val squares=object:android.util.LruCache<String,android.graphics.Bitmap>(6*1024*1024){override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.byteCount}
        fun trimMemory(){synchronized(icons){icons.evictAll()};synchronized(squares){squares.evictAll()}}
        fun invalidate(context:Context)=synchronized(catalogs){catalogGeneration++;catalogs.remove(context.applicationContext.packageManager);synchronized(icons){icons.evictAll()};synchronized(squares){squares.evictAll()}}
    }
    data class Entry(val component:ComponentName,val label:String,val category:String=Intent.CATEGORY_LAUNCHER,val updated:Long=0)
    private val prefs=context.getSharedPreferences("launcher-apps",Context.MODE_PRIVATE)
    fun stockHomeEntry():Entry?{
        val component=DesktopTakeover.originalHome(context)?:return null
        val stamp=try{context.packageManager.getPackageInfo(component.packageName,0).lastUpdateTime}catch(_:Exception){0L}
        return Entry(component,"原桌面","",stamp)
    }
    fun catalogReady():Boolean=synchronized(catalogs){catalogs.containsKey(context.packageManager)}
    fun installed():List<Entry> = synchronized(scanLock){
        val pm=context.packageManager
        val generation=synchronized(catalogs){
            catalogs[pm]?.let{return it}
            catalogGeneration
        }
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
        for((component,fallbackLabel) in listOf(
            ComponentName("com.gitv.dvb.live","com.gitv.dvb.live.activity.PlayActivity") to null,
            ComponentName("com.xiaomi.mitv.appstore","com.xiaomi.mitv.appstore.MainActivity") to null,
            ComponentName("com.xiaomi.mitv.mediaexplorer","com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity") to null,
            ComponentName("com.xiaomi.smarthome.tv","com.xiaomi.smarthome.tv.ui.MainActivity") to null,
            ComponentName("com.sony.dtv.searchapp","com.ktcp.aiagent.xwability.activity.VoipHomeActivity") to "小微畅聊"
        )){
            if(component.packageName in found)continue
            runCatching{pm.getActivityInfo(component,0)}.getOrNull()?.takeIf{it.exported&&it.enabled&&it.applicationInfo.enabled}?.let{info->
                val label=info.loadLabel(pm).toString().takeIf{it.isNotBlank()&&it!=component.packageName}?:fallbackLabel?:component.packageName
                val stamp=try{pm.getPackageInfo(component.packageName,0).lastUpdateTime}catch(_:PackageManager.NameNotFoundException){0L}
                found[component.packageName]=Entry(component,label,category="",updated=stamp)
            }
        }
        found.values.sortedBy{it.label.lowercase()}.also{entries->synchronized(catalogs){if(generation==catalogGeneration)catalogs[pm]=entries}}
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
    fun squareCached(entry: Entry): Boolean {
        val key = "${entry.component.flattenToString()}:${entry.updated}"
        return synchronized(squares) { squares.get(key) != null }
    }

    private val tileMagic = 0x4F4E4554
    private val tileHeaderSize = 20
    private val artVersion = 5
    private val artSize = 144
    private val tints = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun artKey(entry: Entry) = "${entry.component.flattenToString()}:${entry.updated}"

    private fun tileFile(entry: Entry): java.io.File {
        val dir = java.io.File(context.cacheDir, "tiles")
        if (!dir.isDirectory) dir.mkdirs()
        return java.io.File(dir, entry.component.packageName + ".tile")
    }

    fun tileTint(entry: Entry): Int = tints[artKey(entry)] ?: 0

    fun readTileHeader(entry: Entry): Boolean {
        val file = tileFile(entry)
        if (!file.isFile) return false
        return runCatching {
            java.io.DataInputStream(file.inputStream().buffered()).use { input ->
                input.readInt() == tileMagic && input.readLong() == entry.updated && input.readInt() == artVersion && run {
                    val tint = input.readInt()
                    if (tint != 0) tints[artKey(entry)] = tint
                    true
                }
            }
        }.getOrDefault(false)
    }

    private fun loadTileArt(entry: Entry): android.graphics.Bitmap? {
        val file = tileFile(entry)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size <= tileHeaderSize) return null
        val header = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes, 0, tileHeaderSize))
        val tint = runCatching {
            if (header.readInt() != tileMagic) return null
            if (header.readLong() != entry.updated) return null
            if (header.readInt() != artVersion) return null
            header.readInt()
        }.getOrNull() ?: return null
        val bitmap = runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, tileHeaderSize, bytes.size - tileHeaderSize) }.getOrNull() ?: return null
        if (tint != 0) tints[artKey(entry)] = tint
        synchronized(squares) { squares.put(artKey(entry), bitmap) }
        return bitmap
    }

    private fun writeTileArt(entry: Entry, bitmap: android.graphics.Bitmap, tint: Int) {
        runCatching {
            val file = tileFile(entry)
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(tileMagic)
                out.writeLong(entry.updated)
                out.writeInt(artVersion)
                out.writeInt(tint)
                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 90, out)
            }
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    fun keepTiles(entries: List<Entry>) {
        runCatching {
            val dir = java.io.File(context.cacheDir, "tiles")
            val valid = entries.map { it.component.packageName + ".tile" }.toSet()
            dir.listFiles()?.forEach { file -> if (file.name !in valid) file.delete() }
            java.io.File(context.cacheDir, "square-icons-v1").deleteRecursively()
        }
    }

    private fun dominantTint(bitmap: android.graphics.Bitmap): Int {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weight = 0.0
        val stepX = maxOf(1, bitmap.width / 24)
        val stepY = maxOf(1, bitmap.height / 24)
        val hsv = FloatArray(3)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) >= 200) {
                    android.graphics.Color.RGBToHSV(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel), hsv)
                    val w = 0.25 + hsv[1]
                    r += android.graphics.Color.red(pixel) * w
                    g += android.graphics.Color.green(pixel) * w
                    b += android.graphics.Color.blue(pixel) * w
                    weight += w
                }
                x += stepX
            }
            y += stepY
        }
        if (weight <= 0.0) return 0
        return android.graphics.Color.rgb((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt())
    }

    private fun tintBase(canvas: android.graphics.Canvas, size: Int, tint: Int) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val hsv = FloatArray(3)
        if (tint != 0) android.graphics.Color.RGBToHSV(android.graphics.Color.red(tint), android.graphics.Color.green(tint), android.graphics.Color.blue(tint), hsv)
        val top: Int
        val bottom: Int
        if (tint != 0 && hsv[1] > 0.08f) {
            hsv[1] = hsv[1].coerceIn(0.5f, 0.85f)
            hsv[2] = 0.56f
            top = android.graphics.Color.HSVToColor(hsv)
            hsv[2] = 0.40f
            bottom = android.graphics.Color.HSVToColor(hsv)
        } else {
            top = 0xff4a4a52.toInt()
            bottom = 0xff2e2e35.toInt()
        }
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), top, bottom, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    }

    fun squareIcon(entry: Entry): android.graphics.drawable.Drawable {
        val key = artKey(entry)
        synchronized(squares) {
            squares.get(key)?.let { return android.graphics.drawable.BitmapDrawable(context.resources, it) }
        }
        loadTileArt(entry)?.let { return android.graphics.drawable.BitmapDrawable(context.resources, it) }
        val size = artSize
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val pm = context.packageManager
        val original = try { pm.getActivityIcon(entry.component) } catch (_: Exception) { pm.defaultActivityIcon }
        val raster = (icon(entry) as? android.graphics.drawable.BitmapDrawable)?.bitmap
        val tint = raster?.let { dominantTint(it) } ?: 0
        if (tint != 0) tints[key] = tint
        if (android.os.Build.VERSION.SDK_INT >= 26 && original is android.graphics.drawable.AdaptiveIconDrawable) {
            tintBase(canvas, size, tint)
            val lo = -size / 4
            val hi = size + size / 4
            original.background?.let { it.setBounds(lo, lo, hi, hi); it.draw(canvas) }
            original.foreground?.let { it.setBounds(lo, lo, hi, hi); it.draw(canvas) }
        } else {
            val bitmap = raster
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
                val inner = size * 0.88f
                val s = inner / maxOf(iw, ih)
                val dw = iw * s
                val dh = ih * s
                original.setBounds(((size - dw) / 2).toInt(), ((size - dh) / 2).toInt(), ((size + dw) / 2).toInt(), ((size + dh) / 2).toInt())
                original.draw(canvas)
            } else {
                val inner = size * 0.76f
                val s = inner / maxOf(iw, ih)
                val dw = iw * s
                val dh = ih * s
                original.setBounds(((size - dw) / 2).toInt(), ((size - dh) / 2).toInt(), ((size + dw) / 2).toInt(), ((size + dh) / 2).toInt())
                original.draw(canvas)
            }
        }
        synchronized(squares) { squares.put(key, out) }
        writeTileArt(entry, out, tint)
        return android.graphics.drawable.BitmapDrawable(context.resources, out)
    }

    fun squareIconWebp(entry: Entry): ByteArray? {
        val file = tileFile(entry)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size <= tileHeaderSize) return null
        val header = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes, 0, tileHeaderSize))
        val current = runCatching {
            header.readInt() == tileMagic && header.readLong() == entry.updated && header.readInt() == artVersion
        }.getOrDefault(false)
        if (!current) return null
        return bytes.copyOfRange(tileHeaderSize, bytes.size)
    }

    private fun isOpaque(bitmap: android.graphics.Bitmap, roundedCorners: Boolean = false): Boolean {
        val stepX = maxOf(1, bitmap.width / 16)
        val stepY = maxOf(1, bitmap.height / 16)
        var total = 0
        var clear = 0
        val insetX = if (roundedCorners) bitmap.width / 8 else 0
        val insetY = if (roundedCorners) bitmap.height / 8 else 0
        var y = insetY
        while (y < bitmap.height - insetY) {
            var x = insetX
            while (x < bitmap.width - insetX) {
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
        const val ORDER_KEY="app-order-v2"
        val preferredOrder=listOf("com.ktcp.video","com.gitvdemo.video","com.cibn.tv","com.hunantv.license","com.xiaodianshi.tv.yst","com.netflix.ninja","com.google.android.youtube.tv","com.apple.atve.androidtv.appletv","com.disney.disneyplus","com.amazon.amazonvideo.livingroom","com.hbo.hbonow","com.spotify.tv.android","com.mitv.mivideoplayer","com.quark.yun.tv")
        val utilityOrder=listOf("com.xiaomi.mitv.settings","com.android.tv.settings","com.xiaomi.mitv.tvmanager","com.mitv.tvhome","com.google.android.tvlauncher","com.google.android.apps.tv.launcherx")
        fun isUtility(pkg:String)=pkg in utilityOrder||pkg.startsWith("com.android.")
        val defaultComparator=compareBy<Entry>({preferredOrder.indexOf(it.component.packageName).let{i->if(i<0)Int.MAX_VALUE else i}},{if(isUtility(it.component.packageName))1 else 0},{utilityOrder.indexOf(it.component.packageName)},{it.label.lowercase()})
    }
    fun orderedIds():List<String> = synchronized(orderLock){
        val modules=AppModules.all.map{it.id}
        val defaults=modules+installed().sortedWith(Defaults.defaultComparator).map{it.component.flattenToString()}+listOfNotNull(stockHomeEntry()?.let{STOCK_HOME_ID})
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
    fun move(id:String,offset:Int,known:List<String>?=null):Boolean=synchronized(orderLock){
        val ids=(known?:orderedIds()).toMutableList()
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
        ?:installed().firstOrNull{it.component.flattenToString()==id}?.label
        ?:if(id==STOCK_HOME_ID)stockHomeEntry()?.label else null)?.let{displayTitle(id,it)}

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
        val stock=DesktopTakeover.isStockHome(context,entry.component)||
            DesktopTakeover.homes(context).any{it.packageName==entry.component.packageName}||
            stockHomeEntry()?.component?.packageName==entry.component.packageName
        val opened=if(stock)DesktopGuardService.openStock(context,entry.component,::direct)else direct()
        if(opened&&!stock)DesktopRecovery.noteLeftForApp()
        if(!opened)DesktopGuardService.allowStockLauncher(false)
        return opened
    }
}
