package com.tvbridge.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PluginSplitTest {
    @org.junit.Before fun useCustomLiveFixture(){LiveLaunch.choose(org.robolectric.RuntimeEnvironment.getApplication(),null)}
    @Test fun upgradePreservesUserSourcesAndFavoritesAndBacksUpOldState(){
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try{
            val context=c.get();val file=context.filesDir.resolve("live-sources.json")
            val backup=context.filesDir.resolve("live-sources.pre-plugin.json");backup.delete()
            fun channel(id:String,url:String)=JSONObject().put("id",id).put("name",id).put("group","分组").put("streams",JSONArray(listOf(url)))
            fun source(url:String,ch:JSONObject)=JSONObject().put("url",url).put("name","原名称").put("channels",JSONArray(listOf(ch)))
            val old=JSONObject().put("officialCatalogVersion",1).put("active","https://example.test/user.m3u").put("favorites",JSONArray(listOf("yangshipin-600001859","custom")))
                .put("sources",JSONArray(listOf(source("https://www.yangshipin.cn/tv/home",channel("yangshipin-600001859","https://www.yangshipin.cn/tv/home?pid=600001859")),source("https://example.test/user.m3u",channel("custom","https://example.test/video.m3u8"))))).toString()
            file.writeText(old)
            val repo=LiveRepository(context)
            assertEquals("https://example.test/user.m3u",repo.activeSource())
            assertEquals("https://example.test/video.m3u8",repo.allChannels().single().streams.single())
            assertEquals("one-plugin://live/600001859",JSONObject(repo.exportStore()).getJSONArray("sources").let{list->(0 until list.length()).map{list.getJSONObject(it)}.first{it.getString("url")==LiveRepository.DEFAULT_SOURCE}.getJSONArray("channels").getJSONObject(0).getJSONArray("streams").getString(0)})
            assertEquals(listOf("custom"),repo.favoriteChannels().map{it.id});assertEquals(2,JSONObject(repo.exportStore()).getJSONArray("favorites").length());assertEquals(old,backup.readText())
            assertFalse(file.readText().contains("www.yangshipin.cn"))
            val restored=LiveRepository(context);assertEquals(listOf("custom"),restored.favoriteChannels().map{it.id});assertTrue(restored.favorite("yangshipin-600001859"))
            repo.removeSource(LiveRepository.DEFAULT_SOURCE)
            assertTrue(JSONObject(repo.exportStore()).getBoolean("pluginHidden"))
            assertEquals(old,backup.readText())
        }finally{c.pause().stop().destroy()}
    }
    @Test fun pluginUrisAreNotArbitraryWebsiteOrFileUris(){
        assertTrue(LiveCatalog.isPage("one-plugin://live/600001859"))
        for(value in listOf("one-plugin://user@live/600001859","one-plugin://live/600001859?url=https://example.test","one-plugin://live/../private","file:///private","one-plugin://other/600001859"))assertFalse(value,LiveCatalog.isPage(value))
    }
    @Test fun homeThemeKeepsFocusAndDoesNotRecreateUnchangedShortcuts(){
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try{
            val a=c.get();HomeAppearance.setDark(a,false)
            val home=TvHomeView(a,{},{},{},{},{},{})
            a.setContentView(home)
            val add=descendants(home).filterIsInstance<Button>().first{it.text=="添加应用"}
            home.refreshApps();assertSame(add,descendants(home).filterIsInstance<Button>().first{it.text=="添加应用"})
            val files=descendants(home).filterIsInstance<Button>().first{it.text=="文件管理"}
            files.requestFocusFromTouch();home.visibility=View.GONE;home.visibility=View.VISIBLE;home.focusFirst();assertTrue(files.hasFocus())
            HomeAppearance.setDark(a,true);home.refreshAppearance()
            home.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));home.layout(0,0,1920,1080)
            val buttons=descendants(home).filterIsInstance<Button>().filter{it.text.isNotEmpty()}
            assertTrue(buttons.all{it.left>=0&&it.top>=0})
            HomeAppearance.setDark(a,false)
        }finally{c.pause().stop().destroy()}
    }
    @Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
    @Test fun choiceDialogKeepsCheckedItemAndFitsBothRows(){
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try{
            val dialog=NativeUi.show(android.app.AlertDialog.Builder(c.get()).setTitle("背景").setSingleChoiceItems(arrayOf("浅色","深色"),1){_,_->})
            assertTrue(dialog.listView.isItemChecked(1))
            val list=dialog.listView
            list.measure(View.MeasureSpec.makeMeasureSpec(760,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(216,View.MeasureSpec.EXACTLY));list.layout(0,0,760,216)
            assertEquals(2,list.childCount)
            assertTrue(list.getChildAt(1).bottom<=list.height)
            dialog.dismiss()
        }finally{c.pause().stop().destroy()}
    }
    @Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
    @Test fun tenShortcutsFitTwoRowsAndConnectionIsAnActualPanel(){
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try{
            val a=c.get();a.getSharedPreferences("launcher-apps",0).edit().clear().commit()
            val apps=LauncherApps(a)
            for(i in 0 until 10){
                val pkg="example.tile$i"
                val resolve=android.content.pm.ResolveInfo().apply{activityInfo=android.content.pm.ActivityInfo().apply{packageName=pkg;name="$pkg.Main";exported=true;nonLocalizedLabel="应用$i";applicationInfo=android.content.pm.ApplicationInfo().apply{packageName=pkg}}}
                org.robolectric.Shadows.shadowOf(a.packageManager).addResolveInfoForIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),resolve)
                apps.toggle(LauncherApps.Entry(android.content.ComponentName(pkg,"$pkg.Main"),"应用$i"))
            }
            val home=TvHomeView(a,{},{},{},{},{},{})
            a.setContentView(home);home.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));home.layout(0,0,1920,1080)
            val tiles=descendants(home).filterIsInstance<ShortcutButton>();assertEquals(10,tiles.size)
            tiles.forEach{val rect=android.graphics.Rect();it.getDrawingRect(rect);home.offsetDescendantRectToMyCoords(it,rect);assertTrue(rect.right<=1920);assertTrue(rect.bottom<=1080);assertTrue(rect.top>=0)}
            home.update("http://192.0.2.1:8765","123456","http://192.0.2.1:8765/#connect=test",1,"")
            descendants(home).filterIsInstance<Button>().first{it.text=="手机连接"}.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing)
            assertTrue(descendants(dialog.window!!.decorView).any{it is android.widget.ImageView})
            assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<android.widget.TextView>().any{it.text.toString().contains("123456")})
            dialog.dismiss();a.getSharedPreferences("launcher-apps",0).edit().clear().commit()
        }finally{c.pause().stop().destroy()}
    }
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
}
