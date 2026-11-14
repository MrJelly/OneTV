package com.tvbridge.app

import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object YangshipinApiPayload {
    const val APP_ID = "519748109"
    private const val APP_VERSION = "V1.0.0"
    private const val PLATFORM = "5910204"
    private const val AUTH_SALT = "n@7QKk%YeSjfw%22"
    private const val LIVE_SALT = "0f\$IVHi9Qno?G"
    private val CKEY_KEY = hexBytes("48e5918a74ae21c972b90cce8af6c8be")
    private val CKEY_IV = hexBytes("9a7e7d23610266b1d9fbf98581384d92")
    private val RANDOM_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()
    private val RANDOM = SecureRandom()

    val PID_TO_STREAM_ID = mapOf(
        "600001859" to "2024078201",
        "600001800" to "2024075401",
        "600001801" to "2024068501",
        "600001814" to "2029797101",
        "600001818" to "2024078401",
        "600108442" to "2013693901",
        "600004092" to "2024072001",
        "600000492" to "2024072001",
        "600001803" to "2029793001",
        "600004078" to "2024078601",
        "600001805" to "2024078701",
        "600001806" to "2027248701",
        "600001807" to "2027248801",
        "600001811" to "2029797201",
        "600001809" to "2027248901",
        "600001815" to "2027249001",
        "600098637" to "2027249101",
        "600099502" to "2027249301",
        "600001817" to "2024078001",
        "600001810" to "2027249401",
        "600002264" to "2029810301",
        "600156816" to "2026774101",
        "600014550" to "2024181701",
        "600084704" to "2024181801",
        "600084758" to "2024181901",
        "600084782" to "2024182001",
        "600084744" to "2024182101",
        "600084781" to "2024182201",
        "600099655" to "2026874203",
        "600099620" to "2026874303",
        "600002309" to "2024052703",
        "600002521" to "2024171103",
        "600002483" to "2024054503",
        "600002520" to "2024054703",
        "600002475" to "2024054803",
        "600002508" to "2024171203",
        "600002485" to "2024060903",
        "600002509" to "2024060703",
        "600002498" to "2029797003",
        "600002506" to "2024055603",
        "600002531" to "2024061103",
        "600002481" to "2024061303",
        "600002516" to "2024061403",
        "600002525" to "2029797303",
        "600002484" to "2024061503",
        "600002490" to "2024061603",
        "600002503" to "2024061703",
        "600002505" to "2024171303",
        "600002532" to "2024171403",
        "600002493" to "2024171503",
        "600002513" to "2029787903",
        "600152137" to "2019927003",
        "600190405" to "2025561503",
        "600190400" to "2029795103",
        "600190408" to "2025561703",
        "600190737" to "2025608503",
        "600190401" to "2025561203",
        "600190402" to "2025561303",
        "600190407" to "2025560803",
        "600190406" to "2025559103",
        "600190403" to "2025558003",
        "600171827" to "2022823801",
        "600152138" to "2019927403"
    )

    data class Payload(
        val guid: String,
        val ticketRandom: String,
        val requestId: String,
        val liveRandom: String,
        val authBody: String,
        val liveBody: String,
        val streamId: String
    )

    fun create(pid: String, streamIdOverride: String? = null, serverTimeMs: Long = System.currentTimeMillis(), definition: String = "fhd"): Payload {
        val streamId = streamIdOverride ?: PID_TO_STREAM_ID[pid] ?: ""
        val guid = createGuid(serverTimeMs)
        val authRandom = randomText(10)
        val liveRandom = randomText(10)
        val ticketRandom = randomText(10)

        val auth = TreeMap<String, String>().apply {
            put("appid", "ysp_pc")
            put("guid", guid)
            put("pid", pid)
            put("rand_str", authRandom)
        }
        val authSignature = md5(joinForSignature(auth) + AUTH_SALT)
        val authBody = "pid=" + encode(pid) +
                "&guid=" + encode(guid) +
                "&appid=ysp_pc" +
                "&rand_str=" + encode(authRandom) +
                "&signature=" + authSignature

        val liveBody = createLiveBody(pid, streamId, guid, liveRandom, serverTimeMs / 1000L, definition)
        val requestId = "999999" + randomText(10) + serverTimeMs
        return Payload(guid, ticketRandom, requestId, liveRandom, authBody, liveBody, streamId)
    }

    fun createLiveBody(pid: String, streamId: String, guid: String, liveRandom: String, serverSeconds: Long, definition: String = "fhd"): String {
        val live = TreeMap<String, String>().apply {
            put("cnlid", streamId)
            put("livepid", pid)
            put("stream", "2")
            put("guid", guid)
            put("cKey", createCKey(streamId, guid, serverSeconds))
            put("adjust", "1")
            put("sphttps", "1")
            put("platform", PLATFORM)
            put("cmd", "2")
            put("encryptVer", "8.1")
            put("dtype", "1")
            put("devid", "devid")
            put("otype", "ojson")
            put("appVer", APP_VERSION)
            put("app_version", APP_VERSION)
            put("channel", "ysp_tx")
            put("defn", if (definition in listOf("shd", "hd", "sd")) definition else "fhd")
            put("rand_str", liveRandom)
        }
        val liveSignature = md5(joinForSignature(live) + LIVE_SALT)
        val json = JSONObject()
        for ((k, v) in live) {
            json.put(k, v)
        }
        json.put("adjust", 1)
        json.put("signature", liveSignature)
        return json.toString()
    }

    fun computeTicket(pid: String, serverSeconds: Long, guid: String, ticketRandom: String): String {
        val p = "$pid&$serverSeconds&$guid&$APP_ID&$ticketRandom"
        val k = "6a70e9ac007ccc384c28a88dfd2211decb66494bca83bd6178f85732b2eacc9594e4e5ab720ec3a43e87b3c72b86192ff0dfea8fa8f47794d8792668438c"
        val out = StringBuilder()
        for (i in p.indices) {
            val keyByte = k.substring(i * 2, i * 2 + 2).toInt(16)
            val xored = p[i].code xor keyByte
            if (xored < 16) out.append('0')
            out.append(xored.toString(16))
        }
        return out.toString()
    }

    fun createSdkInput(liveBody: String): String {
        val json = JSONObject(liveBody)
        val order = arrayOf(
            "adjust", "app_version", "appVer", "channel", "cKey", "cmd",
            "cnlid", "defn", "devid", "dtype", "encryptVer", "guid",
            "livepid", "otype", "platform", "sphttps", "stream"
        )
        val sb = StringBuilder()
        for (i in order.indices) {
            if (i > 0) sb.append('&')
            sb.append(order[i]).append('=').append(json.optString(order[i], ""))
        }
        return md5(sb.toString())
    }

    fun createCKey(channelId: String, guid: String, seconds: Long): String {
        val base = "|$channelId|$seconds|mg3c3b04ba|$APP_VERSION|$guid|$PLATFORM|https://www.yangshipin.c|mozilla/5.0 (windows nt ||Mozilla|Netscape|Win32|"
        var hash = 0
        for (i in 0 until base.length) {
            hash = hash * 31 + base[i].code
        }
        val plain = ("|$hash$base").toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(CKEY_KEY, "AES"), IvParameterSpec(CKEY_IV))
        return "--01" + toHex(cipher.doFinal(plain), true)
    }

    private fun createGuid(timeMs: Long): String {
        var prefix = java.lang.Long.toString(timeMs, 36)
        if (prefix.length > 8) {
            prefix = prefix.substring(prefix.length - 8)
        }
        val value = StringBuilder(20).append(prefix).append('_')
        while (value.length < 20) {
            value.append(RANDOM_CHARS[RANDOM.nextInt(36)])
        }
        return value.substring(0, 20)
    }

    private fun randomText(length: Int): String {
        val value = StringBuilder(length)
        for (i in 0 until length) {
            value.append(RANDOM_CHARS[RANDOM.nextInt(RANDOM_CHARS.size)])
        }
        return value.toString()
    }

    private fun joinForSignature(values: TreeMap<String, String>): String {
        val joined = StringBuilder()
        for ((k, v) in values) {
            if (joined.isNotEmpty()) joined.append('&')
            joined.append(k).append('=').append(v)
        }
        return joined.toString()
    }

    fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return toHex(digest.digest(text.toByteArray(Charsets.UTF_8)), false)
    }

    fun encode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: UnsupportedEncodingException) {
            value
        }
    }

    private fun hexBytes(text: String): ByteArray {
        val output = ByteArray(text.length / 2)
        for (i in output.indices) {
            output[i] = text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return output
    }

    private fun toHex(bytes: ByteArray, upperCase: Boolean): String {
        val digits = (if (upperCase) "0123456789ABCDEF" else "0123456789abcdef").toCharArray()
        val output = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            output[i * 2] = digits[v ushr 4]
            output[i * 2 + 1] = digits[v and 0x0f]
        }
        return String(output)
    }
}
