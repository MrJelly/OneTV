package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Test

class PlaylistParserTest {
    @Test fun resolvesRelativeEntriesWithoutAcceptingUnsafeSchemes(){
        val list=PlaylistParser.parse("""#EXTM3U
            #EXTINF:-1,频道
            ../live/one.m3u8
            #EXTINF:-1,不安全
            file:///private
        """.trimIndent(),"https://origin.test/list.m3u","https://cdn.test/folder/redirect/list.m3u")
        assertEquals(listOf("https://cdn.test/folder/live/one.m3u8"),list.single().streams)
        assertTrue(PlaylistParser.parse("频道,/relative.m3u8").isEmpty())
    }
    @Test fun stripsTxtResolutionLabelsAndRetainsUrlQuery(){
        val list=PlaylistParser.parse("频道,https://example.test/a\$1920×1080#https://example.test/b\$高清")
        assertEquals(listOf("https://example.test/a","https://example.test/b"),list.single().streams)
        assertEquals("https://example.test/a?token=a\$b",PlaylistParser.parse("频道,https://example.test/a?token=a\$b").single().streams.single())
    }
    @Test fun m3uCombinesResolutionVariantsAndGroups() {
        val text="""#EXTM3U
            #EXTINF:-1 tvg-id="CCTV1@HD" group-title="央视频道",CCTV-1 高清
            https://example.com/1.m3u8
            #EXTINF:-1 tvg-id="CCTV1@SD" group-title="央视频道",CCTV-1 标清
            http://example.com/2.m3u8
            #EXTINF:-1 group-title="公告",更新信息
            https://example.com/notice
        """.trimIndent()
        val result=PlaylistParser.parse(text)
        assertEquals(1,result.size);assertEquals("央视频道",result[0].group);assertEquals(2,result[0].streams.size)
    }
    @Test fun handlesCsvGenreAndDuplicateStreams(){val list=PlaylistParser.parse("文化,#genre#\n测试,http://example.com/live#https://example.com/live2\n测试,http://example.com/live");assertEquals(1,list.size);assertEquals("文化",list[0].group);assertEquals(2,list[0].streams.size)}
    @Test fun handlesJsonChannels(){val list=PlaylistParser.parse("""{"channels":[{"name":"测试","streams":["https://example.com/live","file:///tmp/a"]}]}""");assertEquals(1,list.size);assertEquals(1,list[0].streams.size)}
    @Test fun handlesPls(){assertEquals("频道",PlaylistParser.parse("[playlist]\nFile1=https://example.com/live\nTitle1=频道")[0].name)}
    @Test fun handlesXspfEntities(){val list=PlaylistParser.parse("""<playlist xmlns="http://xspf.org/ns/0/"><trackList><track><title>中文频道</title><location>https://example.com/live?a=1&amp;b=2</location></track></trackList></playlist>""");assertEquals("https://example.com/live?a=1&b=2",list[0].streams[0])}
    @Test fun rejectsExternalEntityAndVideoManifest(){for(text in listOf("<!DOCTYPE playlist [<!ENTITY x SYSTEM 'file:///x'>]><playlist/>","#EXTM3U\n#EXT-X-TARGETDURATION:6\nseg.ts")){try{PlaylistParser.parse(text);fail("must reject")}catch(_:IllegalArgumentException){}}}
    @Test fun onlyWebStreamsWithoutCredentials(){assertNull(PlaylistParser.streamUrl("file:///x"));assertNull(PlaylistParser.streamUrl("https://user:secret@example.com/x"));assertNull(PlaylistParser.streamUrl("javascript:alert(1)"));assertNotNull(PlaylistParser.streamUrl("http://192.168.1.5:8000/live"))}
    @Test fun repositoryHomeDoesNotSelectAThirdPartyPlaylist(){assertEquals("https://github.com/vbskycn/iptv",PlaylistParser.playlistAddress("https://github.com/vbskycn/iptv"))}
    @Test fun convertsGithubBlobAndStableIds(){assertEquals("https://raw.githubusercontent.com/user/repo/main/list.m3u",PlaylistParser.playlistAddress("https://github.com/user/repo/blob/main/list.m3u"));assertEquals(PlaylistParser.parse("测试,https://example.com/1")[0].id,PlaylistParser.parse("测试,https://example.com/2")[0].id)}
}
