package com.majeur.psclient.io

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.majeur.psclient.R
import com.majeur.psclient.model.battle.Player
import com.majeur.psclient.model.pokemon.BasePokemon
import com.majeur.psclient.model.pokemon.BattlingPokemon
import com.majeur.psclient.util.Utils
import com.majeur.psclient.util.glide.AnimatedImageViewTarget
import com.majeur.psclient.util.html.Html
import com.majeur.psclient.widget.BattleLayout
import timber.log.Timber
import java.util.concurrent.ExecutionException
import kotlin.math.roundToInt

class GlideHelper(context: Context) {

    companion object {
        private const val MAGIC_SCALE = 0.0027777777777778f

        // PokeAPI GitHub raw sprites — no hotlink protection, free to use.
        // play.pokemonshowdown.com returns 403 for all non-browser HTTP clients.
        private const val POKEAPI_BASE =
            "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon"

        fun animFrontUrl(num: Int, shiny: Boolean): String {
            val s = if (shiny) "shiny/" else ""
            return "$POKEAPI_BASE/versions/generation-v/black-white/animated/${s}${num}.gif"
        }

        fun animBackUrl(num: Int, shiny: Boolean): String {
            val s = if (shiny) "shiny/" else ""
            return "$POKEAPI_BASE/versions/generation-v/black-white/animated/back/${s}${num}.gif"
        }

        fun staticFrontUrl(num: Int, shiny: Boolean): String {
            val s = if (shiny) "shiny/" else ""
            return "$POKEAPI_BASE/${s}${num}.png"
        }

        fun staticBackUrl(num: Int, shiny: Boolean): String {
            val s = if (shiny) "shiny/" else ""
            return "$POKEAPI_BASE/back/${s}${num}.png"
        }

        // Trainer avatars still come from PS (no alternative); silently fails if 403
        fun trainerUrl(avatar: String) =
            "https://play.pokemonshowdown.com/sprites/trainers/$avatar.png"
    }

    private val glide = Glide.with(context)

    fun loadBattleSprite(pokemon: BattlingPokemon, imageView: ImageView) {
        val num = pokemon.dexNum
        if (num <= 0) { imageView.setImageResource(R.drawable.missingno); return }

        val back  = pokemon.trainer
        val shiny = pokemon.shiny
        val anim   = if (back) animBackUrl(num, shiny)   else animFrontUrl(num, shiny)
        val static = if (back) staticBackUrl(num, shiny) else staticFrontUrl(num, shiny)

        glide.load(anim)
            .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
            .error(
                glide.load(static)
                    .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
                    .error(R.drawable.missingno)
            )
            .into(object : AnimatedImageViewTarget(imageView) {

                override fun onInitInAnimation(v: ViewPropertyAnimator) {
                    v.setDuration(250).setInterpolator(DecelerateInterpolator())
                     .scaleX(0f).scaleY(0f).alpha(0f)
                }

                override fun onInitOutAnimation(v: ViewPropertyAnimator) {
                    v.setDuration(250).setInterpolator(AccelerateInterpolator())
                     .scaleX(1f).scaleY(1f).alpha(1f)
                }

                override fun onApplyResourceSize(w: Int, h: Int) {
                    val layout = imageView.parent as? BattleLayout ?: return
                    val applySize = { fw: Int ->
                        var scale = fw * MAGIC_SCALE
                        if (!pokemon.foe) scale *= 1.5f
                        getView().layoutParams.apply {
                            width  = (w * scale).roundToInt().coerceAtLeast(1)
                            height = (h * scale).roundToInt().coerceAtLeast(1)
                        }
                        getView().requestLayout()
                    }
                    if (layout.width > 0) applySize(layout.width)
                    else layout.post { applySize(layout.width) }
                }
            }).also { imageView.setTag(R.id.glide_tag, it) }
    }

    fun loadPreviewSprite(player: Player, pokemon: BasePokemon, imageView: ImageView) {
        val num = (pokemon as? BattlingPokemon)?.dexNum ?: 0
        if (num <= 0) { imageView.setImageResource(R.drawable.missingno); return }

        val back = player == Player.TRAINER
        val url  = if (back) staticBackUrl(num, false) else staticFrontUrl(num, false)

        glide.load(url)
            .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
            .error(R.drawable.missingno)
            .into(object : AnimatedImageViewTarget(imageView) {
                override fun onInitInAnimation(v: ViewPropertyAnimator) = Unit
                override fun onInitOutAnimation(v: ViewPropertyAnimator) = Unit

                override fun onApplyResourceSize(w: Int, h: Int) {
                    val fieldWidth = (imageView.parent as? BattleLayout)?.width ?: 0
                    val scale = fieldWidth * MAGIC_SCALE
                    imageView.layoutParams.apply {
                        width  = (w * scale).roundToInt().coerceAtLeast(1)
                        height = (h * scale).roundToInt().coerceAtLeast(1)
                        Timber.d("preview sprite sized $w×$h scale=$scale")
                    }
                }
            }).also { imageView.setTag(R.id.glide_tag, it) }
    }

    fun loadDexSprite(pokemon: BasePokemon, shiny: Boolean, imageView: ImageView) {
        val num = (pokemon as? BattlingPokemon)?.dexNum ?: 0
        val url = if (num > 0) staticFrontUrl(num, shiny) else return
        glide.load(url).error(R.drawable.missingno).into(imageView)
    }

    fun loadAvatar(avatar: String, imageView: ImageView) {
        glide.load(trainerUrl(avatar)).error(R.drawable.ic_pokeball).into(imageView)
    }

    fun getHtmlImageGetter(iconLoader: AssetLoader, maxWidth: Int): Html.ImageGetter {
        val mw = maxWidth - Utils.dpToPx(2f)
        return Html.ImageGetter { source, reqw, reqh ->
            try {
                var d: Drawable? = null
                if (source.startsWith("content://com.majeur.psclient/dex-icon/")) {
                    val species = source.substring(source.lastIndexOf('/') + 1)
                    val icon = iconLoader.dexIconNonSuspend(species)
                    if (icon != null) d = BitmapDrawable(icon)
                } else {
                    d = glide.asDrawable().load(source).submit().get()
                }
                if (d == null) return@ImageGetter null
                val r = d.intrinsicWidth / d.intrinsicHeight.toFloat()
                var w: Int
                var h: Int
                if (reqw != 0 && reqh == 0) {
                    w = reqw; h = (w / r).toInt()
                } else if (reqw == 0 && reqh != 0) {
                    h = reqh; w = (h * r).toInt()
                } else {
                    w = reqw; h = reqh
                }
                val mr = w / mw.toFloat()
                if (mr > 1) { w = mw; h /= mr.toInt() }
                d.setBounds(0, 0, w, h)
                return@ImageGetter d
            } catch (e: ExecutionException) {
                e.printStackTrace(); return@ImageGetter null
            } catch (e: InterruptedException) {
                e.printStackTrace(); return@ImageGetter null
            }
        }
    }
}
