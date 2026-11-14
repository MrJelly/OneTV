package com.tvbridge.app

import android.content.ContextWrapper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OnlineWallpapersTest {
    private fun page(start:Int)=JSONObject().put("images",JSONArray().apply{
        for(i in start until start+8)put(JSONObject().put("urlbase","/th?id=OHR.Landscape$i"+"_ZH-CN001")
            .put("url","/th?id=OHR.Landscape$i"+"_ZH-CN001_1920x1080.jpg").put("startdate","20261106"))
    }).toString()
    @Test fun officialPagesAreDeduplicatedAndFullCycleSurvivesNewContextWithoutRepeats(){
        val app=RuntimeEnvironment.getApplication();var calls=0
        val fetch:(String)->String={calls++;page(if(it.contains("idx=7"))7 else 0)}
        val seen=mutableListOf<String>()
        repeat(15){
            val context=ContextWrapper(app)
            val picture=OnlineWallpapers.select(context,true,fetch)
            assertFalse("Repeated before exhausting the catalog",picture.id in seen)
            seen.add(picture.id);OnlineWallpapers.used(context,picture)
        }
        assertEquals(2,calls)
        assertNotEquals(seen.last(),OnlineWallpapers.select(app,true,fetch).id)
    }
    @Test fun offlineCatalogRefreshKeepsWorkingPicturesAndDoesNotChangeTheAppliedWallpaper(){
        val app=RuntimeEnvironment.getApplication();HomeAppearance.setGradient(app)
        val before=HomeAppearance.key(app)
        val first=OnlineWallpapers.select(app,true){page(0)};OnlineWallpapers.used(app,first)
        app.getSharedPreferences("appearance",0).edit().putLong("bing_pool_checked",1).commit()
        val second=OnlineWallpapers.select(app,true){throw java.io.IOException("offline")}
        assertNotEquals(first.id,second.id);assertEquals(before,HomeAppearance.key(app))
    }
}
