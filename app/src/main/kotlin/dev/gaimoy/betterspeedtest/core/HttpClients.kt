package dev.gaimoy.betterspeedtest.core

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    val random = SecureRandom()

    val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 常规请求(client 复用连接池)。 */
    private val common by lazy { baseBuilder(15).build() }
    private val privateControl by lazy { baseBuilder(12).applyInsecure().build() }
    private val transferDispatcher by lazy {
        okhttp3.Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 128
        }
    }

    fun plain(timeoutSec: Long): OkHttpClient =
        common.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS).callTimeout(timeoutSec, TimeUnit.SECONDS).build()

    /** Fixed Cnspeed control endpoint compatibility; certificate authenticity remains unverified. */
    fun insecure(timeoutSec: Long): OkHttpClient =
        privateControl.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS).callTimeout(timeoutSec, TimeUnit.SECONDS).build()

    /** 测速传输专用:HTTP/1.1、每主机大连接池、跟随重定向。 */
    fun cdnTransfer(timeoutSec: Long): OkHttpClient =
        plain(timeoutSec).newBuilder()
            .dispatcher(transferDispatcher)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()

    private fun baseBuilder(timeoutSec: Long): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .dns(BoundedDnsResolver.shared)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(64, 30, TimeUnit.SECONDS))

    private fun OkHttpClient.Builder.applyInsecure(): OkHttpClient.Builder {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trustAll), random)
        sslSocketFactory(ssl.socketFactory, trustAll)
        // No verified certificate/public key is available for pinning. Restrict this exception to
        // the existing private control hostname and never carry it through a redirect.
        hostnameVerifier { host, _ -> host.equals("dlcv2.cnspeedtest.cn", ignoreCase = true) }
        followRedirects(false)
        followSslRedirects(false)
        return this
    }
}
