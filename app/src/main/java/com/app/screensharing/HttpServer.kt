package com.app.screensharing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import com.app.screensharing.HttpServerData.Companion.getClientId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

fun Context.getFileFromAssets(fileName: String): ByteArray {
    return assets.open(fileName).use { inputStream -> inputStream.readBytes() }
        .also { if (it.isEmpty()) throw IllegalStateException("$fileName is empty") }
}

fun randomString(size: Int, allowCapitalLetters: Boolean = false): String {
    val symbols = ('0'..'9') + ('a'..'z') + if (allowCapitalLetters) ('A'..'Z') else emptyList()
    return String(CharArray(size) { symbols.random() })
}

internal class HttpServer(
    context: Context,
//    private val mjpegSettings: MjpegSettings,
//    private val bitmapStateFlow: StateFlow<Bitmap>,
//    private val sendEvent: (MjpegEvent) -> Unit
    private val onStop: () -> Unit,
) {
    //    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val favicon: ByteArray = context.getFileFromAssets("favicon.ico")
    private val logoSvg: ByteArray = context.getFileFromAssets("sym_def_app_icon.svg")
    private val baseIndexHtml = String(context.getFileFromAssets("index.html"), StandardCharsets.UTF_8)
//        .replace("%CONNECTING%", context.getString(R.string.mjpeg_html_stream_connecting))
//        .replace("%STREAM_REQUIRE_PIN%", context.getString(R.string.mjpeg_html_stream_require_pin))
//        .replace("%ENTER_PIN%", context.getString(R.string.mjpeg_html_enter_pin))
//        .replace("%SUBMIT_PIN%", context.getString(R.string.mjpeg_html_submit_pin))
//        .replace("%WRONG_PIN_MESSAGE%", context.getString(R.string.mjpeg_html_wrong_pin))
//        .replace("%ADDRESS_BLOCKED%", context.getString(R.string.mjpeg_html_address_blocked))
//        .replace("%ERROR%", context.getString(R.string.mjpeg_html_error_unspecified))
//        .replace("%DD_SERVICE%", if (debuggable) "mjpeg_client:dev" else "mjpeg_client:prod")
//        .replace("DD_HANDLER", if (debuggable) "[\"http\", \"console\"]" else "[\"http\"]")
//        .replace("%APP_VERSION%", "context.getVersionName()")

    private val indexHtml = AtomicReference(baseIndexHtml)
    private val lastJPEG = AtomicReference(ByteArray(0))
    private val serverData = HttpServerData()
    private val ktorServer = AtomicReference<Pair<CIOApplicationEngine, CompletableDeferred<Unit>>>(null)
    private val bitmapStateFlow = MutableStateFlow(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    private val coroutineScope = CoroutineScope(Job() + Dispatchers.Default)

    fun setBitmap(bitmap: Bitmap) {
        bitmapStateFlow.value = bitmap
    }

    fun start() {
        serverData.configure()

        val serverAddresses = getLocalIpAddress() ?: return
        val server = embeddedServer(CIO, applicationEngineEnvironment {
//            parentCoroutineContext = CoroutineExceptionHandler { _, throwable ->
            //XLog.e(this@HttpServer.getLog("parentCoroutineContext", "coroutineExceptionHandler: $throwable"), throwable)
//            }
            module { appModule() }
            connector {
                host = serverAddresses
                port = PORT
            }
        }) {
            connectionIdleTimeoutSeconds = 10
            shutdownGracePeriod = 0
            shutdownTimeout = 500
        }

        ktorServer.set(server to CompletableDeferred())

        server.environment.monitor.subscribe(ApplicationStarted) {
            logE("monitor KtorStarted: ${it.hashCode()}")
        }

        server.environment.monitor.subscribe(ApplicationStopped) {
            logE("monitor KtorStopped: ${it.hashCode()}")
            coroutineScope.cancel()
            serverData.clear()
            ktorServer.get()?.second?.complete(Unit)
        }

        try {
            server.start(false)
        } catch (cause: CancellationException) {
            cause.printStackTrace()
//            if (cause.cause is SocketException) {
//                XLog.w(getLog("startServer.CancellationException.SocketException", cause.cause.toString()))
//                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.AddressInUseException))
//            } else {
//                XLog.w(getLog("startServer.CancellationException", cause.toString()), cause)
//                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException))
//            }
        } catch (cause: BindException) {
            cause.printStackTrace()
//            XLog.w(getLog("startServer.BindException", cause.toString()))
//            sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.AddressInUseException))
        } catch (cause: Throwable) {
            cause.printStackTrace()
//            XLog.e(getLog("startServer.Throwable"), cause)
//            sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException))
        }
        logE("Start server: Done. Ktor: ${server.hashCode()} at http://$serverAddresses:$PORT")
    }

    suspend fun stop(reloadClients: Boolean) = coroutineScope {
        //XLog.d(getLog("stopServer", "reloadClients: $reloadClients"))
        launch(Dispatchers.Default) {
            ktorServer.getAndSet(null)?.let { (server, stopJob) ->
                if (stopJob.isActive) {
                    if (reloadClients) serverData.notifyClients("RELOAD", timeout = 250)
//                    val hashCode = runCatching { server.hashCode() }.getOrDefault(0)
                    //XLog.i(this@HttpServer.getLog("stopServer", "Ktor: $hashCode"))
                    server.stop(250, 500)
                    //XLog.i(this@HttpServer.getLog("stopServer", "Done. Ktor: $hashCode"))
                }
            }
            //XLog.d(this@HttpServer.getLog("stopServer", "Done"))
        }
    }

    internal suspend fun destroy() {
        //XLog.d(getLog("destroy"))
        serverData.destroy()
        stop(false)
    }

    private fun Application.appModule() {
//        mjpegSettings.data
//            .map { it.htmlBackColor }
//            .distinctUntilChanged()
//            .onEach { htmlBackColor -> indexHtml.set(baseIndexHtml.replace("BACKGROUND_COLOR", htmlBackColor.toColorHexString())) }
//            .launchIn(coroutineScope)

//        mjpegSettings.data
//            .map { Pair(it.htmlEnableButtons && serverData.enablePin.not(), it.htmlBackColor.toColorHexString()) }
//            .distinctUntilChanged()
//            .onEach { (enableButtons, backColor) ->
//                serverData.notifyClients("SETTINGS", JSONObject().put("enableButtons", enableButtons).put("backColor", backColor))
//            }
//            .launchIn(coroutineScope)

        val resultJpegStream = ByteArrayOutputStream()
//        lastJPEG.set(ByteArray(0))

        @OptIn(ExperimentalCoroutinesApi::class)
        val mjpegSharedFlow = bitmapStateFlow
            .map {
                var bitmap = it
                val cropRect = Settings.cropRect
                if ((cropRect.width() < bitmap.width && cropRect.height() <= bitmap.height) ||
                    (cropRect.height() < bitmap.height && cropRect.width() <= bitmap.width)
                ) {
                    bitmap = Bitmap.createBitmap(
                        bitmap,
                        cropRect.left.toInt(),
                        cropRect.top.toInt(),
                        cropRect.width().toInt(),
                        cropRect.height().toInt()
                    )
                }
                resultJpegStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, Settings.imageQuality, resultJpegStream)
                bitmap.recycle()
                resultJpegStream.toByteArray()
            }
            .filter { it.isNotEmpty() }
//            .onEach { jpeg -> lastJPEG.set(jpeg) }
//            .flatMapLatest { jpeg ->
//                flow<ByteArray> { // Send last image every second as keep-alive
//                    while (currentCoroutineContext().isActive) {
//                        emit(jpeg)
//                        delay(1000)
//                    }
//                }
//            }
            .conflate()
            .shareIn(coroutineScope, SharingStarted.Eagerly, 1)
        val crlf = "\r\n".toByteArray()
        val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()
        val multipartBoundary = randomString(20)
        val contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$multipartBoundary")
        val jpegBoundary = "--$multipartBoundary\r\n".toByteArray()

        install(Compression) {
            gzip()
            deflate()
        }
        install(CachingHeaders) { options { _, _ -> CachingOptions(CacheControl.NoStore(CacheControl.Visibility.Private)) } }
        install(DefaultHeaders) { header(HttpHeaders.AccessControlAllowOrigin, "*") }
        install(ForwardedHeaders)
        install(WebSockets)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                if (cause is IOException || cause is IllegalArgumentException || cause is IllegalStateException) return@exception
                //XLog.e(this@appModule.getLog("exception"), RuntimeException("Throwable", cause))
//                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException))
                call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
            }
        }

        routing {
            get("/") { call.respondText(indexHtml.get(), ContentType.Text.Html) }
            get("favicon.ico") { call.respondBytes(favicon, ContentType.Image.XIcon) }
            get("logo.svg") { call.respondBytes(logoSvg, ContentType.Image.SVG) }
            get("start-stop") {
                onStop()
                call.respond(HttpStatusCode.NoContent)
            }
//            get(serverData.jpegFallbackAddress) {
//                if (serverData.isAddressBlocked(call.request.origin.remoteAddress)) call.respond(HttpStatusCode.Forbidden)
//                else {
//                    val clientId = call.request.queryParameters["clientId"] ?: "-"
//                    val remoteAddress = call.request.origin.remoteAddress
//                    val remotePort = call.request.origin.remotePort
//                    serverData.addConnected(clientId, remoteAddress, remotePort)
//                    val bytes = lastJPEG.get()
//                    call.respondBytes(bytes, ContentType.Image.JPEG)
//                    serverData.setNextBytes(clientId, remoteAddress, remotePort, bytes.size)
//                    serverData.setDisconnected(clientId, remoteAddress, remotePort)
//                }
//            }

            webSocket("/socket") {
                val clientId = call.request.getClientId()
                val remoteAddress = call.request.origin.remoteAddress
                serverData.addClient(clientId, this)

                try {
                    for (frame in incoming) {
                        frame as? Frame.Text ?: continue
                        val msg = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue

                        val streamData = JSONObject()
                            .put("enableButtons", true)
                            .put("streamAddress", serverData.streamAddress)

                        when (val type = msg.optString("type").uppercase()) {
                            "HEARTBEAT" -> send("HEARTBEAT", msg.optString("data"))

                            "CONNECT" -> when {
                                !Settings.enablePin -> send("STREAM_ADDRESS", streamData)
                                serverData.isAddressBlocked(remoteAddress) -> send("UNAUTHORIZED", "ADDRESS_BLOCKED")
                                serverData.isClientAuthorized(clientId) -> send("STREAM_ADDRESS", streamData)
                                else -> send("UNAUTHORIZED", null)
                            }

                            "PIN" -> when {
                                serverData.isPinValid(
                                    clientId,
                                    remoteAddress,
                                    msg.optString("data")
                                ) -> send("STREAM_ADDRESS", streamData)

                                serverData.isAddressBlocked(remoteAddress) -> send("UNAUTHORIZED", "ADDRESS_BLOCKED")
                                else -> send("UNAUTHORIZED", "WRONG_PIN")
                            }

                            else -> {
                                logE("socket received unknown message type: $type")
                            }
                        }
                    }
                } catch (ignore: CancellationException) {
                } catch (cause: Exception) {
                    //XLog.w(this@appModule.getLog("socket", "catch: ${cause.localizedMessage}"), cause)
                } finally {
                    logE("socket finally: $clientId")
                    serverData.removeSocket(clientId)
                }
            }

            get(serverData.streamAddress) {
                val clientId = call.request.getClientId()
                val remoteAddress = call.request.origin.remoteAddress
                val remotePort = call.request.origin.remotePort

                if (serverData.isClientAllowed(clientId, remoteAddress).not()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                fun stopClientStream(channel: ByteWriteChannel) =
                    channel.isClosedForWrite || serverData.isAddressBlocked(remoteAddress) ||
                            serverData.isDisconnected(clientId, remoteAddress, remotePort)

                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override val status: HttpStatusCode = HttpStatusCode.OK

                    override val contentType: ContentType = contentType

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        val emmitCounter = AtomicLong(0L)
                        val collectCounter = AtomicLong(0L)
                        mjpegSharedFlow
                            .onStart {
                                logE("onStart Client: $clientId:$remotePort")
                                serverData.addConnected(clientId, remoteAddress, remotePort)
                                channel.writeFully(jpegBoundary, 0, jpegBoundary.size)
                            }
                            .onCompletion {
                                logE("onCompletion Client: $clientId:$remotePort")
                                serverData.setDisconnected(clientId, remoteAddress, remotePort)
                            }
                            .takeWhile { stopClientStream(channel).not() }
                            .map { Pair(emmitCounter.incrementAndGet(), it) }
                            .conflate()
                            .onEach { (emmitCounter, jpeg) ->
                                if (stopClientStream(channel)) return@onEach

                                if (emmitCounter - collectCounter.incrementAndGet() >= 5) {
                                    //XLog.i(this@appModule.getLog("onEach", "Slow connection. Client: $clientId"))
                                    collectCounter.set(emmitCounter)
                                    serverData.setSlowConnection(clientId, remoteAddress, remotePort)
                                }

                                // Write MJPEG frame
                                val jpegSizeText = jpeg.size.toString().toByteArray()
                                channel.writeFully(jpegBaseHeader, 0, jpegBaseHeader.size)
                                channel.writeFully(jpegSizeText, 0, jpegSizeText.size)
                                channel.writeFully(crlf, 0, crlf.size)
                                channel.writeFully(crlf, 0, crlf.size)
                                channel.writeFully(jpeg, 0, jpeg.size)
                                channel.writeFully(crlf, 0, crlf.size)
                                channel.writeFully(jpegBoundary, 0, jpegBoundary.size)
                                // Write MJPEG frame

                                val size =
                                    jpegBaseHeader.size + jpegSizeText.size + crlf.size * 3 + jpeg.size + jpegBoundary.size
                                serverData.setNextBytes(clientId, remoteAddress, remotePort, size)
                            }
                            .catch { /* Empty intentionally */ }
                            .collect()
                    }
                })
            }
        }
    }

    private suspend fun DefaultWebSocketSession.send(type: String, data: Any?) {
        if (isActive) send(JSONObject().put("type", type).apply { if (data != null) put("data", data) }.toString())
    }

    companion object {
        const val PORT = 8081

        fun getLocalIpAddress(): String? {
            try {
                val en = NetworkInterface.getNetworkInterfaces()
                while (en.hasMoreElements()) {
                    val enumIpAddr = en.nextElement().inetAddresses
                    while (enumIpAddr.hasMoreElements()) {
                        val inetAddress = enumIpAddr.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.getHostAddress()
                        }
                    }
                }
            } catch (ex: SocketException) {
                ex.printStackTrace()
            }
            return null
        }
    }

    object Settings {
        var enablePin = false
        var pin = "123456"
        var cropRect = RectF()
        var imageQuality = 50
    }
}

fun logE(msg: String) {
    Log.e("LOGGER_TAG", msg)
}