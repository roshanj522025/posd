package com.majeur.psclient.io.glide

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import okhttp3.OkHttpClient
import java.io.InputStream

@GlideModule
class PSGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // Use OkHttp with a Referer header so PS sprite server doesn't 403 us.
        // play.pokemonshowdown.com blocks requests without a valid PS Referer.
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Referer", "https://play.pokemonshowdown.com/")
                    .header("User-Agent", "Mozilla/5.0 (Android)")
                    .build()
                chain.proceed(request)
            }
            .build()

        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(client)
        )
    }

    // Disable manifest parsing for performance
    override fun isManifestParsingEnabled() = false
}
