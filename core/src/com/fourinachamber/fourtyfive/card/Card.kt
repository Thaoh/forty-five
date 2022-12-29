package com.fourinachamber.fourtyfive.card

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.fourinachamber.fourtyfive.game.Effect
import com.fourinachamber.fourtyfive.game.GameScreenController
import com.fourinachamber.fourtyfive.game.OnjExtensions
import com.fourinachamber.fourtyfive.game.Trigger
import com.fourinachamber.fourtyfive.screen.CustomImageActor
import com.fourinachamber.fourtyfive.screen.ZIndexActor
import com.fourinachamber.fourtyfive.utils.FourtyFiveLogger
import com.fourinachamber.fourtyfive.utils.TemplateString
import com.fourinachamber.fourtyfive.utils.Timeline
import ktx.actors.onEnter
import ktx.actors.onExit
import onj.OnjArray
import onj.OnjNamedObject
import onj.OnjObject

/**
 * represents a type of card, e.g. there is one Prototype for an incendiary bullet, but there might be more than one
 * actual instances of the card. Prototypes can be used to create those instances
 * @param name the name of the card produced by this prototype
 * @param type the type of card (bullet or cover)
 * @param creator lambda that creates the instance
 */
class CardPrototype(
    val name: String,
    val type: Card.Type,
    private val creator: () -> Card
) {

    /**
     * creates an actual instance of this card
     */
    fun create(): Card = creator()
}

/**
 * represents an actual instance of a card. Can be created using [CardPrototype]
 * @param name the name of the card
 * @param title the name but formatted, so it looks good when shown on the screen
 * @param texture the texture of the card
 * @param flavourText Short phrase that (should) be funny or add to the lore
 * @param shortDescription short text explaining the effects of this card; can be left blank
 * @param type the type of card (bullet or cover)
 * @param baseDamage the damage value of the card before modifiers are applied (typically 0 when this is a cover)
 * @param coverValue the cover this card provides (typically 0 when this is a bullet)
 * @param cost the cost of this card in reserves
 * @param effects the effects of this card
 */
class Card(
    val name: String,
    val title: String,
    val texture: TextureRegion,
    val flavourText: String,
    val shortDescription: String,
    val type: Type,
    val baseDamage: Int,
    val coverValue: Int,
    val cost: Int,
    val effects: List<Effect>
) {

    /**
     * used for logging
     */
    val logTag = "card-$name-${++instanceCounter}"

    /**
     * the actor for representing the card on the screen
     */
    val actor = CardActor(this)

    //TODO: isDraggable and inAnimation should be in the actor class

    /**
     * true when the card can be dragged
     */
    var isDraggable: Boolean = true

    /**
     * true when [actor] is in an animation
     */
    var inAnimation: Boolean = false

    var inGame: Boolean = false
        private set

    /**
     * the current damage with all modifiers applied
     */
    var curDamage: Int = baseDamage
        private set
        get() {
            if (!isDamageDirty) return field
            var cur = baseDamage
            for (modifier in modifiers) cur += modifier.damage
            field = cur
            isDamageDirty = false
            return cur
        }

    /**
     * the complete description of the card. Includes [flavourText], [shortDescription], information about damage/cover,
     * cost and modifiers
     */
    var description = ""
        private set

    private var isEverlasting: Boolean = false
    private var isUndead: Boolean = false
    private var isRotten: Boolean = false
    private var isLeftRotating: Boolean = false

    val shouldRemoveAfterShot: Boolean
        get() = !isEverlasting

    val shouldRotateLeft: Boolean
        get() = isLeftRotating

    private lateinit var rottenModifier: CardModifier

    private val modifiers: MutableList<CardModifier> = mutableListOf()

    private var isDamageDirty: Boolean = true

    init {
        updateText()
    }

    private fun initRottenModifier() {
        rottenModifier = CardModifier(0, null) { true }
        modifiers.add(rottenModifier)
        updateText()
    }

    private fun updateRottenModifier(newDamage: Int) {
        modifiers.remove(rottenModifier)
        rottenModifier = CardModifier(
            newDamage,
            TemplateString(rottenDetailTextRawString, mapOf("damageLost" to newDamage))
        ) { true }
        modifiers.add(rottenModifier)
        isDamageDirty = true
        updateText()
    }

    /**
     * checks if the modifiers of this card are still valid and removes them if they are not
     */
    fun checkModifierValidity() {
        val iterator = modifiers.iterator()
        while (iterator.hasNext()) {
            val modifier = iterator.next()
            if (!modifier.validityChecker()) {
                FourtyFiveLogger.debug(logTag, "modifier no longer valid: $modifier")
                iterator.remove()
                isDamageDirty = true
            }
        }
        if (isDamageDirty) updateText()
    }

    /**
     * called by gameScreenController when the card was shot
     */
    fun afterShot(gameScreenController: GameScreenController) {
        if (isUndead) {
            FourtyFiveLogger.debug(logTag, "undead card is respawning in hand after being shot")
            gameScreenController.cardHand!!.addCard(this)
        }
        if (!isEverlasting) leaveGame()
    }

    private fun leaveGame() {
        inGame = false
        modifiers.clear()
        isDamageDirty = true
        updateText()
    }

    /**
     * called by gameScreenController when the destroy-phase starts
     */
    fun enterDestroyMode(gameScreenController: GameScreenController) = actor.enterDestroyMode(gameScreenController)

    /**
     * called by gameScreenController the destroy-phase ends
     */
    fun leaveDestroyMode() = actor.leaveDestroyMode()

    /**
     * checks whether this card can currently enter the game
     */
    fun allowsEnteringGame(gameScreenController: GameScreenController): Boolean {
        // handles special case for Destroy effect
        for (effect in effects) if (effect is Effect.Destroy && effect.trigger == Trigger.ON_ENTER) {
            if (!gameScreenController.hasDestroyableCard()) {
                FourtyFiveLogger.debug(logTag, "card cannot enter game because it has the destroy effect and" +
                        " no destroyable bullet is present")
                return false
            }
        }
        return true
    }

    /**
     * called when the coverStack this card is in was destroyed
     */
    fun onCoverDestroy() {
        leaveGame()
    }

    /**
     * called when this card was destroyed by the destroy effect
     */
    fun onDestroy(gameScreenController: GameScreenController) {
        if (isUndead) {
            FourtyFiveLogger.debug(logTag, "undead card is respawning in hand after being destroyed")
            gameScreenController.cardHand!!.addCard(this)
        }
        leaveDestroyMode()
        leaveGame()
    }

    /**
     * adds a new modifier to the card
     */
    fun addModifier(modifier: CardModifier) {
        FourtyFiveLogger.debug(logTag, "card got new modifier: $modifier")
        modifiers.add(modifier)
        isDamageDirty = true
        updateText()
    }

    /**
     * called when the card enters the game
     */
    fun onEnter(gameScreenController: GameScreenController) {
        inGame = true
    }

    /**
     * called when the revolver rotates (but not when this card was shot)
     */
    fun onRevolverTurn() {
        if (isRotten) updateRottenModifier(rottenModifier.damage - 1)
    }

    /**
     * checks if the effects of this card respond to [trigger] and returns a timeline containing the actions for the
     * effects; null if no effect was triggered
     */
    fun checkEffects(trigger: Trigger, gameScreenController: GameScreenController): Timeline? {
        var wasEffectWithTimelineTriggered = false
        val timeline = Timeline.timeline {
            for (effect in effects) {
                val effectTimeline = effect.checkTrigger(trigger, gameScreenController)
                if (effectTimeline != null) {
                    include(effectTimeline)
                    wasEffectWithTimelineTriggered = true
                }
            }
        }
        return if (wasEffectWithTimelineTriggered) timeline else null
    }

    private fun updateText() {
        val builder = StringBuilder()

        builder.append("\n$flavourText\n\n")
        if (shortDescription.isNotBlank()) builder.append("$shortDescription\n\n")
        builder.append("cost: $cost\n")

        if (type == Type.BULLET) builder.append("damage: $curDamage/$baseDamage")
        else builder.append("cover value: $coverValue")

        builder.append("\n\n")

        for (modifier in modifiers) if (modifier.description != null) {
            builder.append(modifier.description.string).append("\n")
        }

        description = builder.toString()
    }

    override fun toString(): String {
        return "card: $name"
    }

    companion object {

        /**
         * all textures of cards are prefixed with this string
         */
        const val cardTexturePrefix = "card%%"

        private var instanceCounter = 0

        /**
         * reads an onj array it returns the corresponding card prototypes
         * @param cards the onj array
         * @param regions the textures for the cards will be looked up in this map
         * @param initializer lambda that can contain additional initialization logic
         */
        fun getFrom(
            cards: OnjArray,
            regions: Map<String, TextureRegion>,
            initializer: (Card) -> Unit
        ): List<CardPrototype> {

            val prototypes = mutableListOf<CardPrototype>()

            cards
                .value
                .forEach { onj ->
                    onj as OnjObject
                    val prototype = CardPrototype(
                        onj.get<String>("name"),
                        cardTypeOrError(onj)
                    ) { getCardFrom(onj, regions, initializer) }
                    prototypes.add(prototype)
                }
            return prototypes
        }


        private fun getCardFrom(
            onj: OnjObject,
            regions: Map<String, TextureRegion>,
            initializer: (Card) -> Unit
        ): Card {
            val name = onj.get<String>("name")

            val card = Card(
                name,
                onj.get<String>("title"),
                regions["$cardTexturePrefix$name"]
                    ?: throw RuntimeException("cannot find texture for card $name"),

                onj.get<String>("flavourText"),
                onj.get<String>("description"),
                cardTypeOrError(onj),
                onj.get<Long>("baseDamage").toInt(),
                onj.get<Long>("coverValue").toInt(),
                onj.get<Long>("cost").toInt(),

                onj.get<OnjArray>("effects")
                    .value
                    .map { (it as OnjExtensions.OnjEffect).value.copy() } //TODO: find a better solution
            )

            for (effect in card.effects) effect.card = card
            applyTraitEffects(card, onj)
            initializer(card)
            return card
        }

        private fun cardTypeOrError(onj: OnjObject) = when (val type = onj.get<OnjNamedObject>("type").name) {
            "Bullet" -> Type.BULLET
            "Cover" -> Type.COVER
            "OneShot" -> Type.ONE_SHOT
            else -> throw RuntimeException("unknown Card type: $type")
        }


        private fun applyTraitEffects(card: Card, onj: OnjObject) {
            val effects = onj
                .get<OnjArray>("traitEffects")
                .value
                .map { it.value as String }

            for (effect in effects) when (effect) {

                "everlasting" -> card.isEverlasting = true
                "undead" -> card.isUndead = true
                "rotten" -> {
                    card.isRotten = true
                    card.initRottenModifier()
                }
                "leftRotating" -> card.isLeftRotating = true

                else -> throw RuntimeException("unknown trait effect $effect")
            }
        }

        private lateinit var rottenDetailTextRawString: String

        fun init(config: OnjObject) {
            val tmplOnj = config.get<OnjObject>("stringTemplates")
            rottenDetailTextRawString = tmplOnj.get<String>("rottenDetailText")
        }

    }

    /**
     * a type of card
     */
    enum class Type {
        BULLET, COVER, ONE_SHOT
    }

    /**
     * temporarily modifies a card. For example used by the buff damage effect to change the damage of a card
     * @param damage changes the damage of the card. Can be negative
     * @param description if not null this description will be displayed in the detail text of the modified card
     * @param validityChecker checks if the modifier is still valid or should be removed
     */
    data class CardModifier(
        val damage: Int,
        val description: TemplateString?,
        val validityChecker: () -> Boolean
    )

}

/**
 * the actor representing a card on the screen
 */
class CardActor(val card: Card) : CustomImageActor(card.texture), ZIndexActor {

    override var fixedZIndex: Int = 0

    /**
     * true when the card is dragged; set by [CardDragSource][com.fourinachamber.fourtyfive.card.CardDragSource]
     */
    var isDragged: Boolean = false

    /**
     * true when the actor is hovered over
     */
    var isHoveredOver: Boolean = false
        private set

    //TODO: fix
    private lateinit var gameScreenController: GameScreenController

    private val destroyModeOnClickListener: EventListener = EventListener { event ->
        if (event !is InputEvent || event.type != InputEvent.Type.touchDown) return@EventListener false
        gameScreenController.destroyCard(card)
        true
    }

    init {
        onEnter { isHoveredOver = true }
        onExit { isHoveredOver = false }
    }

    fun enterDestroyMode(gameScreenController: GameScreenController) {
        this.gameScreenController = gameScreenController
        addListener(destroyModeOnClickListener)
    }

    fun leaveDestroyMode() {
        removeListener(destroyModeOnClickListener)
    }

}
