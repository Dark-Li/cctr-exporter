package top.kagg886.cctr.desktop.page.add

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import top.kagg886.cctr.api.CCTRLoginSession
import top.kagg886.cctr.api.CCTRUser
import top.kagg886.cctr.api.modules.ChapterType
import top.kagg886.cctr.api.modules.Practice
import top.kagg886.cctr.api.modules.QuestionType
import top.kagg886.cctr.backend.dao.Task
import top.kagg886.cctr.backend.dao.Tasks
import top.kagg886.cctr.backend.service.TaskService
import top.kagg886.cctr.desktop.util.BaseAction
import top.kagg886.cctr.desktop.util.BaseState
import top.kagg886.cctr.desktop.util.BaseViewModel
import top.kagg886.cctr.desktop.util.cctr_last_modifier

private val log = KtorSimpleLogger("AddViewModel")

class AddViewModel(private val retryTaskId: Int? = null) : BaseViewModel<AddViewModelState, AddViewModelAction>() {
    private val loginSession = CCTRLoginSession()
    private val loginMutex = Mutex()
    private val _captcha = MutableStateFlow(CaptchaState())
    val captcha = _captcha.asStateFlow()
    private var retryTask: Task? = null
    private var sessionTransferred = false

    override fun initState(): AddViewModelState = AddViewModelState.LoginToCCTR()

    override suspend fun onAction(state: AddViewModelState, action: AddViewModelAction) {
        when (action) {
            AddViewModelAction.RefreshCaptcha -> {
                if (state !is AddViewModelState.LoginToCCTR || !loginMutex.tryLock()) return
                try {
                    if (retryTaskId != null && retryTask == null) {
                        retryTask = TaskService.query { where { Tasks.id eq retryTaskId } }.firstOrNull()
                        val task = retryTask
                        if (task == null) {
                            setState(AddViewModelState.LoginToCCTR("原任务不存在，请返回后重新添加"))
                            return
                        }
                        cctr_last_modifier.set {
                            schoolId = task.schoolId
                            userName = task.username
                            password = task.password
                        }
                    }
                    loadCaptcha()
                } finally {
                    loginMutex.unlock()
                }
            }
            is AddViewModelAction.LoginToCCTR -> {
                if (state !is AddViewModelState.LoginToCCTR || !loginMutex.tryLock()) return
                try {
                    login(action)
                } finally {
                    loginMutex.unlock()
                }
            }
            is AddViewModelAction.SendConfig -> {
                setState(AddViewModelState.SelectExportType(action.user, action.config))
            }
        }
    }

    private suspend fun login(action: AddViewModelAction.LoginToCCTR) {
        val message = when {
            action.schoolId.isBlank() -> "请输入学校代号"
            action.username.isBlank() -> "请输入用户名"
            action.password.isBlank() -> "请输入密码"
            action.captcha.isBlank() -> "请输入验证码"
            _captcha.value.image == null -> "请先获取验证码"
            retryTaskId != null && retryTask == null -> "原任务尚未加载，请刷新验证码后重试"
            retryTask?.let { it.schoolId != action.schoolId.trim() || it.username != action.username.trim() } == true ->
                "请使用原任务的学校代号和账号登录"
            else -> null
        }
        if (message != null) {
            setState(AddViewModelState.LoginToCCTR(message))
            return
        }
        setState(AddViewModelState.Loading)
        try {
            cctr_last_modifier.set {
                schoolId = action.schoolId.trim()
                userName = action.username.trim()
                password = action.password
            }
            val user = loginSession.login(action.schoolId.trim(), action.username.trim(), action.password, action.captcha)
            log.info("login to CCTR successful")
            val task = retryTask
            setState(if (task == null) {
                AddViewModelState.WaitChoose(user)
            } else {
                AddViewModelState.SelectExportType(user, task.config, task.exportType)
            })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            setState(AddViewModelState.LoginToCCTR(error.message ?: "登录失败，请重试"))
            loadCaptcha()
        }
    }

    private suspend fun loadCaptcha() {
        val generation = _captcha.value.generation + 1
        _captcha.value = CaptchaState(loading = true, generation = generation)
        try {
            val image = withContext(Dispatchers.IO) {
                Image.makeFromEncoded(loginSession.refreshCaptcha()).toComposeImageBitmap()
            }
            _captcha.value = CaptchaState(image = image, generation = generation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _captcha.value = CaptchaState(error = error.message ?: "图片加载失败", generation = generation)
        }
    }

    fun transferLoginSession() {
        sessionTransferred = true
    }

    fun closeLoginSession() {
        if (!sessionTransferred) loginSession.close()
    }
}

data class CaptchaState(
    val image: ImageBitmap? = null,
    val loading: Boolean = false,
    val error: String = "",
    val generation: Int = 0,
)

sealed interface AddViewModelState : BaseState {
    data object Loading : AddViewModelState
    data class LoginToCCTR(val msg: String = "") : AddViewModelState
    data class WaitChoose(val client: CCTRUser) : AddViewModelState
    data class SelectExportType(
        val client: CCTRUser,
        val config: Map<Practice, Map<ChapterType, List<QuestionType>>>,
        val initialExportType: Tasks.ExportType = Tasks.ExportType.entries.first(),
    ) : AddViewModelState
}

sealed interface AddViewModelAction : BaseAction {
    data object RefreshCaptcha : AddViewModelAction
    data class LoginToCCTR(val schoolId: String, val username: String, val password: String, val captcha: String) : AddViewModelAction
    data class SendConfig(val user: CCTRUser, val config: Map<Practice, Map<ChapterType, List<QuestionType>>>) : AddViewModelAction
}
