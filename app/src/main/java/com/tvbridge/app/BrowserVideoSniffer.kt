package com.tvbridge.app

import java.net.URI
import java.util.Locale

/**
 * Thread-safe video URL sniffer, classifier, and header sanitizer for [BrowserActivity].
 *
 * Pure JVM logic (no Android framework calls required when [nowMs] is supplied) so that
 * URL matching, segment filtering, priority ranking, and header forwarding can be unit-tested
 * directly.
 */
internal class BrowserVideoSniffer {

    enum class SitePolicy {
        /** Try native ExoPlayer on sniffed/DOM streams first, then fall back to web fullscreen. */
        NATIVE_FIRST,
        /** Skip native sniffing and expand the web player directly (e.g. encrypted MSE cloud drives). */
        WEB_ONLY
    }

    enum class StreamKind(val priority: Int, val label: String) {
        HLS_MASTER(0, "HLS"),
        HLS(1, "HLS"),
        DASH(2, "DASH"),
        VIDEO_DEST(3, "视频"),
        DIRECT_VIDEO(4, "视频")
    }

    private data class SniffedEntry(
        val url: String,
        val kind: StreamKind,
        val headers: Map<String, String>,
        val seenAtMs: Long
    )

    private val entries = LinkedHashMap<String, SniffedEntry>()
    @Volatile var generation: Long = 0L
        private set

    fun clear() {
        synchronized(entries) {
            generation++
            entries.clear()
        }
    }

    fun sitePolicy(pageUrl: String?): SitePolicy {
        val host = hostOf(pageUrl) ?: return SitePolicy.NATIVE_FIRST
        if (WEB_ONLY_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return SitePolicy.WEB_ONLY
        }
        return SitePolicy.NATIVE_FIRST
    }

    /**
     * Evaluates a sub-resource request intercepted by WebView and stores it if it looks
     * like a playable media stream (including extensionless streams tagged with
     * `Sec-Fetch-Dest: video` or media query parameters).
     */
    fun record(
        url: String,
        requestHeaders: Map<String, String>,
        expectedGeneration: Long,
        nowMs: Long
    ): Boolean {
        val normalized = PlaylistParser.httpUrl(url) ?: return false
        val kind = classify(normalized, requestHeaders) ?: return false
        synchronized(entries) {
            if (expectedGeneration != generation) return false
            entries.remove(normalized)
            entries[normalized] = SniffedEntry(
                url = normalized,
                kind = kind,
                headers = HashMap(requestHeaders),
                seenAtMs = nowMs
            )
            while (entries.size > MAX_ENTRIES) {
                entries.remove(entries.keys.first())
            }
        }
        return true
    }

    /**
     * Classifies [url] + [requestHeaders] into a [StreamKind], or returns `null` if it is
     * an ad host, static asset, or media segment (`.ts`, `.m4s`, `seg001.mp4`, etc.).
     */
    fun classify(url: String, requestHeaders: Map<String, String> = emptyMap()): StreamKind? {
        if (PlaylistParser.httpUrl(url) == null) return null
        if (isAdHost(url)) return null

        val path = url.substringBefore('?').substringBefore('#')
        val lowerPath = path.lowercase(Locale.ROOT)
        if (STATIC_ASSET_SUFFIX.containsMatchIn(lowerPath)) return null
        if (SEGMENT_SUFFIX.containsMatchIn(lowerPath)) return null

        val lowerQuery = url.substringAfter('?', "").substringBefore('#').lowercase(Locale.ROOT)

        // 1. Explicit HLS / DASH manifest by extension or query hints
        if (lowerPath.endsWith(".m3u8") || HLS_QUERY_HINT.containsMatchIn(lowerQuery)) {
            return if (isMasterManifest(lowerPath)) StreamKind.HLS_MASTER else StreamKind.HLS
        }
        if (lowerPath.endsWith(".mpd") || DASH_QUERY_HINT.containsMatchIn(lowerQuery)) {
            return StreamKind.DASH
        }

        // 2. Explicit container extensions (excluding numbered/segment fragments)
        if (DIRECT_MEDIA_SUFFIX.containsMatchIn(lowerPath)) {
            if (isSegmentLike(lowerPath)) return null
            return StreamKind.DIRECT_VIDEO
        }

        // 3. Extensionless streams identified by browser fetch metadata or query hints
        val secFetchDest = headerIgnoreCase(requestHeaders, "Sec-Fetch-Dest")?.lowercase(Locale.ROOT)
        val accept = headerIgnoreCase(requestHeaders, "Accept")?.lowercase(Locale.ROOT).orEmpty()
        if (secFetchDest == "video") {
            if (isSegmentLike(lowerPath)) return null
            return StreamKind.VIDEO_DEST
        }
        if (accept.contains("application/vnd.apple.mpegurl") || accept.contains("application/x-mpegurl")) {
            return StreamKind.HLS
        }
        if (accept.contains("application/dash+xml")) {
            return StreamKind.DASH
        }
        if (VIDEO_QUERY_HINT.containsMatchIn(lowerQuery)) {
            if (isSegmentLike(lowerPath)) return null
            return StreamKind.DIRECT_VIDEO
        }
        if (accept.startsWith("video/") && !isSegmentLike(lowerPath)) {
            return StreamKind.VIDEO_DEST
        }

        return null
    }

    /**
     * Merges DOM `<video>` URLs, JS-intercepted XHR/fetch URLs, and WebView-sniffed URLs
     * into a deduplicated, priority-ranked list (up to [MAX_CANDIDATES]).
     */
    fun mergeCandidates(
        domActiveUrl: String?,
        domFallbackUrl: String?,
        jsUrls: List<String>,
        nowMs: Long
    ): List<String> {
        val sniffed = synchronized(entries) {
            val cutoff = nowMs - ENTRY_TTL_MS
            entries.values
                .filter { it.seenAtMs >= cutoff }
                .sortedWith(
                    compareBy<SniffedEntry> { it.kind.priority }
                        .thenByDescending { it.seenAtMs }
                )
                .map { it.url }
        }

        val ordered = LinkedHashSet<String>()

        fun tryAdd(raw: String?) {
            val clean = raw?.let(PlaylistParser::httpUrl) ?: return
            val path = clean.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            if (SEGMENT_SUFFIX.containsMatchIn(path)) return
            if (isAdHost(clean)) return
            ordered.add(clean)
        }

        // 1. Currently playing DOM <video> URL has highest confidence
        tryAdd(domActiveUrl)

        // 2. Sniffed streams sorted by kind priority & recency (master m3u8 -> m3u8 -> mpd -> video dest -> mp4)
        for (url in sniffed) tryAdd(url)

        // 3. JS XHR/fetch captured manifests/videos (prefer manifests first)
        val jsClassified = jsUrls
            .mapNotNull { raw ->
                val clean = PlaylistParser.httpUrl(raw) ?: return@mapNotNull null
                val kind = classify(clean) ?: return@mapNotNull null
                clean to kind
            }
            .sortedBy { it.second.priority }
        for ((url, _) in jsClassified) tryAdd(url)

        // 4. Paused DOM <video> fallback URL
        tryAdd(domFallbackUrl)

        return ordered.take(MAX_CANDIDATES)
    }

    /**
     * Builds sanitized HTTP headers for ExoPlayer by preserving authentication and
     * anti-hotlink headers (`Referer`, `Origin`, `Authorization`, `X-*`, etc.) while
     * stripping hop-by-hop and conflict headers (`Host`, `Range`, `Cookie`, `User-Agent`, etc.).
     */
    fun playbackHeaders(url: String, pageUrl: String?): Map<String, String> {
        val sniffed = synchronized(entries) { entries[url]?.headers ?: emptyMap() }
        val result = LinkedHashMap<String, String>()

        for ((rawKey, rawValue) in sniffed) {
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty()) continue
            val lower = key.lowercase(Locale.ROOT)
            if (lower in STRIPPED_HEADERS || lower.startsWith("sec-")) continue
            result[key] = value
        }

        val existingReferer = headerIgnoreCase(result, "Referer")
        val referer = existingReferer ?: pageUrl?.takeIf { it.isNotBlank() }
        if (existingReferer == null && referer != null) {
            result["Referer"] = referer
        }

        val existingOrigin = headerIgnoreCase(result, "Origin")
        if (existingOrigin == null && referer != null) {
            val refOrigin = originOf(referer)
            if (refOrigin != null && refOrigin != originOf(url)) {
                result["Origin"] = refOrigin
            }
        }

        return result
    }

    fun sourceLabel(url: String, index: Int): String {
        val kind = synchronized(entries) { entries[url]?.kind } ?: classify(url)
        val prefix = kind?.label ?: "视频"
        return "$prefix ${index + 1}"
    }

    fun isAdHost(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return AD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun isSegmentLike(lowerPath: String): Boolean {
        val name = lowerPath.substringAfterLast('/').substringBeforeLast('.')
        if (name.isEmpty()) return false
        if (name.all { it.isDigit() }) return true
        val words = name.split(SEGMENT_SPLIT).filter { it.isNotEmpty() }
        if (words.any { it in SEGMENT_WORDS || SEGMENT_PREFIX.matches(it) }) return true
        val last = words.lastOrNull() ?: return false
        return words.size > 1 && last.length > 1 && last.startsWith("0") && last.all { it.isDigit() }
    }

    private fun isMasterManifest(lowerPath: String): Boolean {
        val file = lowerPath.substringAfterLast('/')
        return file.contains("master") || file.contains("playlist") || file.contains("index")
    }

    private fun headerIgnoreCase(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.ifEmpty { null }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { URI(url.trim()).host?.lowercase(Locale.ROOT) }.getOrNull()
    }

    private fun originOf(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
        return "$scheme://$host$port"
    }

    companion object {
        private const val MAX_ENTRIES = 32
        private const val MAX_CANDIDATES = 6
        private const val ENTRY_TTL_MS = 120_000L

        private val WEB_ONLY_HOSTS = setOf("pan.quark.cn")

        private val AD_HOSTS = setOf(
            "doubleclick.net", "googlesyndication.com", "google-analytics.com",
            "googletagmanager.com", "googleadservices.com", "adservice.google.com",
            "imasdk.googleapis.com", "amazon-adsystem.com", "adnxs.com",
            "scorecardresearch.com", "cpro.baidu.com", "pos.baidu.com",
            "eclick.baidu.com", "hm.baidu.com", "tanx.com", "mmstat.com",
            "pstatp.com", "snssdk.com"
        )

        private val DIRECT_MEDIA_SUFFIX = Regex("\\.(mp4|webm|mov|m4v|mkv|flv)$")
        private val SEGMENT_SUFFIX = Regex("\\.(ts|m4s|cmfv|cmfa|vtt|srt|ass)$")
        private val STATIC_ASSET_SUFFIX = Regex(
            "\\.(js|mjs|css|png|jpg|jpeg|gif|webp|svg|ico|woff|woff2|ttf|otf|eot|html|htm|xml|json|txt|map)$"
        )
        private val HLS_QUERY_HINT = Regex("(?:^|&)(?:mime|type|format|ext)=[^&]*(?:m3u8|mpegurl)")
        private val DASH_QUERY_HINT = Regex("(?:^|&)(?:mime|type|format|ext)=[^&]*(?:mpd|dash)")
        private val VIDEO_QUERY_HINT = Regex("(?:^|&)(?:mime|response-content-type|content-type)=video(?:/|%2f)|(?:^|&)(?:format|ext)=(?:mp4|webm|mkv|flv)(?:&|$)")

        private val SEGMENT_WORDS = setOf("init", "dashinit", "seg", "segment", "chunk", "frag", "fragment")
        private val SEGMENT_PREFIX = Regex("^(init|seg|segment|chunk|frag|fragment)\\d+$")
        private val SEGMENT_SPLIT = Regex("[^a-z0-9]+")

        private val STRIPPED_HEADERS = setOf(
            "host", "connection", "content-length", "accept-encoding",
            "range", "cookie", "user-agent", "upgrade-insecure-requests",
            "pragma", "cache-control", "if-modified-since", "if-none-match"
        )

        /**
         * Idempotent lightweight hook injected on page load to capture media manifest/stream
         * URLs requested via `fetch` or `XMLHttpRequest` (e.g. `hls.js`, `dash.js`, `flv.js`)
         * without exposing a JavascriptInterface bridge.
         */
        const val INJECT_SNIFF_JS = """
            (function(){
              if(window.__oneSniffHooked)return;
              window.__oneSniffHooked=true;
              window.__oneSniffed=[];
              var mediaRe=/\.(m3u8|mpd|mp4|webm|flv|mkv|m4v)(?:[?#]|$)/i;
              function record(u){
                if(typeof u!=='string')return;
                try{u=new URL(u,location.href).href;}catch(e){return;}
                if(!/^https?:\/\//i.test(u)||!mediaRe.test(u))return;
                var list=window.__oneSniffed;
                var idx=list.indexOf(u);
                if(idx>=0)list.splice(idx,1);
                list.push(u);
                if(list.length>24)list.shift();
              }
              if(window.XMLHttpRequest&&XMLHttpRequest.prototype){
                var origOpen=XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open=function(method,url){
                  try{record(url);}catch(e){}
                  return origOpen.apply(this,arguments);
                };
              }
              if(typeof window.fetch==='function'){
                var origFetch=window.fetch;
                window.fetch=function(input){
                  try{record(typeof input==='string'?input:(input&&input.url));}catch(e){}
                  return origFetch.apply(this,arguments);
                };
              }
            })();
        """

        /**
         * Scans `<video>` and `<source>` elements across the main document and up to 3 levels
         * of same-origin `<iframe>`s, returning active URL, fallback URL, JS-captured URLs,
         * and whether an MSE `blob:` video was observed.
         */
        const val DOM_DETECT_JS = """
            (function(){
              var activeUrl='',activeArea=-1,fallbackUrl='',fallbackArea=-1,hasBlob=false,jsUrls=[];
              function pushJs(arr){
                if(!arr||!arr.length)return;
                for(var i=0;i<arr.length;i++){
                  if(typeof arr[i]==='string'&&jsUrls.indexOf(arr[i])<0)jsUrls.push(arr[i]);
                }
              }
              function videoSrc(v){
                var u=v.currentSrc||v.src||'';
                if(!u){
                  var s=v.querySelector('source[src]');
                  if(s)u=s.src||'';
                }
                return u;
              }
              function scan(doc,win,depth){
                if(!doc||depth>3)return;
                try{if(win&&win.__oneSniffed)pushJs(win.__oneSniffed);}catch(e){}
                var videos=doc.querySelectorAll('video');
                for(var i=0;i<videos.length;i++){
                  var v=videos[i];
                  var u=videoSrc(v);
                  if(/^blob:/i.test(u)){hasBlob=true;continue;}
                  if(/^https?:\/\//i.test(u)){
                    var rect=v.getBoundingClientRect?v.getBoundingClientRect():{width:0,height:0};
                    var area=(rect.width||0)*(rect.height||0);
                    if(!v.paused&&v.readyState>=2){
                      if(area>=activeArea){activeUrl=u;activeArea=area;}
                    }else if(area>=fallbackArea){
                      fallbackUrl=u;fallbackArea=area;
                    }
                  }
                }
                var frames=doc.querySelectorAll('iframe');
                for(var j=0;j<frames.length;j++){
                  try{scan(frames[j].contentDocument,frames[j].contentWindow,depth+1);}catch(e){}
                }
              }
              scan(document,window,0);
              return JSON.stringify({activeUrl:activeUrl,fallbackUrl:fallbackUrl,jsUrls:jsUrls,hasBlob:hasBlob});
            })();
        """
    }
}
