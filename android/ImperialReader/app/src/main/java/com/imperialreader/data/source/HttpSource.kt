package com.imperialreader.data.source

import com.imperialreader.App
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

abstract class HttpSource : Source {

    protected val client get() = App.instance.network.client

    open val extraHeaders: Map<String, String> get() = mapOf("Referer" to "$baseUrl/")

    override fun imageHeaders(): Map<String, String> = extraHeaders

    override suspend fun searchByAuthor(query: String, page: Int): MangasPage =
        super<Source>.searchByAuthor(query, page)

    protected suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(Request.Builder().url(url), headers)

    protected suspend fun postString(
        url: String,
        form: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): String {
        val body = if (form.isEmpty()) {
            ByteArray(0).toRequestBody(null)
        } else {
            FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        }
        return execute(Request.Builder().url(url).post(body), headers)
    }

    private suspend fun execute(builder: Request.Builder, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            headers.forEach { (k, v) -> builder.header(k, v) }
            val request = builder.build()
            client.await(request).use { resp ->
                val text = resp.body?.string().orEmpty()
                val cfChallenge = resp.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true
                if (!resp.isSuccessful) {
                    val cf = resp.header("Server")?.contains("cloudflare", true) == true ||
                        resp.header("cf-ray") != null
                    val browserCheck = resp.code == 503 &&
                        (text.contains("browser_check", ignoreCase = true) ||
                            text.contains("document.cookie", ignoreCase = true))
                    if (cfChallenge || (cf && (resp.code == 403 || resp.code == 503)) || browserCheck) {
                        throw CloudflareException(request.url.toString())
                    }
                    throw HttpStatusException(resp.code, request.url.toString())
                }
                if (cfChallenge) throw CloudflareException(request.url.toString())
                text
            }
        }

    protected fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    protected fun absoluteUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> baseUrl.trimEnd('/') + url
        else -> baseUrl.trimEnd('/') + "/" + url
    }
}
