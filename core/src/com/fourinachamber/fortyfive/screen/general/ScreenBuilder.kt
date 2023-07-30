package com.fourinachamber.fortyfive.screen.general

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.badlogic.gdx.utils.viewport.FitViewport
import com.badlogic.gdx.utils.viewport.Viewport
import com.fourinachamber.fortyfive.keyInput.KeyInputMap
import com.fourinachamber.fortyfive.map.MapManager
import com.fourinachamber.fortyfive.map.detailMap.DetailMapWidget
import com.fourinachamber.fortyfive.map.dialog.DialogWidget
import com.fourinachamber.fortyfive.map.shop.PersonWidget
import com.fourinachamber.fortyfive.map.statusbar.Backpack
import com.fourinachamber.fortyfive.map.statusbar.StatusbarWidget
import com.fourinachamber.fortyfive.map.worldView.WorldViewWidget
import com.fourinachamber.fortyfive.screen.ResourceManager
import com.fourinachamber.fortyfive.screen.gameComponents.CardHand
import com.fourinachamber.fortyfive.screen.gameComponents.CircularCardSelector
import com.fourinachamber.fortyfive.screen.gameComponents.EnemyArea
import com.fourinachamber.fortyfive.screen.gameComponents.Revolver
import com.fourinachamber.fortyfive.screen.general.styles.*
import com.fourinachamber.fortyfive.utils.*
import dev.lyze.flexbox.FlexBox
import io.github.orioncraftmc.meditate.enums.YogaEdge
import ktx.actors.onClick
import onj.parser.OnjParser
import onj.parser.OnjSchemaParser
import onj.schema.OnjSchema
import onj.value.*

class ScreenBuilder(val file: FileHandle) {

    private var borrowed: List<String> = listOf()

    private val earlyRenderTasks: MutableList<OnjScreen.() -> Unit> = mutableListOf()
    private val lateRenderTasks: MutableList<OnjScreen.() -> Unit> = mutableListOf()

    private val behavioursToBind: MutableList<Behaviour> = mutableListOf()
    private var actorsWithDragAndDrop: MutableMap<String, MutableList<Pair<Actor, OnjNamedObject>>> = mutableMapOf()

    private val namedActors: MutableMap<String, Actor> = mutableMapOf()

    private var screenController: ScreenController? = null
    private var background: String? = null
    private var transitionAwayTime: Int? = null
    private val templateObjects: MutableMap<String, OnjNamedObject> = mutableMapOf()


    @MainThreadOnly
    fun build(controllerContext: Any? = null): OnjScreen {
        val onj = OnjParser.parseFile(file.file())
        screenSchema.assertMatches(onj)
        onj as OnjObject

        readAssets(onj)
        doOptions(onj)
        doTemplates(onj)

        val screen = OnjScreen(
            viewport = getViewport(onj.get<OnjNamedObject>("viewport")),
            batch = SpriteBatch(),
            controllerContext = controllerContext,
            styleManagers = listOf(),
            background = background,
            useAssets = borrowed.toMutableList(),
            earlyRenderTasks = earlyRenderTasks,
            lateRenderTasks = lateRenderTasks,
            namedActors = namedActors,
            printFrameRate = false,
            namedCells = mapOf(),
            transitionAwayTime = transitionAwayTime,
            screenBuilder = this,
        )

        onj.get<OnjObject>("options").ifHas<OnjArray>("inputMap") {
            screen.inputMap = KeyInputMap.readFromOnj(it, screen)
        }

        val root = CustomFlexBox(screen)
        root.setFillParent(true)
        getWidget(onj.get<OnjNamedObject>("root"), root, screen)

        screen.addActorToRoot(root)
        screen.buildKeySelectHierarchy()

        screen.screenController = screenController

        val dragAndDrops = doDragAndDrop(screen)
        screen.dragAndDrop = dragAndDrops

        for (behaviour in behavioursToBind) behaviour.bindCallbacks(screen)

        root.addListener { event ->
            screen.screenController?.onUnhandledEvent(event)
            false
        }

        return screen
    }

    private fun doTemplates(onj: OnjObject) {
        onj.ifHas<OnjArray>("templates") {
            for (a in it.value) {
                a as OnjNamedObject
                if (!a.hasKey<String>("template_name") || !a.hasKey<OnjObject>("template_keys")) {
                    throw RuntimeException("templates must define both template_name and template_keys!")
                }
                templateObjects[a.get<String>("template_name")] = a
            }
        }
    }

    fun generateFromTemplate(name: String, data: Map<String, OnjValue>, parent: FlexBox?, screen: OnjScreen): Actor? {
        val template = templateObjects[name] ?: return null
        val combinedData = combineTemplateValues(template.get<OnjObject>("template_keys"), data)
        val widgetOnj = generateTemplateOnjValue(template, combinedData, "")
        widgetOnj as OnjNamedObject
        val oldBehaviours = behavioursToBind.toList()
        val curActor = getWidget(widgetOnj, parent, screen)
        val newBehaviours = behavioursToBind.filter { it !in oldBehaviours }
        for (behaviour in newBehaviours) behaviour.bindCallbacks(screen)
        screen.dragAndDrop = doDragAndDrop(screen)
        screen.invalidateEverything()
        return curActor
    }

    private fun generateTemplateOnjValue(
        original: OnjValue,
        combinedData: Map<String, OnjValue>,
        name: String
    ): OnjValue = when (original) {

        is OnjObject -> {
            val new = original.value.mapValues { (key, value) ->
                val childKey = if (name == "") key else "$name.$key"
                combinedData[childKey] ?: generateTemplateOnjValue(value, combinedData, childKey)
            }
            if (original is OnjNamedObject) OnjNamedObject(original.name, new) else OnjObject(new)
        }

        is OnjArray -> {
            val new = original.value.mapIndexed { index, value ->
                val childKey = "$name.$index"
                combinedData[childKey] ?: generateTemplateOnjValue(value, combinedData, childKey)
            }
            OnjArray(new)
        }

        else -> original

    }

    private fun combineTemplateValues(templateKeys: OnjObject, data: Map<String, OnjValue>): Map<String, OnjValue> {
        val result = mutableMapOf<String, OnjValue>()
        val keys = templateKeys
            .value
            .mapValues { (_, value) ->
                if (value !is OnjString) throw RuntimeException("template_keys can only contain OnjStrings!")
                value
            }
        data.forEach { (dataPointName, value) ->
            val key = keys.entries.find { it.value.value == dataPointName }?.key
                ?: throw RuntimeException(
                    "cannot set $dataPointName in template because it is not defined in the template keys"
                )
            result[key] = value
        }
        return result
    }


    private fun doOptions(onj: OnjObject) {
        val options = onj.get<OnjObject>("options")
        options.ifHas<String>("background") {
            background = it
        }
        options.ifHas<OnjNamedObject>("screenController") {
            screenController = ScreenControllerFactory.controllerOrError(it.name, it)
        }
        options.ifHas<Double>("transitionAwayTime") {
            transitionAwayTime = (it * 1000).toInt()
        }
    }

    private fun readAssets(onj: OnjObject) {
        val assets = onj.get<OnjObject>("assets")

        val toBorrow = mutableListOf<String>()

        assets.ifHas<OnjArray>("useAssets") { arr ->
            toBorrow.addAll(arr.value.map { (it as OnjString).value })
        }
        borrowed = toBorrow
    }

    private fun doDragAndDrop(screen: OnjScreen): MutableMap<String, DragAndDrop> {
        val dragAndDrops = mutableMapOf<String, DragAndDrop>()
        for ((group, actors) in actorsWithDragAndDrop) {
            val dragAndDrop = DragAndDrop()
            for ((actor, onj) in actors) {
                val behaviour = DragAndDropBehaviourFactory.behaviourOrError(
                    onj.name,
                    dragAndDrop,
                    screen,
                    actor,
                    onj
                )
                if (behaviour is Either.Left) dragAndDrop.addSource(behaviour.value)
                else dragAndDrop.addTarget((behaviour as Either.Right).value)
            }
            dragAndDrops[group] = dragAndDrop
        }
        return dragAndDrops
    }


    private fun initFlexBox(
        flexBox: CustomFlexBox,
        widgetOnj: OnjObject,
        screen: OnjScreen
    ) {
        flexBox.root.setPosition(YogaEdge.ALL, 0f)
        if (widgetOnj.hasKey<OnjArray>("children")) {
            widgetOnj
                .get<OnjArray>("children")
                .value
                .forEach {
                    getWidget(it as OnjNamedObject, flexBox, screen)
                }
        }
        flexBox.isTransform = widgetOnj.getOr("enableTransform", false)
        flexBox.resortZIndices()
    }

    private fun getViewport(viewportOnj: OnjNamedObject): Viewport = when (viewportOnj.name) {

        "FitViewport" -> {
            val worldHeight = viewportOnj.get<Double>("worldHeight").toFloat()
            val worldWidth = viewportOnj.get<Double>("worldWidth").toFloat()
            FitViewport(worldWidth, worldHeight)
        }

        "ExtendViewport" -> {
            val minWidth = viewportOnj.get<Double>("minWidth").toFloat()
            val minHeight = viewportOnj.get<Double>("minWidth").toFloat()
            val viewport = ExtendViewport(minWidth, minHeight)
            viewport
        }

        else -> throw RuntimeException("unknown Viewport ${viewportOnj.name}")

    }

    private fun getWidget(
        widgetOnj: OnjNamedObject,
        parent: FlexBox?,
        screen: OnjScreen
    ): Actor = when (widgetOnj.name) {

        "Image" -> CustomImageActor(
            widgetOnj.getOr<String?>("textureName", null),
            screen,
            widgetOnj.getOr("partOfSelectionHierarchy", false)
        ).apply {
            applyImageKeys(this, widgetOnj)
        }

        "Box" -> CustomFlexBox(screen).apply {
            initFlexBox(this, widgetOnj, screen)
        }

        "ScrollBox" -> CustomScrollableFlexBox(
            screen,
            widgetOnj.get<Boolean>("isScrollDirectionVertical"),
            widgetOnj.get<Double>("scrollDistance").toFloat(),
            widgetOnj.get<Boolean>("backgroundStretched"),
            widgetOnj.get<String?>("scrollbarBackgroundName"),
            widgetOnj.get<String?>("scrollbarName"),
            widgetOnj.get<String?>("scrollbarSide"),
        ).apply {
            initFlexBox(this, widgetOnj, screen)
            this.touchable = Touchable.enabled
        }

        "Statusbar" -> StatusbarWidget(
            widgetOnj.get<String?>("mapIndicatorWidgetName"),
            widgetOnj.get<String>("optionsWidgetName"),
            widgetOnj.get<OnjArray>("options").value as List<OnjObject>,
            screen
        ).apply {
            initFlexBox(this, widgetOnj, screen)
        }

        "Label" -> CustomLabel(
            text = widgetOnj.get<String>("text"),
            labelStyle = Label.LabelStyle().apply {
                font = fontOrError(
                    widgetOnj.get<String>("font"),
                    screen
                ) // TODO: figure out how to not load the font immediatley
                if (!widgetOnj.get<OnjValue>("color").isNull()) {
                    fontColor = widgetOnj.get<Color>("color")
                }
            },
            partOfHierarchy = widgetOnj.getOr("partOfSelectionHierarchy", false),
            screen = screen
        ).apply {
            setFontScale(widgetOnj.getOr("fontScale", 1.0).toFloat())
            widgetOnj.ifHas<String>("backgroundTexture") { backgroundHandle = it }
            widgetOnj.ifHas<String>("align") { setAlignment(alignmentOrError(it)) }
            widgetOnj.ifHas<Boolean>("wrap") { wrap = it }
        }

        "CardHand" -> CardHand(
            widgetOnj.get<Double>("targetWidth").toFloat(),
            widgetOnj.get<Double>("cardSize").toFloat(),
            widgetOnj.get<Double>("opacityIfNotPlayable").toFloat(),
            widgetOnj.get<Double>("centerGap").toFloat(),
            screen
        ).apply {
            hoveredCardScale = widgetOnj.get<Double>("hoveredCardScale").toFloat()
            maxCardSpacing = widgetOnj.get<Double>("maxCardSpacing").toFloat()
            startCardZIndicesAt = widgetOnj.get<Long>("startCardZIndicesAt").toInt()
            hoveredCardZIndex = widgetOnj.get<Long>("hoveredCardZIndex").toInt()
            draggedCardZIndex = widgetOnj.get<Long>("draggedCardZIndex").toInt()
        }

        "Revolver" -> Revolver(
            widgetOnj.get<String>("background"),
            widgetOnj.get<String>("slotTexture"),
            widgetOnj.get<Double>("radiusExtension").toFloat(),
            screen
        ).apply {
            slotScale = widgetOnj.get<Double>("slotScale").toFloat()
            cardScale = widgetOnj.get<Double>("cardScale").toFloat()
            animationDuration = widgetOnj.get<Double>("animationDuration").toFloat()
            radius = widgetOnj.get<Double>("radius").toFloat()
            rotationOff = widgetOnj.get<Double>("rotationOff")
            cardZIndex = widgetOnj.get<Long>("cardZIndex").toInt()
        }

        "EnemyArea" -> EnemyArea(
            widgetOnj.get<String>("enemySelectionDrawable"),
            screen
        )

        "TemplateLabel" -> TemplateStringLabel(
            screen,
            templateString = TemplateString(widgetOnj.get<String>("template")),
            labelStyle = Label.LabelStyle(
                fontOrError(widgetOnj.get<String>("font"), screen),
                widgetOnj.get<Color>("color")
            ),
            partOfHierarchy = widgetOnj.getOr("partOfSelectionHierarchy", false)
        ).apply {
            setFontScale(widgetOnj.get<Double>("fontScale").toFloat())
            widgetOnj.ifHas<String>("backgroundTexture") { backgroundHandle = it }
            widgetOnj.ifHas<String>("align") { setAlignment(alignmentOrError(it)) }
            widgetOnj.ifHas<Boolean>("wrap") { wrap = it }
        }

        "Map" -> DetailMapWidget(
            screen,
            MapManager.currentDetailMap,
            widgetOnj.get<String>("defaultNodeTexture"),
            widgetOnj.get<String>("edgeTexture"),
            widgetOnj.get<String>("playerTexture"),
            widgetOnj.get<Double>("playerWidth").toFloat(),
            widgetOnj.get<Double>("playerHeight").toFloat(),
            widgetOnj.get<Double>("nodeSize").toFloat(),
            widgetOnj.get<Double>("lineWidth").toFloat(),
            (widgetOnj.get<Double>("playerMovementTime") * 1000).toInt(),
            widgetOnj.get<String>("directionIndicator"),
            widgetOnj.get<String>("startButtonName"),
            widgetOnj.get<String>("background"),
            widgetOnj.get<Double>("screenSpeed").toFloat(),
            widgetOnj.get<Double>("backgroundScale").toFloat(),
            widgetOnj.get<Double>("disabledDirectionIndicatorAlpha").toFloat(),
            widgetOnj.get<Double>("leftScreenSideDeadSection").toFloat(),
        )

        "AdvancedText" -> AdvancedTextWidget(
            AdvancedText.readFromOnj(widgetOnj.get<OnjArray>("parts"), screen, widgetOnj.get<OnjObject>("defaults")),
            screen
        )

        "DialogWidget" -> DialogWidget(
//            Dialog.readFromOnj(widgetOnj.get<OnjObject>("dialog"), screen),
            (widgetOnj.get<Double>("progressTime") * 1000).toInt(),
            widgetOnj.get<String>("advanceArrowDrawable"),
            widgetOnj.get<Double>("advanceArrowOffset").toFloat(),
            widgetOnj.get<String>("optionsBox"),
            fontOrError(widgetOnj.get<String>("optionsFont"), screen),
            widgetOnj.get<Color>("optionsFontColor"),
            widgetOnj.get<Double>("optionsFontScale").toFloat(),
            screen
        )

        "WorldView" -> WorldViewWidget(
            OnjParser.parseFile(Gdx.files.internal(MapManager.mapConfigFilePath).file()) as OnjObject, // TODO: schema?
            screen
        )

        "CircularCardSelector" -> CircularCardSelector(
            widgetOnj.get<Double>("radius").toFloat(),
            widgetOnj.get<Double>("size").toFloat(),
            widgetOnj.get<String>("emptySlotTexture"),
            widgetOnj.get<Double>("disabledAlpha").toFloat(),
            screen
        )

        "PersonWidget" -> PersonWidget(
            widgetOnj.get<Double>("offsetX").toFloat(),
            widgetOnj.get<Double>("offsetY").toFloat(),
            widgetOnj.get<Double>("scale").toFloat(),
            OnjNamedObject("name", mapOf()),
            screen
        )

        "Backpack" -> Backpack(
            screen,
            widgetOnj.get<String>("cardsFile"),
            widgetOnj.get<String>("backpackFile")
        ).apply {
            initFlexBox(this, widgetOnj, screen)
        }

        "FromTemplate" -> generateFromTemplate(
            widgetOnj.get<String>("generateFrom"),
            widgetOnj.get<OnjObject>("data").value,
            parent,
            screen
        )!!

        else -> throw RuntimeException("Unknown widget name ${widgetOnj.name}")

    }.let { actor ->
        applySharedWidgetKeys(actor, widgetOnj)
        val node = parent?.add(actor)

        node ?: return actor
        if (actor !is StyledActor) {
            if (widgetOnj.hasKey<OnjArray>("styles")) {
                throw RuntimeException("actor $actor defines styles but does not implement StyledActor")
            }
            return actor
        }

        val styleManager = StyleManager(actor, node)
        actor.styleManager = styleManager
        actor.initStyles(screen)
        screen.addStyleManager(styleManager)

        widgetOnj.ifHas<OnjArray>("styles") { arr ->
            arr.value.forEach { obj ->
                obj as OnjObject
                val priority = obj.getOr("style_priority", -1L).toInt()
                val condition = obj.getOr<StyleCondition>("style_condition", StyleCondition.Always)
                var duration: Int? = null
                var interpolation: Interpolation? = null
                obj.ifHas<OnjObject>("style_animation") {
                    val result = readStyleAnimation(it)
                    duration = result.first
                    interpolation = result.second
                }
                obj.value
                    .filter { !it.key.startsWith("style_") }
                    .forEach { (key, value) ->
                        val data = getDataForStyle(value, key)
                        val dataClass = data::class
                        val instruction = if (duration == null) {
                            StyleInstruction(data, priority, condition, dataClass)
                        } else {
                            AnimatedStyleInstruction(data, priority, condition, dataClass, duration!!, interpolation!!)
                        }
                        styleManager.addInstruction(key, instruction, dataClass)
                    }
            }
        }
        return actor
    }


    private fun readStyleAnimation(animation: OnjObject): Pair<Int, Interpolation> {
        return (animation.get<Double>("duration") * 1000).toInt() to animation.get<Interpolation>("interpolation")
    }

    private fun getDataForStyle(onjValue: OnjValue, keyName: String): Any {
        var data = onjValue.value ?: throw RuntimeException("style instruction $keyName cannot be null")
        if (data is Double) data = data.toFloat()
        if (data is Long) data = data.toInt()
        return data
    }

    private fun applyImageKeys(image: CustomImageActor, widgetOnj: OnjNamedObject) {
        image.scaleX = widgetOnj.get<Double>("scaleX").toFloat()
        image.scaleY = widgetOnj.get<Double>("scaleY").toFloat()
        if (widgetOnj.getOr("reportDimensionsWithScaling", false)) {
            image.reportDimensionsWithScaling = true
            image.ignoreScalingWhenDrawing = true
        }
    }

    private fun applySharedWidgetKeys(actor: Actor, widgetOnj: OnjNamedObject) = with(actor) {
        debug = widgetOnj.getOr("debug", false)

        widgetOnj.ifHas<OnjNamedObject>("dragAndDrop") {
            applyDragAndDrop(actor, it)
        }

        widgetOnj.ifHas<OnjArray>("behaviours") { arr ->
            arr.value.forEach {
                it as OnjNamedObject
                behavioursToBind.add(BehaviourFactory.behaviorOrError(it.name, it, this))
            }
        }

        widgetOnj.ifHas<Long>("zIndex") {
            if (this !is ZIndexActor) throw RuntimeException("can only apply z-index to ZIndexActors")
            fixedZIndex = it.toInt()
        }

        widgetOnj.ifHas<Boolean>("visible") { isVisible = it }
        widgetOnj.ifHas<String>("name") { namedActors[it] = this }
        widgetOnj.ifHas<String>("touchable") { touchable = Touchable.valueOf(it) }

        onClick { fire(ButtonClickEvent()) }
    }

    private fun applyDragAndDrop(actor: Actor, onj: OnjNamedObject) {
        val group = onj.get<String>("group")
        if (!actorsWithDragAndDrop.containsKey(group)) actorsWithDragAndDrop[group] = mutableListOf()
        actorsWithDragAndDrop[group]!!.add(actor to onj)
    }

    private fun fontOrError(name: String, screen: OnjScreen): BitmapFont {
        return ResourceManager.get(screen, name)
    }

    private fun alignmentOrError(alignment: String): Int = when (alignment) {
        "center" -> Align.center
        "top" -> Align.top
        "bottom" -> Align.bottom
        "left" -> Align.left
        "bottom left" -> Align.bottomLeft
        "top left" -> Align.topLeft
        "right" -> Align.right
        "bottom right" -> Align.bottomRight
        "top right" -> Align.topRight
        else -> throw RuntimeException("unknown alignment: $alignment")
    }

    companion object {

        val screenSchema: OnjSchema by lazy {
            OnjSchemaParser.parseFile(Gdx.files.internal("onjschemas/screen.onjschema").file())
        }
    }

}
