package xyz.angm.terra3d.client.graphics.screens

import com.badlogic.ashley.core.Engine
import com.badlogic.ashley.core.Entity
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.FitViewport
import ktx.ashley.allOf
import ktx.ashley.exclude
import ktx.ashley.get
import xyz.angm.terra3d.client.Terra3D
import xyz.angm.terra3d.client.ecs.components.LocalPlayerComponent
import xyz.angm.terra3d.client.ecs.components.render.ModelRenderComponent
import xyz.angm.terra3d.client.ecs.components.render.PlayerRenderComponent
import xyz.angm.terra3d.client.ecs.systems.*
import xyz.angm.terra3d.client.graphics.panels.Panel
import xyz.angm.terra3d.client.graphics.panels.PanelStack
import xyz.angm.terra3d.client.graphics.panels.game.GameplayOverlay
import xyz.angm.terra3d.client.graphics.panels.menu.MessagePanel
import xyz.angm.terra3d.client.networking.Client
import xyz.angm.terra3d.client.networking.LocalServer
import xyz.angm.terra3d.client.resources.ResourceManager
import xyz.angm.terra3d.client.resources.configuration
import xyz.angm.terra3d.client.resources.soundPlayer
import xyz.angm.terra3d.client.world.World
import xyz.angm.terra3d.common.IntVector3
import xyz.angm.terra3d.common.ecs.*
import xyz.angm.terra3d.common.ecs.components.NetworkSyncComponent
import xyz.angm.terra3d.common.ecs.components.specific.PlayerComponent
import xyz.angm.terra3d.common.ecs.systems.NetworkSystem
import xyz.angm.terra3d.common.ecs.systems.RemoveSystem
import xyz.angm.terra3d.common.log
import xyz.angm.terra3d.common.networking.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The game screen. Active during gameplay. Uses 2 panels; 1 for hotbar and a stack for the other panels required by the Screen interface.
 *
 * This screen is mainly a bag of other objects that make up game state;
 * and should not have any other responsibility other than initializing them and
 * setting up their interactions that drive the game.
 * The only other responsibility of this class is putting together all graphics sources and drawing them.
 *
 * @param client The client for communicating with the server; should be connected already
 * @property world The world
 * @property cam The player's camera
 *
 * @property engine The ECS engine used
 * @property player The player controlled by this game instance
 * @property playerInputSystem The input system active in the ECS
 * @property playerInventory A simpler way of getting the players inventory
 *
 * @property gameplayPanel The panel always active; responsible for drawing hotbar and other elements
 * @property scheduler A scheduler for usage in the game */
class GameScreen(
    private val game: Terra3D,
    val client: Client
) : ScreenAdapter(), Screen {

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // 3D Graphics
    val world = World(client)
    val cam = PerspectiveCamera(75f, WORLD_WIDTH, WORLD_HEIGHT)
    private val inputHandler = PlayerInputHandler(this)
    private val modelBatch = ModelBatch()
    private val environment = Environment()
    private val renderableEntities = allOf(ModelRenderComponent::class).get()

    // Entities
    private val engine = Engine()
    val player = createLocalPlayer()
    val playerInputSystem: PlayerInputSystem get() = engine.getSystem(PlayerInputSystem::class.java)
    val playerInventory get() = player[playerM]!!.inventory

    // 2D Graphics
    private val stage = Stage(FitViewport(WORLD_WIDTH, WORLD_HEIGHT))
    private val uiPanels = PanelStack()
    val gameplayPanel = GameplayOverlay(this)

    override fun show() {
        initState()
        initRender()
    }

    override fun render(delta: Float) {
        if (cam.far != 300f) {
            log.error { "????" }
            return
        }

        Gdx.gl.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        world.update()
        engine.update(delta)

        modelBatch.begin(cam)
        world.render(modelBatch, cam, environment)
        player[playerRender]!!.render(modelBatch, environment)
        engine.getEntitiesFor(renderableEntities).forEach { it[modelRender]!!.render(modelBatch, environment) }
        modelBatch.end()

        stage.act()
        stage.draw()
    }

    override fun pushPanel(panel: Panel) {
        if (uiPanels.panelsInStack == 0) { // First UI panel, switch gameplay > ui for input
            inputHandler.beforeUnregister()
            Gdx.input.inputProcessor = stage
            Gdx.input.isCursorCatched = false
            Gdx.input.setCursorPosition(stage.viewport.screenWidth / 2, (stage.viewport.screenY + stage.viewport.screenHeight) / 2)
        }
        uiPanels.pushPanel(panel)
    }

    override fun popPanel() {
        if (uiPanels.panelsInStack == 1) { // About to pop last panel, return to gameplay
            inputHandler.beforeRegister()
            Gdx.input.inputProcessor = inputHandler
            Gdx.input.isCursorCatched = true
        }
        uiPanels.popPanel()
    }

    fun popAllPanels() {
        while (uiPanels.panelsInStack != 0) popPanel()
    }

    /** Called when the game can no longer continue (disconnect; player quit; etc.)
     * Returns to the menu screen.
     * @param message The message to display. Defaults to no message which will return to menu screen immediately. */
    fun returnToMenu(message: String? = null) {
        client.send(player) // Make sure the player is up-to-date on the server
        client.disconnectListener = {} // Prevent it from showing the 'disconnected' message when it shouldn't
        dispose()
        game.screen = MenuScreen(game)
        (game.screen as Screen).pushPanel(MessagePanel(game.screen as Screen, message ?: return) {
            (game.screen as Screen).popPanel()
        })
    }

    // Initialize everything not render-related
    private fun initState() {
        // Engine
        engine.addSystem(PlayerSystem(this, player))
        engine.addSystem(PlayerPhysicsSystem(world::blockExists, player))
        engine.addSystem(PlayerInputSystem(this, player, engine.getSystem(PlayerPhysicsSystem::class.java), inputHandler))
        engine.addSystem(RemoveSystem())
        val netSystem = NetworkSystem(client::send)
        val renderSystem = RenderSystem()
        engine.addSystem(renderSystem)
        engine.addEntityListener(exclude(LocalPlayerComponent::class).get(), 1, renderSystem)
        engine.addSystem(netSystem)
        engine.addEntityListener(allOf(NetworkSyncComponent::class).get(), netSystem)


        // Network
        client.disconnectListener = { Gdx.app.postRunnable { returnToMenu("Disconnected from server.") } }
        client.addListener {
            when (it) {
                is Entity -> netSystem.receive(it)
                is EntitiesPacket -> it.entities.forEach { netSystem.receive(it) }
            }
        }
        client.send(ChatMessagePacket("[CYAN]${player[playerM]!!.name}[LIGHT_GRAY] joined the game!"))
        client.send(JoinPacket(configuration.playerName, configuration.clientUUID, player[network]!!.id))


        // Input
        Gdx.input.inputProcessor = inputHandler
        Gdx.input.isCursorCatched = true


        // Chunk loading
        // Go ahead and immediately request the chunk the player is spawning in
        client.send(ChunkRequest(IntVector3(player[position]!!)))
        scheduler.scheduleAtFixedRate({ world.updateLoadedChunks(IntVector3(player[position]!!)) }, 0, 1, TimeUnit.SECONDS)
    }

    // Creates the local player. See the player variable at the top of this file.
    private fun createLocalPlayer(): Entity {
        val player = PlayerComponent.create(engine, configuration.playerName, configuration.clientUUID)
        player.add(LocalPlayerComponent())
        player.add(PlayerRenderComponent())
        player[network]!!.needsSync = false // Prevent player on server being overwritten
        return player
    }

    // Initialize all rendering components
    private fun initRender() {
        // Initialize model cache to ensure loading of required models
        ResourceManager.models.init()

        // Camera
        cam.near = 0.1f
        cam.far = 300f
        cam.update()

        // 2D / Stage
        stage.addActor(gameplayPanel)
        stage.addActor(uiPanels)

        // Lighting
        // TODO sun and moon cycle
        environment.set(ColorAttribute(ColorAttribute.AmbientLight, 0.4f, 0.4f, 0.4f, 1f))
        environment.add(DirectionalLight().set(0.8f, 0.8f, 0.8f, -1f, -0.8f, -0.2f))

        // Sound
        client.addListener {
            if (it is BlockUpdate) {
                // TODO find block type on block break without tightly coupling to world
                val sound = it.properties?.block?.placedSound
                if (sound != null) soundPlayer.playSound3D(sound, it.position.toV3())
            }
        }
    }

    override fun resize(width: Int, height: Int) = stage.viewport.update(width, height, true)

    /** hide is called when the screen is no longer active, at which point this type of screen becomes dereferenced and needs to be disposed. */
    override fun hide() = dispose()

    override fun dispose() {
        LocalServer.stop()
        client.close()
        modelBatch.dispose()
        gameplayPanel.dispose()
        uiPanels.dispose()
        scheduler.shutdown()
    }
}