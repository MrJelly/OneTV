package com.tvbridge.app

import android.app.Activity
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class EngineStoreTest {
    @org.junit.Before fun useCustomLiveFixture(){LiveLaunch.choose(org.robolectric.RuntimeEnvironment.getApplication(),null)}
    @Test fun engineFavoriteMergePreservesConcurrentPhoneChanges(){
        val context=Robolectric.buildActivity(Activity::class.java).setup().get();context.filesDir.resolve("live-sources.json").delete()
        val repo=LiveRepository(context);repo.importText("A,https://example.com/a.m3u8\nB,https://example.com/b.m3u8")
        val channels=repo.allChannels();val before=repo.exportStore()
        val after=JSONObject(before).put("favorites",JSONArray().put(channels[0].id)).toString()
        repo.toggleFavorite(channels[1].id);repo.addDirect("C","https://example.com/c.m3u8")
        repo.mergeSnapshot(before,after)
        assertTrue(repo.favorite(channels[0].id));assertTrue(repo.favorite(channels[1].id));assertTrue(repo.allChannels().any{it.name=="C"})
        val restored=LiveRepository(context);assertTrue(restored.favorite(channels[0].id));assertTrue(restored.allChannels().any{it.name=="C"})
    }
    @Test fun favoritesCombineOfficialAndSubscribedSources(){
        val context=Robolectric.buildActivity(Activity::class.java).setup().get();context.filesDir.resolve("live-sources.json").delete()
        val repo=LiveRepository(context);repo.importText("A,https://example.com/a.m3u8");val official=repo.allChannels().first();repo.toggleFavorite(official.id)
        repo.importText("自定义,https://example.com/custom.m3u8");val custom=repo.allChannels().first{it.name=="自定义"};repo.toggleFavorite(custom.id)
        assertEquals(setOf(official.id,custom.id),repo.favoriteChannels().map{it.id}.toSet())
        assertEquals(official,repo.findChannel(official.id));assertEquals(2,JSONObject(repo.snapshot()).getJSONArray("favoriteChannels").length())
        repo.selectSource(LiveRepository.DEFAULT_SOURCE);assertEquals(2,repo.favoriteChannels().size)
    }
    @Test fun malformedSnapshotIsRejectedBeforeReplacingSources(){
        val context=Robolectric.buildActivity(Activity::class.java).setup().get();val repo=LiveRepository(context)
        repo.importText("A,https://example.com/a.m3u8");val before=repo.exportStore();val bad=JSONObject(before);bad.getJSONArray("sources").getJSONObject(0).put("url","file:///private")
        try{repo.restoreSnapshot(bad.toString());fail("Untrusted source accepted")}catch(_:IllegalArgumentException){}
        assertEquals(before,repo.exportStore())
    }
}
