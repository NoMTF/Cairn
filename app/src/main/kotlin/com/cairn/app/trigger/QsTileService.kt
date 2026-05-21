package com.cairn.app.trigger

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.cairn.app.service.RecordingService

/**
 * 快速设置磁贴 — 下拉面板一点即录。
 *
 * 启动方式之第二种：QS Tile，不解锁也能点。
 */
@RequiresApi(Build.VERSION_CODES.N)
class QsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (RecordingService.isRunning) {
            RecordingService.stop(this)
        } else {
            RecordingService.start(this)
        }

        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        if (RecordingService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "FastLink VPN"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "已连接"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "FastLink VPN"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "点击连接"
            }
        }
        tile.updateTile()
    }
}
