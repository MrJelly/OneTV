package com.tvbridge.app

import android.os.Build
import java.net.*
import javax.net.ssl.*
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal object LegacyTls {
    private val factory by lazy{Factory()}
    @Volatile private var installed=false
    private var roots:ByteArray?=null
    @Synchronized fun install(context:android.content.Context){
        if(Build.VERSION.SDK_INT>=21||installed)return
        try{
            roots=runCatching{context.assets.open("legacy-ca.pem").use{it.readBytes()}}.getOrNull()
            HttpsURLConnection.setDefaultSSLSocketFactory(factory);installed=true
        }catch(e:Throwable){
            installed=false
        }
    }
    fun configure(connection:HttpURLConnection){if(Build.VERSION.SDK_INT<21&&connection is HttpsURLConnection)try{connection.sslSocketFactory=factory}catch(_:Throwable){}}
    private fun compatibleFactory():SSLSocketFactory {
        val context=if(Build.VERSION.SDK_INT<21)runCatching{
            val provider=org.conscrypt.Conscrypt.newProviderBuilder().setName("OneLegacyTLS").build()
            java.security.Security.insertProviderAt(provider,1)
            SSLContext.getInstance("TLS",provider)
        }.getOrElse{SSLContext.getInstance("TLS")}else SSLContext.getInstance("TLS")
        return context.apply{init(null,roots?.let{legacyTrust(it)},null)}.socketFactory
    }
    internal fun legacyTrust(bundle:ByteArray):Array<TrustManager> {
        fun manager(store:KeyStore?):X509TrustManager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(store)}.trustManagers.filterIsInstance<X509TrustManager>().first()
        val platform=manager(null)
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null,null)}
        val parser=CertificateFactory.getInstance("X.509")
        val certificates=Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").findAll(bundle.toString(Charsets.US_ASCII)).map{parser.generateCertificate(it.value.byteInputStream(Charsets.US_ASCII))}.toList()
        require(certificates.isNotEmpty()){"缺少公共根证书"}
        certificates.forEachIndexed{i,cert->require((cert as X509Certificate).basicConstraints>=0);store.setCertificateEntry("mozilla-$i",cert)}
        val modern=manager(store)
        return arrayOf(object:X509TrustManager {
            override fun getAcceptedIssuers()=platform.acceptedIssuers+modern.acceptedIssuers
            override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String)=platform.checkClientTrusted(chain,authType)
            override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String){
                try{platform.checkServerTrusted(chain,authType)}catch(_:CertificateException){modern.checkServerTrusted(chain,authType)}
            }
        })
    }
    internal class Factory(private val delegate:SSLSocketFactory=compatibleFactory()):SSLSocketFactory(){
        private fun enabled(socket:java.net.Socket)=socket.also{if(it is SSLSocket){
            val protocols=it.supportedProtocols.filter{v->v=="TLSv1.2"}.toTypedArray()
            if(protocols.isEmpty())throw SSLProtocolException("此设备不支持 TLS 1.2")
            it.enabledProtocols=protocols
        }}
        override fun getDefaultCipherSuites()=delegate.defaultCipherSuites
        override fun getSupportedCipherSuites()=delegate.supportedCipherSuites
        override fun createSocket()=enabled(delegate.createSocket())
        override fun createSocket(s:java.net.Socket,h:String,p:Int,c:Boolean)=enabled(delegate.createSocket(s,h,p,c))
        override fun createSocket(h:String,p:Int)=enabled(delegate.createSocket(h,p))
        override fun createSocket(h:String,p:Int,l:InetAddress,lp:Int)=enabled(delegate.createSocket(h,p,l,lp))
        override fun createSocket(h:InetAddress,p:Int)=enabled(delegate.createSocket(h,p))
        override fun createSocket(h:InetAddress,p:Int,l:InetAddress,lp:Int)=enabled(delegate.createSocket(h,p,l,lp))
    }
    fun message(error:Exception):String=when{
        generateSequence<Throwable>(error){it.cause}.any{it is java.security.cert.CertificateException}->"设备无法验证直播源证书，请检查设备时间及地址证书"
        error is SSLException->"HTTPS 握手失败，请检查设备时间或直播源的 TLS 兼容性"
        error is SocketTimeoutException->"直播源连接超时，已保留原频道"
        error is UnknownHostException->"无法解析直播源地址，请检查网络"
        else->error.message?:"直播源操作失败"
    }
}
