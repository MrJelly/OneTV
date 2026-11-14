package com.tvbridge.app

import android.app.Activity
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LiveResumeTest {
    @org.junit.Before fun useCustomLiveFixture(){LiveLaunch.choose(org.robolectric.RuntimeEnvironment.getApplication(),null)}
    @Test fun lastChannelSurvivesRestartAndStalePluginSyncCannotReplaceNewerSelection(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.filesDir.resolve("live-sources.json").delete()
        try{
            val repo=LiveRepository(a);repo.importText("A,https://example.test/a.m3u8\nB,https://example.test/b.m3u8")
            val channels=repo.allChannels();repo.rememberChannel(channels[1].id)
            assertEquals(channels[1].id,LiveRepository(a).resumeChannel()!!.id)
            val before=repo.exportStore();val time=JSONObject(before).getLong("lastPlayedAt")
            val stale=JSONObject(before).put("lastChannel",channels[0].id).put("lastPlayedAt",time+1).toString()
            repo.rememberChannel(channels[0].id);repo.rememberChannel(channels[1].id)
            repo.mergeSnapshot(before,stale);assertEquals(channels[1].id,repo.resumeChannel()!!.id)
            val fresh=JSONObject(before).put("lastChannel",channels[0].id).put("lastPlayedAt",time+10000).toString()
            repo.mergeSnapshot(before,fresh);assertEquals(channels[0].id,LiveRepository(a).resumeChannel()!!.id)
            repo.removeSource("local");assertNull(repo.resumeChannel())
            repo.importText("C,https://example.test/c.m3u8");assertEquals("C",repo.resumeChannel()!!.name)
        }finally{c.pause().stop().destroy()}
    }
    @Test fun missingPluginFallsBackWithoutErasingItsRememberedChannel(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.filesDir.resolve("live-sources.json").delete()
        try{
            val repo=LiveRepository(a);repo.importText("本地频道,https://example.test/local.m3u8")
            val state=JSONObject(repo.exportStore()).put("lastChannel","yangshipin-600001859").put("lastPlayedAt",100)
            state.getJSONArray("sources").put(JSONObject().put("url",LiveRepository.DEFAULT_SOURCE).put("name","插件").put("channels",JSONArray().put(JSONObject().put("id","yangshipin-600001859").put("name","插件频道").put("group","测试").put("streams",JSONArray().put("one-plugin://live/600001859")))))
            repo.restoreSnapshot(state.toString())
            assertEquals("本地频道",repo.resumeChannel()!!.name)
            assertEquals("yangshipin-600001859",JSONObject(repo.exportStore()).getString("lastChannel"))
        }finally{c.pause().stop().destroy()}
    }
}
