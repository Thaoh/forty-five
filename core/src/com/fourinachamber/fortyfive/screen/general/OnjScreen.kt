package com.fourinachamber.fortyfive.screen.general

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Cursor
import com.badlogic.gdx.graphics.g2d.*
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Null
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.TimeUtils
import com.badlogic.gdx.utils.viewport.Viewport
import com.fourinachamber.fortyfive.game.GraphicsConfig
import com.fourinachamber.fortyfive.keyInput.KeyInputMap
import com.fourinachamber.fortyfive.keyInput.KeySelectionHierarchyBuilder
import com.fourinachamber.fortyfive.keyInput.KeySelectionHierarchyNode
import com.fourinachamber.fortyfive.onjNamespaces.OnjColor
import com.fourinachamber.fortyfive.rendering.Renderable
import com.fourinachamber.fortyfive.screen.ResourceBorrower
import com.fourinachamber.fortyfive.screen.ResourceHandle
import com.fourinachamber.fortyfive.screen.ResourceManager
import com.fourinachamber.fortyfive.screen.general.customActor.DisplayDetailsOnHoverActor
import com.fourinachamber.fortyfive.screen.general.customActor.HoverStateActor
import com.fourinachamber.fortyfive.screen.general.customActor.KeySelectableActor
import com.fourinachamber.fortyfive.screen.general.customActor.ZIndexActor
import com.fourinachamber.fortyfive.screen.general.styles.StyleManager
import com.fourinachamber.fortyfive.screen.general.styles.StyledActor
import com.fourinachamber.fortyfive.utils.*
import dev.lyze.flexbox.FlexBox
import ktx.actors.onEnter
import ktx.actors.onExit
import onj.value.*
import kotlin.system.measureTimeMillis


/**
 * a screen that was build from an onj file.
 */
open class OnjScreen @MainThreadOnly constructor(
    val viewport: Viewport,
    batch: Batch,
    private val background: String?,
    private val controllerContext: Any?,
    private val useAssets: MutableList<String>,
    private val earlyRenderTasks: List<OnjScreen.() -> Unit>,
    private val lateRenderTasks: List<OnjScreen.() -> Unit>,
    styleManagers: List<StyleManager>,
    private val namedCells: Map<String, Cell<*>>,
    private val namedActors: MutableMap<String, Actor>,
    private val printFrameRate: Boolean,
    val transitionAwayTime: Int?,
    val screenBuilder: ScreenBuilder,
) : ScreenAdapter(), Renderable, ResourceBorrower {

    var styleManagers: MutableList<StyleManager> = styleManagers.toMutableList()
        private set

    var dragAndDrop: Map<String, DragAndDrop> = mapOf()

    var lastRenderTime: Long = 0
        private set

    private val createTime: Long = TimeUtils.millis()
    private val callbacks: MutableList<Pair<Long, () -> Unit>> = mutableListOf()
    private val additionalDisposables: MutableList<Disposable> = mutableListOf()

    private val additionalLateRenderTasks: MutableList<(Batch) -> Unit> = mutableListOf()
    private val additionalEarlyRenderTasks: MutableList<(Batch) -> Unit> = mutableListOf()

    var isVisible: Boolean = false
        private set

    var defaultCursor: Either<Cursor, Cursor.SystemCursor> = Cursor.SystemCursor.Arrow.eitherRight()
        @MainThreadOnly set(value) {
            field = value
            Utils.setCursor(value)
        }

    private val _screenState: MutableSet<String> = mutableSetOf()
    val screenState: Set<String>
        get() = _screenState

    private val screenStateChangeListeners: MutableList<(entered: Boolean, state: String) -> Unit> = mutableListOf()

    val stage: Stage = Stage(viewport, batch)

    var screenController: ScreenController? = null
        @MainThreadOnly set(value) {
            field?.end()
            field = value
            if (isVisible) value?.init(this, controllerContext)
        }

    var selectedActor: KeySelectableActor? = null
        @AllThreadsAllowed set(value) {
            field?.isSelected = false
            field = value
            field?.isSelected = true
            selectedNode = null
        }

    var selectedNode: KeySelectionHierarchyNode? = null
        @AllThreadsAllowed set(value) {
            if (!(value?.isSelectable ?: true)) {
                throw RuntimeException("only a node that is selectable can be assigned to 'selectedNode'")
            }
            value?.actor?.let { selectedActor = it as KeySelectableActor }
            field = value
        }

    private var awaitingConfirmationClick: Boolean = false

    private var screenInputProcessor: InputProcessor = object : InputAdapter() {

        override fun keyDown(keycode: Int): Boolean {
            if (!awaitingConfirmationClick) return false
            if (keycode in arrayOf(Keys.ENTER, Keys.NUMPAD_ENTER)) {
                awaitingConfirmationClick = false
                return true
            }
            return false
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (!awaitingConfirmationClick) return false
            awaitingConfirmationClick = false
            return true
        }

    }

    var inputMap: KeyInputMap? = null
        @AllThreadsAllowed set(value) {
            field = value
            inputMultiplexer.clear()
            inputMultiplexer.addProcessor(screenInputProcessor)
            value?.let { inputMultiplexer.addProcessor(it) }
            inputMultiplexer.addProcessor(stage)
        }

    @MainThreadOnly
    private val keySelectDrawable: Drawable by lazy {
        GraphicsConfig.keySelectDrawable(this)
    }

    private val backgroundDrawable: Drawable? by lazy {
        background?.let { ResourceManager.get<Drawable>(this, it) }
    }

    private var inputMultiplexer: InputMultiplexer = InputMultiplexer()

    var keySelectionHierarchy: KeySelectionHierarchyNode? = null
        private set

    var currentHoverDetail: Actor? = null
        private set

    var currentDisplayDetailActor: DisplayDetailsOnHoverActor? = null
        private set

    init {
        useAssets.forEach {
            ResourceManager.borrow(this, it)
        }
        addEarlyRenderTask {
            val drawable = backgroundDrawable ?: return@addEarlyRenderTask
            drawable.draw(it, 0f, 0f, stage.viewport.worldWidth, stage.viewport.worldHeight)
        }
        addLateRenderTask {
            val highlight = selectedActor?.getBounds() ?: return@addLateRenderTask
            keySelectDrawable.draw(it, highlight.x, highlight.y, highlight.width, highlight.height)
        }
        inputMultiplexer.addProcessor(screenInputProcessor)
        inputMultiplexer.addProcessor(stage)
    }

    @AllThreadsAllowed
    fun afterMs(ms: Int, callback: @MainThreadOnly () -> Unit) {
        callbacks.add((TimeUtils.millis() + ms) to callback)
    }

    @AllThreadsAllowed
    fun addDisposable(disposable: Disposable) {
        additionalDisposables.add(disposable)
    }

    @AllThreadsAllowed
    fun addActorToRoot(actor: Actor) {
        stage.root.addActor(actor)
    }

    @AllThreadsAllowed
    fun invalidateEverything() {

        fun invalidateGroup(group: Group) {
            for (child in group.children) {
                if (child is Layout) {
                    child.invalidate()
                }
                if (child is Group) invalidateGroup(child)
            }
        }

        invalidateGroup(stage.root)
    }

    @AllThreadsAllowed
    fun removeActorFromRoot(actor: Actor) {
        stage.root.removeActor(actor)
    }

    @AllThreadsAllowed
    fun resortRootZIndices() {
        stage.root.children.sort { el1, el2 ->
            (if (el1 is ZIndexActor) el1.fixedZIndex else -1) -
                    (if (el2 is ZIndexActor) el2.fixedZIndex else -1)
        }
    }

    @AllThreadsAllowed
    fun enterState(state: String) {
        if (state in _screenState) return
        _screenState.add(state)
        screenStateChangeListeners.forEach { it(true, state) }
    }

    @AllThreadsAllowed
    fun leaveState(state: String) {
        if (state !in _screenState) return
        _screenState.remove(state)
        screenStateChangeListeners.forEach { it(false, state) }
    }

    @AllThreadsAllowed
    fun addScreenStateChangeListener(listener: @AllThreadsAllowed (entered: Boolean, state: String) -> Unit) {
        screenStateChangeListeners.add(listener)
    }

    @AllThreadsAllowed
    fun buildKeySelectHierarchy() {
        keySelectionHierarchy = KeySelectionHierarchyBuilder().build(stage.root)
    }

    @AllThreadsAllowed
    fun addLateRenderTask(task: @MainThreadOnly (Batch) -> Unit): Unit = run { additionalLateRenderTasks.add(task) }

    @AllThreadsAllowed
    fun addEarlyRenderTask(task: @MainThreadOnly (Batch) -> Unit): Unit = run { additionalEarlyRenderTasks.add(task) }

    @AllThreadsAllowed
    fun removeLateRenderTask(task: @MainThreadOnly (Batch) -> Unit) {
        additionalLateRenderTasks.remove(task)
    }

    @AllThreadsAllowed
    fun removeEarlyRenderTask(task: @MainThreadOnly (Batch) -> Unit) {
        additionalEarlyRenderTasks.remove(task)
    }

    fun addNamedActor(name: String, actor: Actor) {
        namedActors[name] = actor
    }

    fun removeNamedActor(name: String) {
        namedActors.remove(name)
    }

    fun <T> addOnHoverDetailActor(actor: T) where T : Actor, T : DisplayDetailsOnHoverActor {
        if (actor is HoverStateActor) {
            actor.onHoverEnter {
                if (dragAndDrop.none { it.value.isDragging }) { //TODO MARVIN has to add that this if works in a fight too
                    showHoverDetail(actor, actor, actor.actorTemplate)
                }
            }
            actor.onHoverLeave {
                hideHoverDetail()
            }
        } else {
            actor.onEnter {
                showHoverDetail(actor, actor, actor.actorTemplate)
            }
            actor.onExit {
                hideHoverDetail()
            }
        }

    }

    private fun showHoverDetail(actor: Actor, displayDetailActor: DisplayDetailsOnHoverActor, detailTemplate: String) {
        if (currentHoverDetail != null) hideHoverDetail()
        val detail = screenBuilder.generateFromTemplate(
            detailTemplate,
            displayDetailActor.getHoverDetailData(),
            null,
            this
        )!!
        displayDetailActor.detailActor = detail
        displayDetailActor.setBoundsOfHoverDetailActor(actor)
        currentHoverDetail = detail
        currentDisplayDetailActor = displayDetailActor
        displayDetailActor.onDetailDisplayStarted()

        leaveState("showHoverDetail")
        afterMs(20) {
            if (currentHoverDetail === detail)
                enterState("showHoverDetail")
        }
        // actor only appear on fade, same with dialog // bis morgen abend hoffentlich
        // events eintragen für roads und so weiter (laut notion)
        // Buch schreiben
    }

    private fun hideHoverDetail() {
        currentHoverDetail = null
        currentDisplayDetailActor?.detailActor = null
        currentDisplayDetailActor?.onDetailDisplayEnded()
    }

    @AllThreadsAllowed
    fun namedActorOrError(name: String): Actor = namedActors[name] ?: throw RuntimeException(
        "no actor named $name"
    )

    private fun updateCallbacks() {
        val curTime = TimeUtils.millis()
        val iterator = callbacks.iterator()
        while (iterator.hasNext()) {
            val (time, callback) = iterator.next()
            if (time <= curTime) {
                callback()
                iterator.remove()
            }
        }
    }

    @MainThreadOnly
    override fun show() {
        screenController?.init(this, controllerContext)
        Gdx.input.inputProcessor = inputMultiplexer
        Utils.setCursor(defaultCursor)
        isVisible = true
    }

    fun borrowResource(handle: ResourceHandle) {
        useAssets.add(handle)
        ResourceManager.borrow(this, handle)
    }

    fun transitionAway() {
        screenController = null
        inputMap = null
        enterState(transitionAwayScreenState)
    }

    @MainThreadOnly
    override fun hide() {
        super.hide()
        isVisible = false
    }

    fun swapStyleManager(
        old: StyleManager,
        new: StyleManager
    ) { // TODO: this whole swapping stylemanager thing is kinda ugly
        styleManagers = styleManagers.map { if (it === old) new else it }.toMutableList()
    }

    fun addStyleManager(manager: StyleManager) {
        styleManagers.add(manager)
    }

    @MainThreadOnly
    override fun render(delta: Float) = try {
//        Thread.sleep(800) //TODO remove // (please don't, its great to find this method)
        styleManagers.forEach(StyleManager::update)
        if (printFrameRate) FortyFiveLogger.fps()
        screenController?.update()
        updateCallbacks()
        lastRenderTime = measureTimeMillis {
            val oldStyleManagers = styleManagers.toList()
            stage.act(Gdx.graphics.deltaTime)
            styleManagers.filter { it !in oldStyleManagers }
                .forEach(StyleManager::update) //all added items get updated too
            ScreenUtils.clear(0.0f, 0.0f, 0.0f, 1.0f)
            if (stage.batch.isDrawing) stage.batch.end()
            doRenderTasks(earlyRenderTasks, additionalEarlyRenderTasks)
            stage.draw()
            doRenderTasks(lateRenderTasks, additionalLateRenderTasks)

            if (dragAndDrop.none { it.value.isDragging }) {
                stage.batch.begin()
                currentHoverDetail?.let { actor ->
                    actor.act(delta)
                    actor.draw(stage.batch, 1f)
                }
                stage.batch.end()
            }
        }
    } catch (e: Exception) {
        FortyFiveLogger.fatal(e)
    }

    private fun doRenderTasks(tasks: List<OnjScreen.() -> Unit>, additionalTasks: MutableList<(Batch) -> Unit>) {
        stage.batch.begin()
        tasks.forEach { it(this) }
        additionalTasks.forEach { it(stage.batch) }
        stage.batch.end()
    }

    @MainThreadOnly
    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    fun confirmationClickTimelineAction(maxTime: Long? = null): Timeline.TimelineAction =
        object : Timeline.TimelineAction() {

            private var finishAt: Long? = null

            override fun start(timeline: Timeline) {
                super.start(timeline)
                maxTime?.let {
                    finishAt = TimeUtils.millis() + it
                }
                awaitingConfirmationClick = true
            }

            override fun isFinished(timeline: Timeline): Boolean {
                if (!awaitingConfirmationClick) return true
                finishAt?.let {
                    if (TimeUtils.millis() >= it) return true
                }
                return false
            }

            override fun end(timeline: Timeline) {
                awaitingConfirmationClick = false
            }
        }

    @MainThreadOnly
    override fun dispose() {
        hide()
        screenController?.end()
        stage.dispose()
//        toDispose.forEach(Disposable::dispose)
        useAssets.forEach {
            ResourceManager.giveBack(this, it)
        }
        additionalDisposables.forEach(Disposable::dispose)
    }

    companion object {

        const val logTag = "screen"

        const val transitionAwayScreenState = "transition away"
    }

}
