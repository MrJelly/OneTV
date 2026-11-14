package com.tvbridge.app

import android.content.*
import android.content.pm.PackageManager
import org.json.JSONArray

class LauncherApps(private val context:Context){
    companion object{
        const val MAX_FAVORITES=10
        private val catalogs=java.util.WeakHashMap<PackageManager,List<Entry>>()
        private val icons=android.util.LruCache<String,android.graphics.drawable.Drawable.ConstantState>(48)
        fun invalidate(context:Context)=synchronized(catalogs){catalogs.remove(context.packageManager);synchronized(icons){icons.evictAll()}}
    }
    data class Entry(val component:ComponentName,val label:String,val category:String=Intent.CATEGORY_LAUNCHER,val updated:Long=0)
    private val prefs=context.getSharedPreferences("launcher-apps",Context.MODE_PRIVATE)
    fun installed():List<Entry> = synchronized(catalogs){
        val pm=context.packageManager
        catalogs[pm]?.let{return@synchronized it}
        val found=linkedMapOf<String,Entry>()
        for(category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER,"android.intent.category.APPSTORE_LAUNCHER")){
            for(result in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category),0)){
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
        val gitv=ComponentName("com.gitv.dvb.live","com.gitv.dvb.live.activity.PlayActivity")
        if(gitv.packageName !in found){
            runCatching{pm.getActivityInfo(gitv,0)}.getOrNull()?.takeIf{it.exported&&it.enabled&&it.applicationInfo.enabled}?.let{info->
                found[gitv.packageName]=Entry(gitv,info.loadLabel(pm).toString(),category="")
            }
        }
        found.values.sortedBy{it.label.lowercase()}.also{catalogs[pm]=it}
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
    fun saved():List<String> = try{val a=JSONArray(prefs.getString("favorites","[]"));(0 until a.length()).map{a.getString(it)}}catch(_:Exception){emptyList()}
    fun favorites():List<Entry>{
        val installed=installed();val entries=installed.associateBy{it.component.flattenToString()};val packages=installed.associateBy{it.component.packageName}
        val before=saved();val resolved=before.map{id->entries[id]?:ComponentName.unflattenFromString(id)?.packageName?.let(packages::get)}
        val ids=before.mapIndexed{i,id->resolved[i]?.component?.flattenToString()?:id}.distinct()
        if(ids!=before)prefs.edit().putString("favorites",JSONArray(ids).toString()).apply()
        return resolved.filterNotNull().distinctBy{it.component.packageName}
    }
    fun toggle(entry:Entry):Boolean{val id=entry.component.flattenToString();val ids=saved().toMutableList();if(!ids.remove(id)){if(ids.size>=MAX_FAVORITES)return false;ids.add(id)};prefs.edit().putString("favorites",JSONArray(ids).toString()).apply();return true}
    fun remove(entry:Entry){val ids=saved().filter{it!=entry.component.flattenToString()};prefs.edit().putString("favorites",JSONArray(ids).toString()).apply()}
    fun move(entry:Entry,offset:Int):Boolean{val ids=saved().toMutableList();val from=ids.indexOf(entry.component.flattenToString());val to=from+offset;if(from<0||to !in ids.indices)return false;val other=ids[to];ids[to]=ids[from];ids[from]=other;prefs.edit().putString("favorites",JSONArray(ids).toString()).apply();return true}
    fun launch(entry:Entry):Boolean {
        if(entry.component.packageName in setOf("com.android.settings","com.android.tv.settings","com.changhong.easysetting")){
            val intent=ChanghongSettings.intent(context)
            if(intent!=null&&ChanghongSettings.open(context,intent))return true
        }
        fun direct()=try{context.startActivity(Intent(Intent.ACTION_MAIN).apply{if(entry.category.isNotEmpty())addCategory(entry.category)}.setComponent(entry.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));true}catch(_:RuntimeException){false}
        val opened=if(DesktopTakeover.isStockHome(context,entry.component))DesktopGuardService.openStock(context,entry.component,::direct)else direct()
        if(!opened)DesktopGuardService.allowStockLauncher(false)
        return opened
    }
}
