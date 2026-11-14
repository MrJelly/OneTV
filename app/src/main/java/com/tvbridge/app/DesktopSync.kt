package com.tvbridge.app

import android.accounts.*
import android.app.Service
import android.content.*
import android.os.*

object DesktopSync {
    const val TYPE="com.tvbridge.app.desktop"
    private const val AUTHORITY="settings"
    fun enabled(context:Context)=DesktopTakeover.unlocked(context)&&context.getSharedPreferences("startup",0).getBoolean("sync_revival",false)
    @Synchronized fun configure(context:Context,enable:Boolean):Boolean {
        if(!DesktopTakeover.unlocked(context)||(enable&&!DesktopTakeover.wanted(context)))return false
        val prefs=context.getSharedPreferences("startup",0)
        if(!enable)prefs.edit().putBoolean("sync_revival",false).apply()
        return runCatching{
            val accounts=AccountManager.get(context)
            val existing=accounts.getAccountsByType(TYPE).firstOrNull()
            if(!enable){
                existing?.let{ContentResolver.setSyncAutomatically(it,AUTHORITY,false);if(Build.VERSION.SDK_INT>=22)accounts.removeAccountExplicitly(it)else accounts.removeAccount(it,null,null)}
                true
            }else{
                val account=existing?:Account("One 桌面恢复",TYPE).also{check(accounts.addAccountExplicitly(it,null,null))}
                ContentResolver.setIsSyncable(account,AUTHORITY,1)
                ContentResolver.setSyncAutomatically(account,AUTHORITY,true)
                prefs.edit().putBoolean("sync_revival",true).apply()
                ContentResolver.getMasterSyncAutomatically()
            }
        }.getOrDefault(false)
    }
}
class DesktopSyncService:Service(){
    private val adapter by lazy{object:AbstractThreadedSyncAdapter(this,false){
        override fun onPerformSync(account:Account,extras:Bundle,authority:String,provider:ContentProviderClient,result:SyncResult){
            if(account.type==DesktopSync.TYPE&&DesktopTakeover.unlocked(this@DesktopSyncService)&&DesktopSync.enabled(this@DesktopSyncService))DesktopRecovery.revived(this@DesktopSyncService)
        }
    }}
    override fun onBind(intent:Intent)=adapter.syncAdapterBinder
}
class DesktopAccountService:Service(){
    private fun unsupported()=Bundle().apply{putInt(AccountManager.KEY_ERROR_CODE,AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);putString(AccountManager.KEY_ERROR_MESSAGE,"请在 One 中设置桌面恢复")}
    private val authenticator by lazy{object:AbstractAccountAuthenticator(this){
        override fun editProperties(response:AccountAuthenticatorResponse,type:String)=unsupported()
        override fun addAccount(response:AccountAuthenticatorResponse,type:String,token:String?,features:Array<out String>?,options:Bundle?)=unsupported()
        override fun confirmCredentials(response:AccountAuthenticatorResponse,account:Account,options:Bundle?)=unsupported()
        override fun getAuthToken(response:AccountAuthenticatorResponse,account:Account,type:String?,options:Bundle?)=unsupported()
        override fun getAuthTokenLabel(type:String?)=""
        override fun updateCredentials(response:AccountAuthenticatorResponse,account:Account,type:String?,options:Bundle?)=unsupported()
        override fun hasFeatures(response:AccountAuthenticatorResponse,account:Account,features:Array<out String>?)=Bundle().apply{putBoolean(AccountManager.KEY_BOOLEAN_RESULT,false)}
    }}
    override fun onBind(intent:Intent)=authenticator.iBinder
}
