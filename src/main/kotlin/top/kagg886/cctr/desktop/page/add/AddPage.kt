package top.kagg886.cctr.desktop.page.add

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import moe.tlaster.precompose.navigation.RouteBuilder
import moe.tlaster.precompose.navigation.path
import moe.tlaster.precompose.navigation.transition.NavTransition
import moe.tlaster.precompose.viewmodel.viewModel
import top.kagg886.cctr.api.modules.ChapterType
import top.kagg886.cctr.api.modules.Practice
import top.kagg886.cctr.api.modules.QuestionType
import top.kagg886.cctr.backend.dao.Task
import top.kagg886.cctr.backend.service.TaskService
import top.kagg886.cctr.backend.dao.Tasks
import top.kagg886.cctr.backend.dao.Tasks.exportType
import top.kagg886.cctr.backend.dao.Tasks.schoolId
import top.kagg886.cctr.backend.task.TaskManager
import top.kagg886.cctr.desktop.LocalNavigation
import top.kagg886.cctr.desktop.LocalNavigationShower
import top.kagg886.cctr.desktop.LocalSnackBar
import top.kagg886.cctr.desktop.page.home.HOME_ROUTE
import top.kagg886.cctr.desktop.util.cctr_last_modifier
import java.time.LocalDateTime

@Composable
fun AddPage(retryTaskId: Int? = null, goBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(onClick = {goBack()}) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "go_back")
            }

            val model = viewModel {
                AddViewModel(retryTaskId)
            }
            LaunchedEffect(model) { model.dispatch(AddViewModelAction.RefreshCaptcha) }
            DisposableEffect(model) {
                onDispose { model.closeLoginSession() }
            }

            val _state by model.state.collectAsState()
            val cctr = cctr_last_modifier.collectAsState().value
            var schoolId by remember(cctr.schoolId) { mutableStateOf(cctr.schoolId) }
            var userName by remember(cctr.userName) { mutableStateOf(cctr.userName) }
            var password by remember(cctr.password) { mutableStateOf(cctr.password) }
            val captcha by model.captcha.collectAsState()
            var captchaText by remember(captcha.generation) { mutableStateOf("") }
            AnimatedContent(_state, modifier = Modifier.fillMaxSize()) {state->
                when(state) {
                    is AddViewModelState.LoginToCCTR -> {
                        val scope = rememberCoroutineScope()
                        val snack = LocalSnackBar.current
                        LaunchedEffect(state.msg) {
                            if (state.msg.isNotBlank()) {
                                scope.launch {
                                    snack.showSnackbar(state.msg)
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.fillMaxSize().padding(48.dp).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (retryTaskId != null) Text("重新登录以重试任务 #$retryTaskId")
                            if (state.msg.isNotBlank()) {
                                SelectionContainer {
                                    Text(state.msg, color = MaterialTheme.colors.error, modifier = Modifier.width(280.dp))
                                }
                            }
                            OutlinedTextField(
                                value = schoolId,
                                onValueChange = { schoolId = it },
                                label = { Text("学校代号") },
                                singleLine = true,
                                modifier = Modifier.width(280.dp),
                            )
                            OutlinedTextField(
                                value = userName,
                                onValueChange = { userName = it },
                                label = { Text("用户名") },
                                singleLine = true,
                                modifier = Modifier.width(280.dp),
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("密码") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.width(280.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.width(280.dp).heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                when {
                                    captcha.loading -> CircularProgressIndicator(Modifier.size(24.dp))
                                    captcha.image != null -> Image(
                                        bitmap = captcha.image!!,
                                        contentDescription = "登录验证码",
                                        modifier = Modifier.size(140.dp, 48.dp),
                                    )
                                    else -> Text("验证码未加载")
                                }
                                TextButton(
                                    enabled = !captcha.loading,
                                    onClick = { model.dispatch(AddViewModelAction.RefreshCaptcha) },
                                ) { Text("刷新验证码") }
                            }
                            if (captcha.error.isNotBlank()) {
                                Text("验证码加载失败，请刷新重试", color = MaterialTheme.colors.error, modifier = Modifier.width(280.dp))
                            }
                            OutlinedTextField(
                                value = captchaText,
                                onValueChange = { captchaText = it },
                                label = { Text("验证码") },
                                singleLine = true,
                                enabled = !captcha.loading && captcha.image != null,
                                modifier = Modifier.width(280.dp),
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                enabled = !captcha.loading && captcha.image != null && captchaText.isNotBlank(),
                                onClick = { model.dispatch(AddViewModelAction.LoginToCCTR(schoolId, userName, password, captchaText)) },
                            ) { Text("登录") }
                        }
                    }
                    AddViewModelState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(),contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text("Loading...")
                            }
                        }
                    }
                    is AddViewModelState.WaitChoose -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val conf = remember {
                                mutableStateOf<Map<Practice,Map<ChapterType,List<QuestionType>>>>(mapOf())
                            }
                            ChooseConfig(user = state.client, config = conf)
                            FloatingActionButton(
                                onClick = {
                                   model.dispatch(AddViewModelAction.SendConfig(user = state.client,config = conf.value))
                                },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "next")
                            }
                        }
                    }

                    is AddViewModelState.SelectExportType -> {
                        //TODO 导出方式
                        Column(modifier = Modifier.fillMaxWidth(0.8f),horizontalAlignment = Alignment.CenterHorizontally) {

                            Spacer(modifier = Modifier.weight(1f))
                            val radioOptions = Tasks.ExportType.entries.toList()
                            val (selectedOption, onOptionSelected) = remember(state.initialExportType) { mutableStateOf(state.initialExportType) }
                            Text("选择你的导出方式")
                            Column(Modifier.selectableGroup()) {
                                radioOptions.forEach { text ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(56.dp)
                                            .selectable(
                                                selected = (text == selectedOption),
                                                onClick = { onOptionSelected(text) },
                                                role = Role.RadioButton
                                            )
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = (text == selectedOption),
                                            onClick = null
                                        )
                                        Text(
                                            text = text.name,
                                            style = MaterialTheme.typography.body1.merge(),
                                            modifier = Modifier.padding(start = 16.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            val navigator = LocalNavigation.current
                            val scope = rememberCoroutineScope()
                            val snack = LocalSnackBar.current
                            var submitting by remember { mutableStateOf(false) }
                            FloatingActionButton(onClick = {
                                if (submitting) return@FloatingActionButton
                                submitting = true
                                scope.launch {
                                    try {
                                        val task = TaskManager.commitTask(Task(
                                        id = -1,
                                        schoolId = state.client.schoolId,
                                        username = state.client.userName,
                                        password = state.client.passWord,
                                        status = Tasks.TaskStatus.WAITING,
                                        exportType = selectedOption,
                                        createTime = LocalDateTime.now(),
                                        config = state.config
                                        ), state.client)
                                        model.transferLoginSession()
                                        launch {
                                            snack.showSnackbar("提交成功!，任务id为：${task.id}")
                                        }
                                        navigator.navigate(HOME_ROUTE)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        submitting = false
                                        snack.showSnackbar(error.message ?: "提交失败，请重试")
                                    }
                                }
//                                model.dispatch(AddViewModelAction.StartToExport(state.client,state.config,selectedOption))
                            }){
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "")
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

}

const val ADD_ROUTE = "ADD_ROUTE"

fun RouteBuilder.configureAddPage() {
    scene(ADD_ROUTE, navTransition = NavTransition(
        createTransition = slideInHorizontally { it } + fadeIn(),
        destroyTransition = slideOutHorizontally { it }  + fadeOut()
    )) {
        AddScene()
    }
    scene("$ADD_ROUTE/retry/{id}", navTransition = NavTransition(
        createTransition = slideInHorizontally { it } + fadeIn(),
        destroyTransition = slideOutHorizontally { it } + fadeOut(),
    )) {
        AddScene(it.path<String>("id")?.toIntOrNull())
    }
}

@Composable
private fun AddScene(retryTaskId: Int? = null) {
    val nav = LocalNavigation.current
    var show by LocalNavigationShower.current
    DisposableEffect(Unit) {
        show = false
        onDispose { show = true }
    }
    AddPage(retryTaskId) {
        nav.goBack()
    }
}
