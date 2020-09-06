package xyz.angm.terra3d.client.graphics.panels.menu

import com.badlogic.gdx.Gdx
import ktx.actors.onClick
import ktx.actors.plusAssign
import ktx.scene2d.image
import ktx.scene2d.scene2d
import ktx.scene2d.vis.visTable
import ktx.scene2d.vis.visTextButton
import xyz.angm.terra3d.client.graphics.Skin
import xyz.angm.terra3d.client.graphics.panels.Panel
import xyz.angm.terra3d.client.graphics.panels.menu.options.OptionsPanel
import xyz.angm.terra3d.client.graphics.screens.MenuScreen
import xyz.angm.terra3d.client.resources.I18N
import kotlin.system.exitProcess

/** Main menu panel. */
class MainMenuPanel(screen: MenuScreen) : Panel(screen) {

    init {
        reload(screen)
    }

    internal fun reload(screen: MenuScreen) {
        clearChildren()
        this += scene2d.visTable {
            pad(0f, 0f, 100f, 0f)

            image("logo") {
                it.height(232f).width(800f).pad(20f).row()
            }

            visTextButton(I18N["main.singleplayer"]) {
                it.height(Skin.textButtonHeight).width(Skin.textButtonWidth).pad(20f).row()
                onClick { screen.pushPanel(SingleplayerWorldSelectionPanel(screen)) }
            }
            visTextButton(I18N["main.multiplayer"]) {
                it.height(Skin.textButtonHeight).width(Skin.textButtonWidth).pad(20f).row()
                onClick { screen.pushPanel(MultiplayerMenuPanel(screen)) }
            }
            visTextButton(I18N["main.options"]) {
                it.height(Skin.textButtonHeight).width(Skin.textButtonWidth).pad(20f).row()
                onClick { screen.pushPanel(OptionsPanel(screen, this@MainMenuPanel)) }
            }
            visTextButton(I18N["main.exit"]) {
                it.height(Skin.textButtonHeight).width(Skin.textButtonWidth).pad(20f).row()
                onClick {
                    Gdx.app.exit()
                    exitProcess(0)
                }
            }

            setFillParent(true)
        }
    }
}