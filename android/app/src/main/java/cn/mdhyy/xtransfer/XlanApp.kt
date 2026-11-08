package cn.mdhyy.xtransfer

import android.app.Application
import androidx.room.Room
import cn.mdhyy.xtransfer.data.AppDatabase
import cn.mdhyy.xtransfer.data.BleManager
import cn.mdhyy.xtransfer.data.LanShareServer
import cn.mdhyy.xtransfer.data.LocalHotspot
import cn.mdhyy.xtransfer.data.Notifications
import cn.mdhyy.xtransfer.data.Repository
import cn.mdhyy.xtransfer.data.SettingsStore
import cn.mdhyy.xtransfer.data.WifiDirectManager

class XlanApp : Application() {
    lateinit var repo: Repository
        private set

    lateinit var hotspot: LocalHotspot
        private set

    lateinit var share: LanShareServer
        private set

    lateinit var wifiDirect: WifiDirectManager
        private set

    lateinit var ble: BleManager
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "xtransfer.db").build()
        repo = Repository(this, db, SettingsStore(this))
        hotspot = LocalHotspot(this)
        share = LanShareServer(this, repo)
        wifiDirect = WifiDirectManager(this)
        ble = BleManager(this)
        repo.attachBle(ble)
    }

    companion object {
        @Volatile
        var foreground = false
    }
}
