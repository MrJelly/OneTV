package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Test
import java.net.*
import javax.net.ssl.*

class LegacyTlsTest {
    @Test fun publicRootBundleRetainsIsrgAndRejectsUntrustedChains(){
        val bundle=java.io.File("src/main/assets/legacy-ca.pem").readBytes()
        val trust=LegacyTls.legacyTrust(bundle).filterIsInstance<X509TrustManager>().single()
        assertTrue(trust.acceptedIssuers.any{it.subjectX500Principal.name.contains("ISRG Root X1")})
        assertTrue(trust.acceptedIssuers.size>100)
        assertThrows(IllegalArgumentException::class.java){trust.checkServerTrusted(emptyArray(),"RSA")}
    }
    @Test fun enablesModernProtocolsWithoutReplacingCiphersOrHostnameVerification(){
        val root=SSLContext.getInstance("TLS").apply{init(null,null,null)}.socketFactory
        val verifier=HttpsURLConnection.getDefaultHostnameVerifier()
        lateinit var underlying:SSLSocket
        val old=object:SSLSocketFactory(){
            override fun getDefaultCipherSuites()=root.defaultCipherSuites
            override fun getSupportedCipherSuites()=root.supportedCipherSuites
            override fun createSocket():Socket=(root.createSocket() as SSLSocket).also{underlying=it;it.enabledProtocols=arrayOf("TLSv1")}
            override fun createSocket(s:Socket,h:String,p:Int,c:Boolean):Socket=error("No networking in this test")
            override fun createSocket(h:String,p:Int):Socket=error("No networking in this test")
            override fun createSocket(h:String,p:Int,l:InetAddress,lp:Int):Socket=error("No networking in this test")
            override fun createSocket(h:InetAddress,p:Int):Socket=error("No networking in this test")
            override fun createSocket(h:InetAddress,p:Int,l:InetAddress,lp:Int):Socket=error("No networking in this test")
        }
        val fixed=LegacyTls.Factory(old)
        (fixed.createSocket() as SSLSocket).use{socket->
            assertSame(underlying,socket)
            assertTrue("TLS 1.2 must be enabled",socket.enabledProtocols.contains("TLSv1.2"))
            assertFalse(socket.enabledProtocols.any{it=="SSLv3"||it=="TLSv1"||it=="TLSv1.1"})
            assertArrayEquals(root.defaultCipherSuites,fixed.defaultCipherSuites)
            assertSame(verifier,HttpsURLConnection.getDefaultHostnameVerifier())
        }
    }
}
