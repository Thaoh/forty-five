package com.fourinachamber.fourtyfive.game.enemy

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.fourinachamber.fourtyfive.game.*
import com.fourinachamber.fourtyfive.screen.general.OnjScreen
import com.fourinachamber.fourtyfive.utils.Timeline
import onj.value.OnjNamedObject

/**
 * represents an action that the enemy can execute
 */
abstract class EnemyAction {

    /**
     * the Drawable that is drawn above the enemies head to indicate which action will be executed
     */
    abstract val indicatorDrawable: Drawable

    /**
     * the scale [indicatorDrawable] is drawn at
     */
    abstract val indicatorScale: Float

    /**
     * the text displayed next to [indicatorDrawable]
     */
    abstract val descriptionText: String

    /**
     * returns a timeline that executes the action
     */
    abstract fun execute(): Timeline?

    /**
     * action that damages the player
     */
    class DamagePlayer(
        val enemy: Enemy,
        onj: OnjNamedObject,
        onjScreen: OnjScreen,
        override val indicatorScale: Float,
        val damage: Int
    ) : EnemyAction() {

        override val indicatorDrawable: Drawable = onjScreen.drawableOrError(onj.get<String>("indicatorTexture"))

        override val descriptionText: String = damage.toString()

        override fun execute(): Timeline = enemy.damagePlayer(damage)

        override fun toString(): String {
            return "DamagePlayer(damage=$damage)"
        }
    }

    /**
     * actions that adds cover to the enemy
     */
    class AddCover(
        val enemy: Enemy,
        onj: OnjNamedObject,
        onjScreen: OnjScreen,
        override val indicatorScale: Float,
        val coverValue: Int
    ) : EnemyAction() {

        override val indicatorDrawable: Drawable = onjScreen.drawableOrError(onj.get<String>("indicatorTexture"))
        override val descriptionText: String = coverValue.toString()

        override fun execute(): Timeline = Timeline.timeline {
            val textAnimation = GraphicsConfig.numberChangeAnimation(
                enemy.actor.coverText.localToStageCoordinates(Vector2(0f, 0f)),
                coverValue.toString(),
                true,
                true
            )

            action { enemy.currentCover += coverValue }
            includeAction(textAnimation)
            delay(GraphicsConfig.bufferTime)
        }


        override fun toString(): String {
            return "AddCover(cover=$coverValue)"
        }

    }

    /**
     * the player insults the player and does nothing else
     */
    class DoNothing(
        val insult: String,
        val enemy: Enemy,
        onj: OnjNamedObject,
        onjScreen: OnjScreen,
        override val indicatorScale: Float
        ) : EnemyAction() {

        override val indicatorDrawable: Drawable = onjScreen.drawableOrError(onj.get<String>("indicatorTexture"))
        override val descriptionText: String  = ""

        override fun execute(): Timeline = Timeline.timeline {
            val fadeAnimation = GraphicsConfig.insultFadeAnimation(
                enemy.actor.localToStageCoordinates(Vector2(0f, 0f)),
                insult
            )
            delayUntil { fadeAnimation.isFinished() }
            includeAction(fadeAnimation)
            delay(GraphicsConfig.bufferTime)
        }


        override fun toString(): String {
            return "DoNothing()"
        }

    }

}
