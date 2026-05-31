package com.majeur.psclient.model.pokemon

import com.majeur.psclient.util.toId
import java.io.Serializable


open class BasePokemon : Serializable {

    var species: String = ""
        set(value) {
            field = value
            baseSpecies = ""
            forme = null
            spriteId = ""
            computeForme(value)
        }

    var baseSpecies: String = ""
        private set
    var forme: String? = null
        private set
    var spriteId: String = ""
        private set

    private fun computeForme(species: String){
        val id = species.toId()
        val excluded = arrayOf("hooh", "hakamoo", "jangmoo", "kommoo", "porygonz")
        if (!excluded.contains(id)) {
            if (id == "kommoototem") {
                baseSpecies = "Kommo-o"
                forme = "Totem"
            } else if (species.contains('-')) {
                baseSpecies = species.substringBefore('-')
                forme = species.substringAfter('-')
            }
        }
        if (id != "yanmega" && id.endsWith("mega")) {
            baseSpecies = id.removeSuffix("mega")
            forme = "mega"
        } else if (id.endsWith("primal")) {
            baseSpecies = id.removeSuffix("primal")
            forme = "primal"
        } else if (id.endsWith("alola")) {
            baseSpecies = id.removeSuffix("alola")
            forme = "alola"
        }

        if (baseSpecies.isEmpty()) baseSpecies = species

        // PS sprite filenames use lowercase with hyphens preserved (e.g. tauros-paldea-combat).
        // toId() strips hyphens which breaks multi-part forme names — use toLowercase() instead.
        val baseId = baseSpecies.lowercase(java.util.Locale.ROOT)
        val formeId = (forme ?: "").lowercase(java.util.Locale.ROOT)
        spriteId = if (formeId.isEmpty()) baseId else "$baseId-$formeId"
        if (spriteId.endsWith("totem")) spriteId = spriteId.removeSuffix("totem")
        spriteId = spriteId.removeSuffix("-")
    }
}
