package com.example.ui

import android.content.Context
import android.os.Build
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import com.example.player.PlaybackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Channel logo loading (Coil).
 *
 * - IPv4 first + short connect timeout: on many home networks a host's IPv6 route is dead and the default
 *   "try IPv6, wait for the timeout, then IPv4" makes some logos take many seconds.
 * - Client-shape fallback: hosts disagree about which clients they accept (Wikimedia refuses "okhttp/x",
 *   some CDNs refuse Chrome-like agents that lack browser headers), so on 400/401/403/406/429 the request is
 *   retried with the other shapes and the one that worked is remembered per host.
 * - [prefetch] warms the disk cache in the background (one request at a time) so logos are already there
 *   when the list is scrolled, like players that pre-download them.
 * - Failures are written to the playback log (tag LOGO) so they can be diagnosed.
 */
object LogoLoader {
    private const val ACCEPT = "image/webp,image/png,image/jpeg,image/gif,image/*;q=0.8,*/*;q=0.5"

    private val userAgents: List<String> by lazy {
        listOf(
            // browser-like (Wikimedia, Amazon CDN, ...)
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36 tvBuddy/1.0",
            // what a plain Android HttpURLConnection app sends
            "Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID})",
            // OkHttp's default
            "okhttp/4.12.0"
        )
    }

    private val retryCodes = setOf(400, 401, 403, 406, 429)
    private val preferredAgent = ConcurrentHashMap<String, Int>()
    private val prefetched: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val errorsLogged = AtomicInteger(0)

    private object Ipv4FirstDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    private class AgentFallbackInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val base = chain.request()
            val host = base.url.host
            val first = preferredAgent[host] ?: 0
            val order = listOf(first) + userAgents.indices.filter { it != first }
            var response: Response? = null
            for (idx in order) {
                response?.close()
                response = chain.proceed(
                    base.newBuilder()
                        .header("User-Agent", userAgents[idx])
                        .header("Accept", ACCEPT)
                        .build()
                )
                if (response.isSuccessful) {
                    preferredAgent[host] = idx
                    return response
                }
                if (response.code !in retryCodes) return response
            }
            return response!!
        }
    }

    fun install(context: Context) {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(Ipv4FirstDns)
            .dispatcher(Dispatcher().apply { maxRequests = 48; maxRequestsPerHost = 8 })
            .addInterceptor(AgentFallbackInterceptor())
            .build()
        Coil.setImageLoader(
            ImageLoader.Builder(context.applicationContext)
                .okHttpClient(client)
                .respectCacheHeaders(false) // logo hosts often send no-store; keep them cached on the TV
                .eventListener(object : EventListener {
                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        if (errorsLogged.incrementAndGet() <= 60) {
                            val t = result.throwable
                            PlaybackLog.warn(
                                "LOGO",
                                "failed ${request.data}: ${t.javaClass.simpleName}: ${t.message?.take(120)}"
                            )
                        }
                    }
                })
                .build()
        )
    }

    /**
     * Downloads logos one at a time into the disk cache (nothing is kept in memory), skipping the ones
     * already handled in this run. Cancels cleanly when the caller's coroutine is cancelled.
     */
    suspend fun prefetch(context: Context, urls: List<String>, max: Int = 400) = withContext(Dispatchers.IO) {
        val loader = Coil.imageLoader(context)
        for (url in urls.asSequence().filter { it.startsWith("http") }.distinct().take(max)) {
            coroutineContext.ensureActive()
            if (!prefetched.add(url)) continue
            loader.execute(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(128, 128)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build()
            )
        }
    }
}
