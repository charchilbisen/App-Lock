package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ui.theme.*
import com.example.viewmodel.AppItem
import com.example.viewmodel.AppLockViewModel

class MainActivity : FragmentActivity() {

    private lateinit var viewModel: AppLockViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = AppLockViewModel.Factory(this)
        setContent {
            val vm: AppLockViewModel = viewModel(factory = factory)
            viewModel = vm
            val themeMode by vm.themeMode.collectAsState()

            MyApplicationTheme(themeMode = themeMode) {
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.updatePermissionsStatus()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                val isPinSet by viewModel.isPinSetState.collectAsState()
                val dashboardUnlocked by viewModel.dashboardUnlocked.collectAsState()

                if (!isPinSet) {
                    PinSetupScreen(
                        themeMode = themeMode,
                        onThemeSelected = { viewModel.setThemeMode(it) },
                        onPinSaved = { pin ->
                            viewModel.savePin(pin)
                        }
                    )
                } else if (!dashboardUnlocked) {
                    GatewayAuthenticationScreen(
                        themeMode = themeMode,
                        onThemeSelected = { viewModel.setThemeMode(it) },
                        savedPin = viewModel.getSavedPin(),
                        isBiometricAvailable = viewModel.isBiometricEnabled.value,
                        onSuccess = {
                            viewModel.markDashboardUnlocked(true)
                        },
                        onBiometricClick = {
                            showGatingBiometricPrompt()
                        }
                    )
                    
                    LaunchedEffect(Unit) {
                        if (viewModel.isBiometricEnabled.value) {
                            showGatingBiometricPrompt()
                        }
                    }
                } else {
                    MainConsoleDashboard(viewModel = viewModel)
                }
            }
        }
    }

    private fun showGatingBiometricPrompt() {
        if (!viewModel.isBiometricEnabled.value) return

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    viewModel.markDashboardUnlocked(true)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(this@MainActivity, "Biometric failed", Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock App Lock Console")
            .setSubtitle("Confirm biological credentials to make configurations")
            .setNegativeButtonText("Use PIN")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onStop() {
        super.onStop()
        if (::viewModel.isInitialized && viewModel.isPinSet()) {
            viewModel.markDashboardUnlocked(false)
        }
    }
}

// ---------------------- PIN Setup Flow Composable ----------------------
@Composable
fun PinSetupScreen(
    themeMode: String,
    onThemeSelected: (String) -> Unit,
    onPinSaved: (String) -> Unit
) {
    var step by remember { mutableIntStateOf(1) } // 1: Enter PIN, 2: Confirm PIN
    var firstEntry by remember { mutableStateOf("") }
    var secondEntry by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(isError) {
        if (isError) {
            repeat(3) {
                shakeOffset.animateTo(15f, spring(stiffness = Spring.StiffnessHigh))
                shakeOffset.animateTo(-15f, spring(stiffness = Spring.StiffnessHigh))
            }
            shakeOffset.animateTo(0f)
            secondEntry = ""
            isError = false
        }
    }

    val currentInput = if (step == 1) firstEntry else secondEntry
    val colors = getThemeColors()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Theme Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemeSelectorRow(
                    currentTheme = themeMode,
                    onThemeSelected = onThemeSelected
                )
            }

            // Header card section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .background(colors.accent, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                     text = if (step == 1) "Create Custom PIN" else "Confirm Your PIN",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (step == 1) "Enter a 4-digit PIN to secure your app" else "Confirm your 4-digit security PIN to finalize",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // PIN Dots and Setup Confirmation Button Group
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .offset(x = shakeOffset.value.dp)
                        .padding(bottom = 20.dp)
                ) {
                    for (i in 0 until 4) {
                        val isFilled = i < currentInput.length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    color = if (isError) Color(0xFFFF5252)
                                    else if (isFilled) colors.textPrimary
                                    else colors.border,
                                    shape = CircleShape
                                )
                        )
                    }
                }

                // Explicit button to confirm PIN setup entry
                Button(
                    onClick = {
                        if (currentInput.length == 4) {
                            if (step == 1) {
                                step = 2
                            } else {
                                if (firstEntry == secondEntry) {
                                    onPinSaved(secondEntry)
                                } else {
                                    isError = true
                                }
                            }
                        }
                    },
                    enabled = currentInput.length == 4,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("pin_confirm_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.textPrimary,
                        contentColor = colors.cardBg,
                        disabledContainerColor = colors.border,
                        disabledContentColor = colors.textSecondary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (step == 1) "Continue" else "Confirm & Save PIN",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Aesthetic high density custom light keypad grid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "DEL")

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.widthIn(max = 280.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(keys) { key ->
                        when (key) {
                            "" -> {
                                Spacer(modifier = Modifier.size(60.dp))
                            }
                            "DEL" -> {
                                IconButton(
                                    onClick = {
                                        if (currentInput.isNotEmpty()) {
                                            if (step == 1) {
                                                firstEntry = firstEntry.dropLast(1)
                                            } else {
                                                secondEntry = secondEntry.dropLast(1)
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .size(60.dp)
                                        .background(colors.cardBg, CircleShape)
                                        .border(1.dp, colors.border, CircleShape)
                                        .testTag("key_delete")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Backspace,
                                        contentDescription = "Backspace",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            else -> {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .background(colors.cardBg, CircleShape)
                                        .border(1.dp, colors.border, CircleShape)
                                        .testTag("key_$key")
                                        .clickable {
                                            if (currentInput.length < 4) {
                                                val added = currentInput + key
                                                if (step == 1) {
                                                    firstEntry = added
                                                } else {
                                                    secondEntry = added
                                                }
                                            }
                                        }
                                ) {
                                    Text(
                                        text = key,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------- Gateway Authentication Screen ----------------------
@Composable
fun GatewayAuthenticationScreen(
    themeMode: String,
    onThemeSelected: (String) -> Unit,
    savedPin: String,
    isBiometricAvailable: Boolean,
    onSuccess: () -> Unit,
    onBiometricClick: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(isError) {
        if (isError) {
            repeat(3) {
                shakeOffset.animateTo(15f, spring(stiffness = Spring.StiffnessHigh))
                shakeOffset.animateTo(-15f, spring(stiffness = Spring.StiffnessHigh))
            }
            shakeOffset.animateTo(0f)
            enteredPin = ""
            isError = false
        }
    }

    val colors = getThemeColors()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Theme Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemeSelectorRow(
                    currentTheme = themeMode,
                    onThemeSelected = onThemeSelected
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .background(colors.textPrimary, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Console Access Gated",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Verify credentials to edit App Lock parameters",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            // PIN Dots and Gate Confirmation Button Group
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .offset(x = shakeOffset.value.dp)
                        .padding(bottom = 20.dp)
                ) {
                    for (i in 0 until 4) {
                        val isFilled = i < enteredPin.length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    color = if (isError) Color(0xFFFF5252)
                                    else if (isFilled) colors.textPrimary
                                    else colors.border,
                                    shape = CircleShape
                                )
                        )
                    }
                }

                // Explicit button to confirm PIN and enter app/console
                Button(
                    onClick = {
                        if (enteredPin == savedPin) {
                            onSuccess()
                        } else {
                            isError = true
                        }
                    },
                    enabled = enteredPin.length == 4,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("gate_confirm_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.textPrimary,
                        contentColor = colors.cardBg,
                        disabledContainerColor = colors.border,
                        disabledContentColor = colors.textSecondary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Confirm & Enter App",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Keypad Grid Layout
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "BIO", "0", "DEL")

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.widthIn(max = 280.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(keys) { key ->
                        when (key) {
                            "BIO" -> {
                                if (isBiometricAvailable) {
                                    IconButton(
                                        onClick = onBiometricClick,
                                        modifier = Modifier
                                            .size(60.dp)
                                            .background(colors.accent, CircleShape)
                                            .testTag("key_biometrics")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Fingerprint,
                                            contentDescription = "Fingerprint",
                                            tint = colors.textPrimary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(60.dp))
                                }
                            }
                            "DEL" -> {
                                IconButton(
                                    onClick = {
                                        if (enteredPin.isNotEmpty()) {
                                            enteredPin = enteredPin.dropLast(1)
                                        }
                                    },
                                    modifier = Modifier
                                        .size(60.dp)
                                        .background(colors.cardBg, CircleShape)
                                        .border(1.dp, colors.border, CircleShape)
                                        .testTag("key_delete")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Backspace,
                                        contentDescription = "Backspace",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            else -> {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .background(colors.cardBg, CircleShape)
                                        .border(1.dp, colors.border, CircleShape)
                                        .testTag("key_$key")
                                        .clickable {
                                            if (enteredPin.length < 4) {
                                                enteredPin += key
                                            }
                                        }
                                ) {
                                    Text(
                                        text = key,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------- Main Secured Dashboard Console ----------------------
@Composable
fun MainConsoleDashboard(viewModel: AppLockViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Settings & Status, 1: Application Locks
    val colors = getThemeColors()

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            NavigationBar(
                containerColor = colors.cardBg,
                contentColor = colors.textPrimary,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .shadow(12.dp)
                    .border(BorderStroke(1.dp, colors.border))
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings Tab") },
                    label = { Text("Status & Setup", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.textPrimary,
                        selectedTextColor = colors.textPrimary,
                        unselectedIconColor = colors.textSecondary,
                        unselectedTextColor = colors.textSecondary,
                        indicatorColor = colors.accent
                    ),
                    modifier = Modifier.testTag("nav_settings")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(imageVector = Icons.Filled.Apps, contentDescription = "Apps Tab") },
                    label = { Text("App Locker", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.textPrimary,
                        selectedTextColor = colors.textPrimary,
                        unselectedIconColor = colors.textSecondary,
                        unselectedTextColor = colors.textSecondary,
                        indicatorColor = colors.accent
                    ),
                    modifier = Modifier.testTag("nav_apps")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(colors.background)
        ) {
            // Elegant Header Section of "High Density" spec
            HeaderSection(viewModel = viewModel)

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.background
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                    },
                    label = "dashboard_tab_transition"
                ) { targetTab ->
                    when (targetTab) {
                        0 -> SettingsAndStatusTab(viewModel = viewModel)
                        1 -> ApplicationLocksTab(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

// Dedicated High Density Header Section matching HTML spec
@Composable
fun HeaderSection(viewModel: AppLockViewModel) {
    val themeMode by viewModel.themeMode.collectAsState()
    val colors = getThemeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 16.dp, bottom = 12.dp, start = 24.dp, end = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "App Lock",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                modifier = Modifier.testTag("dashboard_title")
            )
        }
        
        ThemeSelectorRow(
            currentTheme = themeMode,
            onThemeSelected = { newTheme ->
                viewModel.setThemeMode(newTheme)
            }
        )
    }
}

@Composable
fun SettingsAndStatusTab(viewModel: AppLockViewModel) {
    val context = LocalContext.current
    val isServiceActive by viewModel.isServiceActive.collectAsState()
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsState()
    val hasUsageStatsPermission by viewModel.hasUsageStatsPermission.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()

    var showPinChangeDialog by remember { mutableStateOf(false) }
    val colors = getThemeColors()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // High Density Active Protection Service card
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("status_master_card")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(colors.cardBg, RoundedCornerShape(16.dp))
                                    .shadow(1.dp, RoundedCornerShape(16.dp))
                            ) {
                                Icon(
                                    imageVector = if (isServiceActive) Icons.Filled.Shield else Icons.Filled.ShieldMoon,
                                    contentDescription = "Status lock shield",
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = if (isServiceActive) "Service Active" else "Service Idle",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Monitoring usage statistics...",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary.copy(alpha = 0.7f)
                                )
                            }
                        }

                        Switch(
                            checked = isServiceActive,
                            onCheckedChange = { active ->
                                if (active && (!hasUsageStatsPermission || !hasOverlayPermission)) {
                                    Toast.makeText(context, "Permissions are required to start protection", Toast.LENGTH_LONG).show()
                                } else {
                                    viewModel.toggleServiceActive(active)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.cardBg,
                                checkedTrackColor = colors.textPrimary,
                                uncheckedThumbColor = colors.textSecondary,
                                uncheckedTrackColor = colors.border
                            ),
                            modifier = Modifier.testTag("master_service_switch")
                        )
                    }
                }
            }
        }

        // Required Permissions status checklist card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardBg),
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "System Authorizations",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = colors.textPrimary
                    )

                    PermissionStatusRow(
                        title = "Usage Access Authorization",
                        description = "Permits detecting when a background locked app transitions to foreground.",
                        isGranted = hasUsageStatsPermission,
                        onRequest = { viewModel.openUsageAccessSettings() }
                    )

                    HorizontalDivider(color = colors.border, thickness = 1.dp)

                    PermissionStatusRow(
                        title = "Overlay/Display on Top Authority",
                        description = "Permits presenting the biometric and PIN locks screen instantly on top of other applications.",
                        isGranted = hasOverlayPermission,
                        onRequest = { viewModel.openOverlaySettings() }
                    )
                }
            }
        }

        // Configurations: Biometrics, Change PIN
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardBg),
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Authentication Preferences",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = colors.textPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Biological Credentials",
                                fontWeight = FontWeight.SemiBold,
                                                            fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Use fingerprint scans to open locked apps securely.",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }

                        Switch(
                            checked = isBiometricEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.toggleBiometricEnabled(enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.cardBg,
                                checkedTrackColor = colors.textPrimary,
                                uncheckedThumbColor = colors.textSecondary,
                                uncheckedTrackColor = colors.border
                            )
                        )
                    }

                    HorizontalDivider(color = colors.border, thickness = 1.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPinChangeDialog = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Modify Custom PIN",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Update your 4-digit physical device security locks",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = "Change PIN",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    if (showPinChangeDialog) {
        ChangePinDialog(
            currentPin = viewModel.getSavedPin(),
            onDismiss = { showPinChangeDialog = false },
            onPinChanged = { newPin ->
                viewModel.savePin(newPin)
                showPinChangeDialog = false
                Toast.makeText(context, "PIN code updated successfully!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun PermissionStatusRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    val colors = getThemeColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (isGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = colors.textSecondary
            )
        }

        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) colors.border.copy(alpha = 0.5f) else colors.textPrimary,
                contentColor = if (isGranted) colors.textSecondary else colors.cardBg
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(34.dp)
        ) {
            Text(
                text = if (isGranted) "Authorized" else "Grant",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ChangePinDialog(
    currentPin: String,
    onDismiss: () -> Unit,
    onPinChanged: (String) -> Unit
) {
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    val colors = getThemeColors()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Change Security PIN",
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                fontSize = 18.sp
            )
        },
        containerColor = colors.cardBg,
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFF5252),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                OutlinedTextField(
                    value = oldPin,
                    onValueChange = { if (it.length <= 4) oldPin = it },
                    label = { Text("Verify Existing PIN") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.textPrimary,
                        unfocusedBorderColor = colors.border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 4) newPin = it },
                    label = { Text("Specify New 4-digit PIN") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.textPrimary,
                        unfocusedBorderColor = colors.border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 4) confirmPin = it },
                    label = { Text("Re-confirm New PIN") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.textPrimary,
                        unfocusedBorderColor = colors.border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (oldPin != currentPin) {
                        errorMessage = "The old PIN you entered is incorrect."
                    } else if (newPin.length < 4) {
                        errorMessage = "PIN code must be exactly 4 digits."
                    } else if (newPin != confirmPin) {
                        errorMessage = "New PIN configurations do not match."
                    } else {
                        onPinChanged(newPin)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.textPrimary)
            ) {
                Text("Update PIN", color = colors.cardBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}

// ---------------------- Protected Apps Locks Checkbox Tab ----------------------
@Composable
fun ApplicationLocksTab(viewModel: AppLockViewModel) {
    val searchVal by viewModel.searchQuery.collectAsState()
    val displayedApps by viewModel.uiAppState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // 0: All progress/status, 1: Locked Only, 2: System/Others
    var filterCategoryIndex by remember { mutableIntStateOf(0) }
    val colors = getThemeColors()

    val filteredApps = remember(displayedApps, filterCategoryIndex) {
        when (filterCategoryIndex) {
            1 -> displayedApps.filter { it.isLocked }
            2 -> displayedApps.filter { !it.isLocked }
            else -> displayedApps
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // High density visual explanation label
        Text(
            text = "Checked applications mandate secure credentials validation on entry",
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp)
        )

        // Light outline high density search bar
        OutlinedTextField(
            value = searchVal,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("Search system applications...", color = colors.textSecondary, fontSize = 14.sp) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = "Search icon", tint = colors.textSecondary) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .testTag("search_app_text"),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                focusedBorderColor = colors.textPrimary,
                unfocusedBorderColor = colors.border,
                focusedContainerColor = colors.cardBg,
                unfocusedContainerColor = colors.cardBg
            ),
            singleLine = true
        )

        // HTML specular category pills
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            CategoryTabPill(
                label = "All Apps",
                isSelected = filterCategoryIndex == 0,
                onClick = { filterCategoryIndex = 0 },
                modifier = Modifier.testTag("filter_all")
            )
            CategoryTabPill(
                label = "Locked",
                isSelected = filterCategoryIndex == 1,
                onClick = { filterCategoryIndex = 1 },
                modifier = Modifier.testTag("filter_locked")
            )
            CategoryTabPill(
                label = "System",
                isSelected = filterCategoryIndex == 2,
                onClick = { filterCategoryIndex = 2 },
                modifier = Modifier.testTag("filter_unlocked")
            )
        }

        // Entire list is displayed in a beautiful high-density rounded container
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
            color = colors.cardBg
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Suggested to Lock",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textSecondary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 16.dp, start = 16.dp, bottom = 8.dp)
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.textPrimary)
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.FolderOff,
                                contentDescription = null,
                                tint = colors.border,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No matched apps found",
                                color = colors.textSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredApps, key = { it.packageName }) { appItem ->
                            AppItemRow(
                                appItem = appItem,
                                onLockChange = { lock ->
                                    viewModel.toggleAppLock(appItem.packageName, appItem.appName, lock)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Category design tab pill representing standard tab bar from CSS
@Composable
fun CategoryTabPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = getThemeColors()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (isSelected) colors.textPrimary else colors.cardBg)
            .border(
                BorderStroke(1.dp, if (isSelected) colors.textPrimary else colors.border),
                RoundedCornerShape(50)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) colors.cardBg else colors.textSecondary
        )
    }
}

@Composable
fun AppItemRow(
    appItem: AppItem,
    onLockChange: (Boolean) -> Unit
) {
    val colors = getThemeColors()
    Surface(
        onClick = { onLockChange(!appItem.isLocked) },
        shape = RoundedCornerShape(16.dp),
        color = colors.cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLockChange(!appItem.isLocked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Customized back color round box for High Density listing items
                val softIconBackground = remember(appItem.appName) {
                    val hash = appItem.appName.hashCode().coerceAtLeast(0)
                    val choices = listOf(
                        Color(0xFFE8F1FF), // Light Blue
                        Color(0xFFFDE8EF), // Light Pink
                        Color(0xFFFFF7E1), // Light Yellow/Amber
                        Color(0xFFE6F4EA)  // Light Green
                    )
                    choices[hash % choices.size]
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .background(softIconBackground, RoundedCornerShape(12.dp))
                ) {
                    Image(
                        bitmap = appItem.icon.toBitmap().asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = appItem.appName,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = appItem.packageName.substringAfterLast('.'),
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (appItem.isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (appItem.isLocked) "Locked" else "Unlocked",
                    tint = if (appItem.isLocked) colors.textPrimary else colors.border,
                    modifier = Modifier.size(20.dp)
                )

                Switch(
                    checked = appItem.isLocked,
                    onCheckedChange = onLockChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.cardBg,
                        checkedTrackColor = colors.textPrimary,
                        uncheckedThumbColor = colors.textSecondary,
                        uncheckedTrackColor = colors.border
                    )
                )
            }
        }
    }
}
