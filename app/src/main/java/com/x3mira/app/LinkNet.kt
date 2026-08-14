package com.x3mira.app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One place where the agent's network requests decide HOW to leave.
 *
 * PREFER THE PHONE. The request is handed down the socket that is already
 * carrying the mirror, the phone performs it over whatever connection it has,
 * and the body comes back the same way. The glasses never touch the internet.
 *
 * That is not a micro-optimisation, it is what makes the thing work away from
 * a Wi-Fi router:
 *
 *  - The phone has cellular. For the glasses to have internet, the phone must
 *    become a hotspot — and tethered traffic is commonly throttled by carriers
 *    on a different budget from on-device traffic, on the same tower. A
 *    request the phone makes itself is on-device.
 *  - The mirror is already using that Wi-Fi link at ~4 Mbps. Under a hotspot
 *    the agent's uploads contend with the video on one channel; the phone's
 *    own cellular leg does not.
 *  - Most importantly it removes the requirement altogether. If the eyewear
 *    needs no internet, the local link only has to be a LINK — which brings
 *    Wi-Fi Direct into range and leaves cellular completely untouched.
 *
 * FALL BACK TO GOING OUT DIRECTLY when there is no link, or when the link
 * cannot carry it. On a desk on home Wi-Fi both routes work and the fallback
 * costs nothing; the alternative is an agent that stops working the moment the
 * mirror drops, which would be a worse trade than the one it fixes.
 */
object LinkNet {

    private const val TAG = "X3MiraNet"

    /** Only used when the phone is unreachable — see the class note. */
    private val direct: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    class Result(val code: Int, val body: ByteArray, val viaPhone: Boolean) {
        val ok: Boolean get() = code in 200..299
        fun text(): String = String(body, Charsets.UTF_8)
    }

    /**
     * [headers] is sent as a JSON object so the whole request survives one
     * hop as four primitives and a byte array, rather than needing a
     * serialisation format on the wire for a map.
     */
    fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray
    ): Result {
        val json = JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } }.toString()

        DexLink.current?.let { link ->
            val reply = runCatching { link.httpViaPhone(url, method, json, body) }.getOrNull()
            // status 0 is the phone saying "I could not perform this at all".
            // Falling through to the direct path then is right: the phone may
            // have no signal while the glasses are on working Wi-Fi.
            if (reply != null && reply.status != 0) {
                Log.i(TAG, "via phone: ${reply.status} ${reply.body.size}b  $url")
                return Result(reply.status, reply.body, true)
            }
            Log.w(TAG, "phone could not carry it — going out directly")
        }

        return runCatching {
            val b = Request.Builder().url(url)
            var contentType = "application/json"
            headers.forEach { (k, v) ->
                if (k.equals("content-type", true)) contentType = v
                b.header(k, v)
            }
            if (method.equals("GET", true) || method.equals("HEAD", true)) {
                b.method(method.uppercase(), null)
            } else {
                b.method(method.uppercase(), body.toRequestBody(contentType.toMediaType()))
            }
            direct.newCall(b.build()).execute().use { resp ->
                Result(resp.code, resp.body?.bytes() ?: ByteArray(0), false)
            }
        }.getOrElse {
            Log.w(TAG, "direct request failed: ${it.message}")
            Result(0, (it.message ?: "request failed").toByteArray(), false)
        }
    }
}
