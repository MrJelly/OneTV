package com.tvbridge.app

import java.io.*
import java.nio.*
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import org.junit.Test
import org.junit.Assert.*

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28])
class AdbWireTest {
    @Test fun adbFramesRoundTrip(){val out=ByteArrayOutputStream();AdbWire.write(out,"AUTH",1,0,ByteArray(20){it.toByte()});val packet=AdbWire.read(DataInputStream(out.toByteArray().inputStream()));assertEquals("AUTH",packet.command);assertEquals(1,packet.arg0);assertArrayEquals(ByteArray(20){it.toByte()},packet.data)}
    @Test fun corruptHeaderRejected(){val out=ByteArrayOutputStream();AdbWire.write(out,"CNXN",1,0);val bytes=out.toByteArray();bytes[23]=(bytes[23].toInt() xor 1).toByte();try{AdbWire.read(DataInputStream(bytes.inputStream()));fail()}catch(_:IllegalStateException){}}
    @Test fun adbRsaProofAndPublicKeyFormat(){val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair();val token=ByteArray(20).also{SecureRandom().nextBytes(it)};val signature=AdbWire.sign(pair.private,token);val decoded=Cipher.getInstance("RSA/ECB/PKCS1Padding").run{init(Cipher.DECRYPT_MODE,pair.public);doFinal(signature)};assertArrayEquals(token,decoded.takeLast(20).toByteArray());val data=Base64.getDecoder().decode(AdbWire.publicKey(pair.public as RSAPublicKey));assertEquals(524,data.size);assertEquals(64,ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int)}
}
