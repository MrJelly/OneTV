package com.tvbridge.app

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerLifecycleTest {
    @Test fun stoppedChannelCannotDeliverLateUiOrRetryCallbacks(){
        var messages=0
        val player=YangshipinPlayer(RuntimeEnvironment.getApplication(),state={_,_->messages++})
        try{
            YangshipinPlayer::class.java.getDeclaredField("active").apply{isAccessible=true}.setBoolean(player,true)
            YangshipinPlayer::class.java.getDeclaredMethod("notifyState",String::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(player,"old channel",true)
            val recover=YangshipinPlayer::class.java.getDeclaredMethod("recover",Boolean::class.javaPrimitiveType).apply{isAccessible=true}
            recover.invoke(player,true);recover.invoke(player,true)
            assertEquals(1,YangshipinPlayer::class.java.getDeclaredField("retries").apply{isAccessible=true}.getInt(player))
            player.stop();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            assertEquals(0,messages)
            assertNull(YangshipinPlayer::class.java.getDeclaredField("recovery").apply{isAccessible=true}.get(player))
        }finally{player.release()}
    }
}
