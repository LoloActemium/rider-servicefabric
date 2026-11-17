package ch.actemium.rider.servicefabric.health

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

/** Unsecured REST calls to the local cluster HTTP gateway; never throws. */
class SfHealthClient {

    fun get(url: String, timeoutMs: Int = SfHealthRules.TIMEOUT_MS): ApiOutcome = try {
        HttpRequests.request(url)
            .connectTimeout(timeoutMs)
            .readTimeout(timeoutMs)
            .throwStatusCodeException(false)
            .accept("application/json")
            .connect { request ->
                val code = (request.connection as HttpURLConnection).responseCode
                val body = if (code in 200..299 && code != 204) request.readString() else ""
                ApiOutcome.Response(code, body)
            }
    } catch (e: Exception) {
        LOG.debug("GET $url failed", e)
        when (generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is ConnectException || it is SocketTimeoutException }) {
            is ConnectException -> ApiOutcome.Refused
            is SocketTimeoutException -> ApiOutcome.Timeout("timeout after ${timeoutMs} ms")
            else -> ApiOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private val LOG = logger<SfHealthClient>()
    }
}
