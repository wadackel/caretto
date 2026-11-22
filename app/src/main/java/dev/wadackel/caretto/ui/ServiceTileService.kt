package dev.wadackel.caretto.ui

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.wadackel.caretto.R

/**
 * Quick settings tile that switches the accessibility service on and off.
 *
 * It exists for apps that refuse to run while an unlisted accessibility service is enabled
 * (PayPay, docs/findings.md F19): the service has to be off for as long as such an app is open,
 * and going through accessibility settings each time is the cost this removes.
 */
class ServiceTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        // Switching on a service that reads every key is not something to allow past the lock
        // screen. Switching it off only takes capability away, so that is left one tap.
        if (isLocked && !isServiceEnabledInSettings(this)) unlockAndRun(::toggle) else toggle()
    }

    private fun toggle() {
        if (!canSwitchService(this)) return
        setServiceEnabledInSettings(this, !isServiceEnabledInSettings(this))
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val canSwitch = canSwitchService(this)
        tile.state = when {
            !canSwitch -> Tile.STATE_UNAVAILABLE
            isServiceEnabledInSettings(this) -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = if (canSwitch) null else getString(R.string.tile_needs_grant)
        tile.updateTile()
    }
}
