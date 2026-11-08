package top.kagg886.cctr.desktop.page.welcome

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openqa.selenium.SessionNotCreatedException
import top.kagg886.cctr.desktop.util.BaseAction
import top.kagg886.cctr.desktop.util.BaseState
import top.kagg886.cctr.desktop.util.BaseViewModel
import top.kagg886.cctr.desktop.util.EdgeConfig
import top.kagg886.cctr.desktop.util.checkEdgeDriverExists
import top.kagg886.cctr.desktop.util.edge_config
import top.kagg886.cctr.desktop.util.getEdgeDownloadURL
import top.kagg886.cctr.desktop.util.getEdgeDriverFile
import top.kagg886.cctr.desktop.util.getInstalledEdgeVersion
import top.kagg886.cctr.desktop.util.msedgedriverName
import top.kagg886.cctr.driver.WebDriverDispatcher
import top.kagg886.cctr.util.unzip
import top.kagg886.cctr.util.useTempFileSuspend
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

private val log = KtorSimpleLogger("WelcomeViewModel")
private const val DOWNLOAD_BUFFER_SIZE = 8192L

class WelcomeViewModel : BaseViewModel<WelcomeViewModelState, WelcomeViewModelAction>() {

    override fun initState(): WelcomeViewModelState = WelcomeViewModelState.Default

    override suspend fun onAction(state: WelcomeViewModelState, action: WelcomeViewModelAction) {
        when (action) {
            is WelcomeViewModelAction.StartCheckingEdgeDriver -> checkAndInitEdgeDriver(action.config)
            is WelcomeViewModelAction.StartDownloadEdgeDriver -> {
                log.info("start checking url...")
                val loading = WelcomeViewModelState.LoadingEdgeDriver(mutableStateOf("Checking driver url..."))
                setState(loading)
                downloadEdgeDriver(action.edgeVersion, loading)
            }
        }
    }

    private suspend fun checkAndInitEdgeDriver(config: EdgeConfig) {
        log.info("start checking driver")
        val loading = WelcomeViewModelState.LoadingEdgeDriver()
        setState(loading)

        loading.text.value = "Checking Edge version..."
        val edgeVersion = getInstalledEdgeVersion(config.binary) ?: config.version.takeIf {
            it.isNotBlank() && it != DEFAULT_EDGE_VERSION
        }

        if (edgeVersion == null) {
            log.warn("edge version detect failed")
            setState(WelcomeViewModelState.LoadingFailure("Failed to detect Microsoft Edge version"))
            return
        }

        if (edgeVersion != config.version) {
            log.info("detected edge version: $edgeVersion")
        }

        if (!checkEdgeDriverExists(edgeVersion)) {
            log.warn("edge driver: $edgeVersion not exists, downloading")
            if (!downloadEdgeDriver(edgeVersion, loading)) {
                return
            }
        }

        initEdgeDriver(config, edgeVersion, loading)
    }

    private suspend fun initEdgeDriver(
        config: EdgeConfig,
        edgeVersion: String,
        loading: WelcomeViewModelState.LoadingEdgeDriver,
    ) {
        val result = kotlin.runCatching {
            loading.text.value = "Initializing WebDriver..."
            log.info("init edge driver...")
            withContext(Dispatchers.IO) {
                if (!WebDriverDispatcher.inited) {
                    WebDriverDispatcher.init {
                        driverFile = getEdgeDriverFile(edgeVersion).resolve(msedgedriverName).apply {
                            log.info("edge-driver-file: $this")
                        }
                        driverPoolSize = config.pool
                        executableFile = File(config.binary)
                    }
                } else {
                    log.warn("edge driver already inited")
                }
            }
        }

        result.onFailure {
            log.warn("init edge driver failed:", it)
            if (it is SessionNotCreatedException) {
                setState(WelcomeViewModelState.LoadingFailure("Driver init failed. Please check logs."))
                return
            }
            setState(WelcomeViewModelState.LoadingFailure("Unknown error"))
            return
        }

        if (edgeVersion != config.version) {
            edge_config.set {
                version = edgeVersion
            }
        }
        log.info("init edge driver success")
        setState(WelcomeViewModelState.LoadingSuccess)
    }

    private suspend fun downloadEdgeDriver(
        edgeVersion: String,
        loading: WelcomeViewModelState.LoadingEdgeDriver,
    ): Boolean {
        loading.text.value = "Checking driver download url..."
        val url = getEdgeDownloadURL(edgeVersion)
        log.info("downloading: $url")
        var success = false

        runCatching {
            HttpClient {
                install(HttpTimeout) {
                    requestTimeoutMillis = 15.minutes.toLong(DurationUnit.MILLISECONDS)
                    connectTimeoutMillis = 15.minutes.toLong(DurationUnit.MILLISECONDS)
                    socketTimeoutMillis = 15.minutes.toLong(DurationUnit.MILLISECONDS)
                }
            }.use { client ->
                client.prepareGet(url).execute { response ->
                    if (response.status.isSuccess().not()) {
                        log.warn("url $url not exists")
                        setState(WelcomeViewModelState.LoadingFailure("Edge driver for $edgeVersion was not found"))
                        return@execute
                    }

                    val channel: ByteReadChannel = response.bodyAsChannel()
                    useTempFileSuspend { tempFile ->
                        while (!channel.isClosedForRead) {
                            val packet = channel.readRemaining(DOWNLOAD_BUFFER_SIZE)
                            while (!packet.isEmpty) {
                                val bytes = packet.readBytes()
                                tempFile.appendBytes(bytes)
                                response.contentLength()?.let { length ->
                                    val percent = String.format(
                                        "%.2f",
                                        (tempFile.length().toFloat() / length.toFloat()) * 100
                                    )
                                    loading.text.value = "Downloading... $percent%"
                                }
                            }
                        }
                        log.info("url $url download success, unzipping...")
                        loading.text.value = "Downloading... success!, unzipping..."
                        tempFile.unzip(out = getEdgeDriverFile(edgeVersion))
                        edge_config.set {
                            version = edgeVersion
                        }
                        success = true
                    }
                }
            }
        }.onFailure {
            log.warn("download edge driver failed:", it)
            setState(WelcomeViewModelState.LoadingFailure("Driver download failed. Please check your network."))
        }

        return success
    }

    private companion object {
        const val DEFAULT_EDGE_VERSION = "1.0.0"
    }
}

sealed interface WelcomeViewModelState : BaseState {
    data object Default : WelcomeViewModelState

    data class LoadingEdgeDriver(
        var text: MutableState<String> = mutableStateOf("Please wait..."),
    ) : WelcomeViewModelState

    data object LoadingSuccess : WelcomeViewModelState
    data class LoadingFailure(
        val cause: String? = null,
    ) : WelcomeViewModelState
}

sealed interface WelcomeViewModelAction : BaseAction {
    data class StartCheckingEdgeDriver(
        val config: EdgeConfig
    ) : WelcomeViewModelAction

    data class StartDownloadEdgeDriver(val edgeVersion: String) : WelcomeViewModelAction
}
