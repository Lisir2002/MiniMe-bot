package com.mini.bot.feature.settings.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mini.bot.R
import com.mini.bot.core.theme.Radius
import com.mini.bot.core.theme.Spacing
import com.mini.bot.core.theme.semanticColors
import com.mini.bot.core.ui.AppTextField
import com.mini.bot.feature.settings.data.local.CloudDeployStore
import com.mini.bot.feature.settings.data.remote.CloudContainerSummary
import com.mini.bot.feature.settings.data.remote.CloudControlPlaneClient
import com.mini.bot.feature.settings.data.remote.CloudControlPlaneException
import com.mini.bot.feature.settings.data.remote.CloudControlPlaneProbe
import com.mini.bot.feature.settings.data.remote.CloudControlPlaneProbeResult
import com.mini.bot.feature.settings.data.remote.CloudDeployRequest
import com.mini.bot.feature.settings.data.remote.CloudDeploymentImage
import com.mini.bot.feature.settings.data.remote.CloudResourcePolicy
import com.mini.bot.feature.settings.data.remote.CloudResourcePreset
import com.mini.bot.feature.settings.domain.model.CloudControlPlaneEndpoint
import com.mini.bot.feature.settings.domain.model.CloudDeviceIdentity
import com.mini.bot.feature.settings.domain.model.CloudEndpointError
import com.mini.bot.feature.settings.domain.model.CloudInstallCommand
import com.mini.bot.feature.settings.domain.model.CloudEndpointParseResult
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Globe
import kotlinx.coroutines.launch

private const val CloudDeployTotalSteps = 5

private enum class CloudDeployStep {
    Connect,
    Access,
    Pair,
    Container,
    Done
}

/**
 * 云端部署向导：连接控制面、确认访问方式、配对设备、创建第一个容器。
 *
 * 地址检测只走未鉴权 /health；配对后的请求才带设备 Token。域名仍是可选分支，
 * 没有域名时完整流程走 IP + HTTP。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudDeployScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val store = remember(applicationContext) { CloudDeployStore(applicationContext) }
    var savedUrl by rememberSaveable { mutableStateOf(store.loadEndpoint()) }

    var step by rememberSaveable { mutableStateOf(CloudDeployStep.Connect) }
    var input by rememberSaveable { mutableStateOf(savedUrl) }
    var error by remember { mutableStateOf<CloudEndpointError?>(null) }
    var checking by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<CloudControlPlaneProbeResult?>(null) }
    var endpoint by remember { mutableStateOf<CloudControlPlaneEndpoint?>(null) }
    var deviceToken by remember { mutableStateOf(store.loadDeviceToken().orEmpty()) }
    var createdContainer by remember { mutableStateOf<CloudContainerSummary?>(null) }
    val probe = remember { CloudControlPlaneProbe() }
    val scope = rememberCoroutineScope()
    val installCommand = CloudInstallCommand.forInput(input)

    fun resetProbe() {
        error = null
        probeResult = null
    }

    fun checkConnection() {
        resetProbe()
        when (val result = CloudControlPlaneEndpoint.normalize(input)) {
            is CloudEndpointParseResult.Error -> error = result.error
            is CloudEndpointParseResult.Success -> {
                checking = true
                scope.launch {
                    val resultProbe = probe.check(result.endpoint)
                    checking = false
                    probeResult = resultProbe
                    if (resultProbe.reachable) {
                        endpoint = result.endpoint
                        input = result.endpoint.baseUrl
                        savedUrl = result.endpoint.baseUrl
                        store.saveEndpoint(result.endpoint.baseUrl)
                        deviceToken = store.loadDeviceToken().orEmpty()
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_deploy_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            StepHeader(step)

            when (step) {
                CloudDeployStep.Connect -> ConnectStep(
                    input = input,
                    onInputChange = {
                        input = it
                        if (error != null || probeResult != null) resetProbe()
                    },
                    error = error,
                    checking = checking,
                    probeResult = probeResult,
                    savedUrl = savedUrl,
                    installCommand = installCommand,
                    onCheck = ::checkConnection,
                    onNext = {
                        if (endpoint != null && probeResult?.reachable == true) {
                            step = CloudDeployStep.Access
                        }
                    }
                )

                CloudDeployStep.Access -> AccessStep(
                    endpoint = endpoint,
                    onBack = {
                        step = CloudDeployStep.Connect
                        resetProbe()
                    },
                    onFinish = { step = CloudDeployStep.Pair }
                )

                CloudDeployStep.Pair -> PairingStep(
                    endpoint = endpoint,
                    existingToken = deviceToken,
                    store = store,
                    onBack = { step = CloudDeployStep.Access },
                    onNext = { token ->
                        deviceToken = token
                        step = CloudDeployStep.Container
                    }
                )

                CloudDeployStep.Container -> ContainerStep(
                    endpoint = endpoint,
                    deviceToken = deviceToken,
                    onBack = { step = CloudDeployStep.Pair },
                    onRepair = {
                        store.clearPairing()
                        deviceToken = ""
                        step = CloudDeployStep.Pair
                    },
                    onCreated = {
                        createdContainer = it
                        step = CloudDeployStep.Done
                    }
                )

                CloudDeployStep.Done -> DoneStep(
                    endpoint = endpoint,
                    container = createdContainer,
                    onBackToContainer = onNavigateBack
                )
            }
        }
    }
}

@Composable
private fun StepHeader(step: CloudDeployStep) {
    val number = when (step) {
        CloudDeployStep.Connect -> 1
        CloudDeployStep.Access -> 2
        CloudDeployStep.Pair -> 3
        CloudDeployStep.Container -> 4
        CloudDeployStep.Done -> 5
    }
    val title = when (step) {
        CloudDeployStep.Connect -> R.string.cloud_deploy_step_connect
        CloudDeployStep.Access -> R.string.cloud_deploy_step_access
        CloudDeployStep.Pair -> R.string.cloud_deploy_step_pair
        CloudDeployStep.Container -> R.string.cloud_deploy_step_container
        CloudDeployStep.Done -> R.string.cloud_deploy_step_done
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.cloud_deploy_step_progress, number, CloudDeployTotalSteps),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
    }
}

@Composable
private fun ConnectStep(
    input: String,
    onInputChange: (String) -> Unit,
    error: CloudEndpointError?,
    checking: Boolean,
    probeResult: CloudControlPlaneProbeResult?,
    savedUrl: String,
    installCommand: String,
    onCheck: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var showInstallCommand by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.cloud_deploy_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText
    )

    if (savedUrl.isNotBlank()) {
        Text(
            text = stringResource(R.string.cloud_deploy_current, savedUrl),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText
        )
    }

    AppTextField(
        value = input,
        onValueChange = {
            copied = false
            onInputChange(it)
        },
        label = stringResource(R.string.cloud_deploy_server_label),
        placeholder = stringResource(R.string.cloud_deploy_server_placeholder),
        singleLine = true,
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        supportingText = {
            Text(
                text = if (error != null) {
                    endpointErrorText(error)
                } else {
                    stringResource(R.string.cloud_deploy_server_help)
                },
                color = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.semanticColors.subtleText
                }
            )
        }
    )

    TextButton(
        onClick = { showInstallCommand = !showInstallCommand }
    ) {
        Text(
            text = if (showInstallCommand) {
                stringResource(R.string.cloud_deploy_install_hide)
            } else {
                stringResource(R.string.cloud_deploy_install_show)
            }
        )
    }

    if (showInstallCommand) {
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.semanticColors.cardSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text(
                    text = stringResource(R.string.cloud_deploy_install_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = installCommand,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                TextButton(
                    onClick = {
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Mini-AIBOT install command", installCommand)
                        )
                        copied = true
                    }
                ) {
                    Text(
                        text = if (copied) {
                            stringResource(R.string.cloud_deploy_install_copied)
                        } else {
                            stringResource(R.string.cloud_deploy_install_copy)
                        }
                    )
                }
            }
        }
    }

    Button(
        onClick = onCheck,
        modifier = Modifier.fillMaxWidth(),
        enabled = !checking
    ) {
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.cloud_deploy_checking))
        } else {
            Text(stringResource(R.string.cloud_deploy_check))
        }
    }

    probeResult?.let { result ->
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = if (result.reachable) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (result.reachable) FeatherIcons.Check else FeatherIcons.Cloud,
                        contentDescription = null,
                        tint = if (result.reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = if (result.reachable) {
                            stringResource(R.string.cloud_deploy_check_success)
                        } else {
                            stringResource(R.string.cloud_deploy_check_failed, result.detail)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (result.reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                if (!result.reachable) {
                    Spacer(Modifier.height(Spacing.xs))
                    TextButton(onClick = onCheck) {
                        Text(stringResource(R.string.cloud_deploy_retry))
                    }
                }
            }
        }
    }

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
        enabled = probeResult?.reachable == true && !checking
    ) {
        Text(stringResource(R.string.cloud_deploy_next))
    }
}

@Composable
private fun AccessStep(
    endpoint: CloudControlPlaneEndpoint?,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val secure = endpoint?.secure == true
    Text(
        text = stringResource(R.string.cloud_deploy_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText
    )

    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.semanticColors.cardSurface,
        modifier = Modifier.fillMaxWidth().let { base ->
            if (!secure) {
                base.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(Radius.lg)
                )
            } else {
                base
            }
        }
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (!secure) FeatherIcons.Check else FeatherIcons.Cloud,
                contentDescription = null,
                tint = if (!secure) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Column {
                Text(
                    text = stringResource(R.string.cloud_deploy_ip_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.cloud_deploy_ip_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
                if (!secure) {
                    endpoint?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = it.baseUrl,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.semanticColors.cardSurface,
        modifier = Modifier.fillMaxWidth().let { base ->
            if (secure) {
                base.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(Radius.lg)
                )
            } else {
                base
            }
        }
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (secure) FeatherIcons.Check else FeatherIcons.Globe,
                contentDescription = null,
                tint = if (secure) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.cloud_deploy_domain_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(R.string.cloud_deploy_domain_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (secure) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.subtleText,
                        modifier = Modifier
                            .background(
                                color = if (secure) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(Radius.sm)
                            )
                            .padding(horizontal = Spacing.xs, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.cloud_deploy_domain_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
                if (secure) {
                    endpoint?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = it.baseUrl,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (!secure) {
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.cloud_deploy_ip_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(Spacing.md)
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.cloud_deploy_change_address))
        }
        Button(
            onClick = onFinish,
            modifier = Modifier.weight(1f),
            enabled = endpoint != null
        ) {
            Text(stringResource(R.string.cloud_deploy_pair))
        }
    }
}

@Composable
private fun PairingStep(
    endpoint: CloudControlPlaneEndpoint?,
    existingToken: String,
    store: CloudDeployStore,
    onBack: () -> Unit,
    onNext: (String) -> Unit
) {
    val client = remember { CloudControlPlaneClient() }
    val scope = rememberCoroutineScope()
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var pairedToken by remember { mutableStateOf(existingToken) }
    var serverName by remember { mutableStateOf(store.loadServerName().orEmpty()) }
    var verifying by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(endpoint, existingToken) {
        if (endpoint == null || existingToken.isBlank()) return@LaunchedEffect
        verifying = true
        error = null
        try {
            val info = client.serverInfo(endpoint, existingToken)
            if (info.paired) {
                pairedToken = existingToken
                serverName = info.serverName
                store.saveServerName(info.serverName)
                verified = true
            } else {
                store.clearPairing()
                error = CloudControlPlaneException(200, "not_paired", "Server is not paired")
            }
        } catch (e: Throwable) {
            if (e is CloudControlPlaneException && e.statusCode == 401) {
                store.clearPairing()
            }
            error = e
        } finally {
            verifying = false
        }
    }

    fun pair() {
        val token = tokenInput.trim()
        if (endpoint == null || token.isEmpty() || pairing) return
        pairing = true
        error = null
        scope.launch {
            try {
                val deviceKey = CloudDeviceIdentity.generate()
                val result = client.pair(
                    endpoint = endpoint,
                    bootstrapToken = token,
                    devicePublicKey = deviceKey.publicKeyBase64,
                    deviceName = "Mini-AIBOT Android",
                )
                store.savePairing(
                    deviceToken = result.deviceToken,
                    publicKey = deviceKey.publicKeyBase64,
                    privateKey = deviceKey.privateKeyBase64,
                    serverName = result.serverName,
                )
                pairedToken = result.deviceToken
                serverName = result.serverName
                verified = true
                tokenInput = ""
            } catch (e: Throwable) {
                error = e
                verified = false
            } finally {
                pairing = false
            }
        }
    }

    Text(
        text = stringResource(R.string.cloud_deploy_pair_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText
    )

    when {
        verifying -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.cloud_deploy_pair_verifying))
            }
        }

        verified -> {
            Surface(
                shape = RoundedCornerShape(Radius.lg),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        FeatherIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
                        Text(
                            text = stringResource(R.string.cloud_deploy_pair_paired),
                            fontWeight = FontWeight.SemiBold
                        )
                        if (serverName.isNotBlank()) {
                            Text(
                                text = serverName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.semanticColors.subtleText
                            )
                        }
                    }
                }
            }
        }

        else -> {
            Text(
                text = stringResource(R.string.cloud_deploy_pair_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText
            )
            AppTextField(
                value = tokenInput,
                onValueChange = {
                    tokenInput = it
                    if (error != null) error = null
                },
                label = stringResource(R.string.cloud_deploy_pair_token_label),
                placeholder = stringResource(R.string.cloud_deploy_pair_token_placeholder),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = ::pair,
                modifier = Modifier.fillMaxWidth(),
                enabled = endpoint != null && tokenInput.isNotBlank() && !pairing
            ) {
                if (pairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.cloud_deploy_pair_pairing))
                } else {
                    Text(stringResource(R.string.cloud_deploy_pair_action))
                }
            }
        }
    }

    error?.let {
        Text(
            text = cloudErrorText(it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.cloud_deploy_back))
        }
        Button(
            onClick = { if (verified && pairedToken.isNotBlank()) onNext(pairedToken) },
            modifier = Modifier.weight(1f),
            enabled = verified && pairedToken.isNotBlank() && !verifying && !pairing
        ) {
            Text(stringResource(R.string.cloud_deploy_pair_next))
        }
    }
}

@Composable
private fun ContainerStep(
    endpoint: CloudControlPlaneEndpoint?,
    deviceToken: String,
    onBack: () -> Unit,
    onRepair: () -> Unit,
    onCreated: (CloudContainerSummary) -> Unit
) {
    val client = remember { CloudControlPlaneClient() }
    val scope = rememberCoroutineScope()
    val defaultName = stringResource(R.string.cloud_deploy_default_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var images by remember { mutableStateOf<List<CloudDeploymentImage>>(emptyList()) }
    var presets by remember { mutableStateOf<List<CloudResourcePreset>>(emptyList()) }
    var selectedImageId by rememberSaveable { mutableStateOf("") }
    var selectedPresetId by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var deploying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(endpoint, deviceToken) {
        if (endpoint == null || deviceToken.isBlank()) {
            loading = false
            error = CloudControlPlaneException(401, "unauthorized", "Pairing token is missing")
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val info = client.serverInfo(endpoint, deviceToken)
            val options = client.deploymentOptions(endpoint, deviceToken)
            val matching = options.images
                .filter { it.architecture == info.architecture }
                .ifEmpty { options.images }
            images = matching
            presets = options.presets
            if (selectedImageId.isBlank() || matching.none { it.id == selectedImageId }) {
                selectedImageId = matching.firstOrNull()?.id.orEmpty()
            }
            if (selectedPresetId.isBlank() || options.presets.none { it.id == selectedPresetId }) {
                selectedPresetId = options.presets.firstOrNull()?.id.orEmpty()
            }
        } catch (e: Throwable) {
            error = e
        } finally {
            loading = false
        }
    }

    val selectedImage = images.firstOrNull { it.id == selectedImageId }
    val selectedPreset = presets.firstOrNull { it.id == selectedPresetId }
    val nameValid = name.trim().length in 1..64

    fun deploy() {
        val image = selectedImage ?: return
        if (endpoint == null || deviceToken.isBlank() || deploying || !nameValid) return
        deploying = true
        error = null
        scope.launch {
            try {
                val summary = client.deployContainer(
                    endpoint = endpoint,
                    deviceToken = deviceToken,
                    requestBody = CloudDeployRequest(
                        name = name.trim(),
                        imageRef = image.imageRef,
                        resourcePolicy = selectedPreset?.resourcePolicy ?: CloudResourcePolicy(),
                    )
                )
                onCreated(summary)
            } catch (e: Throwable) {
                error = e
            } finally {
                deploying = false
            }
        }
    }

    Text(
        text = stringResource(R.string.cloud_deploy_container_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText
    )

    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.cloud_deploy_container_loading))
        }
    } else {
        AppTextField(
            value = name,
            onValueChange = {
                name = it
                if (error != null) error = null
            },
            label = stringResource(R.string.cloud_deploy_container_name_label),
            singleLine = true,
            isError = !nameValid,
            supportingText = {
                Text(
                    text = if (nameValid) {
                        stringResource(R.string.cloud_deploy_container_name_help)
                    } else {
                        stringResource(R.string.cloud_deploy_error_name)
                    },
                    color = if (nameValid) {
                        MaterialTheme.semanticColors.subtleText
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        )

        if (images.isNotEmpty()) {
            Text(
                text = stringResource(R.string.cloud_deploy_image_label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            images.forEach { image ->
                SelectableRow(
                    title = image.displayName,
                    subtitle = image.imageRef,
                    selected = image.id == selectedImageId,
                    onClick = { selectedImageId = image.id }
                )
            }
        }

        if (presets.isNotEmpty()) {
            Text(
                text = stringResource(R.string.cloud_deploy_preset_label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            presets.forEach { preset ->
                SelectableRow(
                    title = preset.displayName,
                    subtitle = preset.id,
                    selected = preset.id == selectedPresetId,
                    onClick = { selectedPresetId = preset.id }
                )
            }
        }

        Text(
            text = stringResource(R.string.cloud_deploy_container_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText
        )

        Button(
            onClick = ::deploy,
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedImage != null && nameValid && !deploying
        ) {
            if (deploying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.cloud_deploy_deploying))
            } else {
                Text(stringResource(R.string.cloud_deploy_deploy_action))
            }
        }
    }

    error?.let {
        Text(
            text = cloudErrorText(it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (it is CloudControlPlaneException && it.statusCode == 401) {
            TextButton(onClick = onRepair) {
                Text(stringResource(R.string.cloud_deploy_repair))
            }
        }
    }

    TextButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.cloud_deploy_back))
    }
}

@Composable
private fun SelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.semanticColors.cardSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (selected) FeatherIcons.Check else FeatherIcons.Cloud,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Column {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        }
    }
}

@Composable
private fun DoneStep(
    endpoint: CloudControlPlaneEndpoint?,
    container: CloudContainerSummary?,
    onBackToContainer: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = FeatherIcons.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = Spacing.xl)
                .size(48.dp)
        )
        Text(
            text = stringResource(R.string.cloud_deploy_done_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        container?.let {
            Text(
                text = stringResource(
                    R.string.cloud_deploy_done_container,
                    it.profile.name,
                    it.instance.phase
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        endpoint?.let {
            Text(
                text = it.baseUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
        Text(
            text = stringResource(R.string.cloud_deploy_done_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
        Button(
            onClick = onBackToContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md)
        ) {
            Text(stringResource(R.string.cloud_deploy_back_to_container))
        }
    }
}

@Composable
private fun endpointErrorText(error: CloudEndpointError): String = when (error) {
    CloudEndpointError.EMPTY -> stringResource(R.string.cloud_deploy_error_empty)
    CloudEndpointError.HTTPS_REQUIRED -> stringResource(R.string.cloud_deploy_error_https)
    CloudEndpointError.DOMAIN_REQUIRED -> stringResource(R.string.cloud_deploy_error_domain)
    CloudEndpointError.DOMAIN_INVALID -> stringResource(R.string.cloud_deploy_error_domain_invalid)
    CloudEndpointError.INVALID_HOST -> stringResource(R.string.cloud_deploy_error_host)
    CloudEndpointError.INVALID_PORT -> stringResource(R.string.cloud_deploy_error_port)
    CloudEndpointError.INVALID_FORMAT -> stringResource(R.string.cloud_deploy_error_format)
}

@Composable
private fun cloudErrorText(error: Throwable): String = when (error) {
    is CloudControlPlaneException -> when (error.code) {
        "device_already_paired" -> stringResource(R.string.cloud_deploy_error_paired)
        "invalid_bootstrap_token" -> stringResource(R.string.cloud_deploy_error_bootstrap)
        "unauthorized" -> stringResource(R.string.cloud_deploy_error_unauthorized)
        "not_paired" -> stringResource(R.string.cloud_deploy_error_not_paired)
        "invalid_name" -> stringResource(R.string.cloud_deploy_error_name)
        "invalid_image" -> stringResource(R.string.cloud_deploy_error_image)
        "unsupported_image" -> stringResource(R.string.cloud_deploy_error_image)
        "invalid_resource" -> stringResource(R.string.cloud_deploy_error_resource)
        "unsupported_component" -> stringResource(R.string.cloud_deploy_error_component)
        "network_error" -> stringResource(R.string.cloud_deploy_error_network)
        else -> stringResource(R.string.cloud_deploy_error_server, error.code)
    }
    else -> stringResource(R.string.cloud_deploy_error_network)
}
