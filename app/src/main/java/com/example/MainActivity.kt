package com.example

import android.os.Bundle
import android.os.Build
import android.content.Context
import android.view.WindowManager
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
import com.example.ui.triggerHapticFeedback
import com.example.ui.triggerLockToggleVibration
import com.example.ui.playBiometricSuccessFeedback
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import com.example.viewmodel.AppItem
import com.example.viewmodel.AppLockViewModel

class MainActivity : FragmentActivity() {

    private lateinit var viewModel: AppLockViewModel
    private val isGatingUnlockedAnimTriggered = mutableStateOf(false)

    private fun optimizeRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.decorView.post {
                    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val display = display
                    if (display != null) {
                        val modes = display.supportedModes
                        val mode120 = modes.firstOrNull { it.refreshRate >= 110f }
                        if (mode120 != null) {
                            val params = window.attributes
                            params.preferredDisplayModeId = mode120.modeId
                            window.attributes = params
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore gracefully
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        optimizeRefreshRate()

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

                val isGatingUnlocked = isGatingUnlockedAnimTriggered.value
                val gatingScale by animateFloatAsState(
                    targetValue = if (isGatingUnlocked) 0.88f else 1.0f,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "gating_exit_scale"
                )
                val gatingOpacity by animateFloatAsState(
                    targetValue = if (isGatingUnlocked) 0f else 1.0f,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "gating_exit_opacity",
                    finishedListener = {
                        if (isGatingUnlocked) {
                            viewModel.markDashboardUnlocked(true)
                            isGatingUnlockedAnimTriggered.value = false
                        }
                    }
                )

                if (!isPinSet) {
                    PinSetupScreen(
                        themeMode = themeMode,
                        onThemeSelected = { viewModel.setThemeMode(it) },
                        onPinSaved = { pin, question, answer ->
                            viewModel.saveSecurityQuestionAndAnswer(question, answer)
                            viewModel.savePin(pin)
                        }
                    )
                } else if (!dashboardUnlocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = gatingScale,
                                scaleY = gatingScale,
                                alpha = gatingOpacity
                            )
                    ) {
                        GatewayAuthenticationScreen(
                            themeMode = themeMode,
                            onThemeSelected = { viewModel.setThemeMode(it) },
                            savedPin = viewModel.getSavedPin(),
                            isBiometricAvailable = viewModel.isBiometricEnabled.value,
                            onSuccess = {
                                triggerHapticFeedback(this@MainActivity, success = true)
                                isGatingUnlockedAnimTriggered.value = true
                            },
                            onBiometricClick = {
                                showGatingBiometricPrompt()
                            }
                        )
                    }
                    
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
                    playBiometricSuccessFeedback(this@MainActivity)
                    isGatingUnlockedAnimTriggered.value = true
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
    onPinSaved: (String, String, String) -> Unit
) {
    var step by remember { mutableIntStateOf(1) } // 1: Enter PIN, 2: Confirm PIN, 3: Security Question Setup
    var firstEntry by remember { mutableStateOf("") }
    var secondEntry by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    var selectedQuestionIndex by remember { mutableIntStateOf(0) }
    var answerText by remember { mutableStateOf("") }
    var questionDropdownExpanded by remember { mutableStateOf(false) }

    val view = androidx.compose.ui.platform.LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { com.example.data.AppLockRepository.getInstance(context) }

    fun playKeyHaptic() {
        // Disabled as requested
    }

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

    LaunchedEffect(currentInput) {
        if (currentInput.length == 4) {
            delay(150)
            if (step == 1) {
                triggerHapticFeedback(context, success = true)
                step = 2
            } else if (step == 2) {
                if (firstEntry == secondEntry) {
                    triggerHapticFeedback(context, success = true)
                    step = 3
                } else {
                    triggerHapticFeedback(context, success = false)
                    isError = true
                }
            }
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
                val themeIcon = when (themeMode) {
                    "LIGHT" -> Icons.Filled.WbSunny
                    "DARK" -> Icons.Filled.NightlightRound
                    else -> Icons.Filled.BrightnessMedium
                }
                val themeDesc = when (themeMode) {
                    "LIGHT" -> "Light Theme"
                    "DARK" -> "Dark Theme"
                    else -> "System Auto Theme"
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.cardBg)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .clickable {
                            val nextTheme = when (themeMode) {
                                "LIGHT" -> "DARK"
                                "DARK" -> "SYSTEM"
                                else -> "LIGHT"
                            }
                            onThemeSelected(nextTheme)
                        }
                        .testTag("pin_setup_theme_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = themeIcon,
                        contentDescription = "$themeDesc (Click to Cycle)",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Header card section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
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

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                     text = when (step) {
                         1 -> "Create Custom PIN 🔐"
                         2 -> "Confirm Your PIN 🔄"
                         else -> "Set Security Question 🛡️"
                     },
                     fontSize = 22.sp,
                     fontWeight = FontWeight.Bold,
                     color = colors.textPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = when (step) {
                        1 -> "Enter an elegant 4-digit PIN to secure your applet dashboard."
                        2 -> "Confirm your 4-digit security PIN to finalize."
                        else -> "This question helps you recover and reset your PIN if you ever forget it."
                    },
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // PIN Dots section (only shown on steps 1 and 2)
            if (step < 3) {
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
                            .padding(bottom = 8.dp)
                    ) {
                        for (i in 0 until 4) {
                            val isFilled = i < currentInput.length
                            val dotSize by animateDpAsState(
                                targetValue = if (isFilled) 18.dp else 12.dp,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                label = "setup_dot_size_anim"
                            )
                            val dotColor by animateColorAsState(
                                targetValue = if (isError) Color(0xFFFF5252)
                                else if (isFilled) colors.textPrimary
                                else colors.border,
                                animationSpec = tween(durationMillis = 150),
                                label = "setup_dot_color_anim"
                            )
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .wrapContentSize(Alignment.Center)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(dotSize)
                                        .background(
                                            color = dotColor,
                                            shape = CircleShape
                                        )
                                )
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }

            // Interactive Bottom Part (Numpad for steps 1-2, security questions form for step 3)
            if (step < 3) {
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
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(60.dp)
                                            .clip(CircleShape)
                                            .background(colors.cardBg, CircleShape)
                                            .border(1.dp, colors.border, CircleShape)
                                            .clickable {
                                                if (repository.isTouchSoundEnabled()) {
                                                    try {
                                                        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                                                    } catch (e: Exception) {
                                                        // Safe fallback
                                                    }
                                                }
                                                playKeyHaptic()
                                                if (currentInput.isNotEmpty()) {
                                                    if (step == 1) {
                                                        firstEntry = firstEntry.dropLast(1)
                                                    } else {
                                                        secondEntry = secondEntry.dropLast(1)
                                                    }
                                                }
                                            }
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
                                            .clip(CircleShape)
                                            .background(colors.cardBg, CircleShape)
                                            .border(1.dp, colors.border, CircleShape)
                                            .clickable {
                                                if (repository.isTouchSoundEnabled()) {
                                                    try {
                                                        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                                                    } catch (e: Exception) {
                                                        // Safe fallback
                                                    }
                                                }
                                                playKeyHaptic()
                                                if (currentInput.length < 4) {
                                                    val added = currentInput + key
                                                    if (step == 1) {
                                                        firstEntry = added
                                                    } else {
                                                        secondEntry = added
                                                    }
                                                }
                                            }
                                            .testTag("key_$key")
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
            } else {
                val predefinedQuestions = listOf(
                    "What was the name of your first pet? 🐶",
                    "What is your mother's maiden name? 👩",
                    "What city were you born in? 🗺️",
                    "What was the name of your first school? 🏫",
                    "What is your favorite book or movie? 🍿"
                )
                var answerError by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Choose a Question 👇",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.cardBg)
                                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                    .clickable { questionDropdownExpanded = true }
                                    .padding(16.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = predefinedQuestions[selectedQuestionIndex],
                                        color = colors.textPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDropDown,
                                        contentDescription = "Select Question",
                                        tint = colors.textSecondary
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = questionDropdownExpanded,
                                onDismissRequest = { questionDropdownExpanded = false },
                                modifier = Modifier.background(colors.cardBg)
                            ) {
                                predefinedQuestions.forEachIndexed { index, question ->
                                    DropdownMenuItem(
                                        text = { Text(question, color = colors.textPrimary) },
                                        onClick = {
                                            selectedQuestionIndex = index
                                            questionDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Answer ✍️",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        OutlinedTextField(
                            value = answerText,
                            onValueChange = { 
                                answerText = it
                                answerError = false
                            },
                            placeholder = { Text("Your secret answer...", color = colors.textSecondary) },
                            singleLine = true,
                            isError = answerError,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pin_setup_answer_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedBorderColor = colors.textPrimary,
                                unfocusedBorderColor = colors.border,
                                focusedLabelColor = colors.textPrimary,
                                unfocusedLabelColor = colors.textSecondary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (answerText.trim().isNotEmpty()) {
                                onPinSaved(secondEntry, predefinedQuestions[selectedQuestionIndex], answerText.trim())
                            } else {
                                answerError = true
                                Toast.makeText(context, "Please provide an answer to secure your PIN! 😊", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("pin_setup_finalize_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.textPrimary,
                            contentColor = colors.background
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Finish & Protect App 🚀", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
    val view = androidx.compose.ui.platform.LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { com.example.data.AppLockRepository.getInstance(context) }

    LaunchedEffect(enteredPin) {
        if (enteredPin.length == 4) {
            delay(150)
            if (enteredPin == savedPin) {
                onSuccess()
            } else {
                triggerHapticFeedback(context, success = false)
                isError = true
            }
        }
    }

    fun playKeyHaptic() {
        try {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (e: Exception) {
            // Graceful fallback
        }
    }

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
                val themeIcon = when (themeMode) {
                    "LIGHT" -> Icons.Filled.WbSunny
                    "DARK" -> Icons.Filled.NightlightRound
                    else -> Icons.Filled.BrightnessMedium
                }
                val themeDesc = when (themeMode) {
                    "LIGHT" -> "Light Theme"
                    "DARK" -> "Dark Theme"
                    else -> "System Auto Theme"
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.cardBg)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .clickable {
                            val nextTheme = when (themeMode) {
                                "LIGHT" -> "DARK"
                                "DARK" -> "SYSTEM"
                                else -> "LIGHT"
                            }
                            onThemeSelected(nextTheme)
                        }
                        .testTag("gateway_auth_theme_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = themeIcon,
                        contentDescription = "$themeDesc (Click to Cycle)",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
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

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Console Access Gated",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

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
                        .padding(bottom = 8.dp)
                ) {
                    for (i in 0 until 4) {
                        val isFilled = i < enteredPin.length
                        val dotSize by animateDpAsState(
                            targetValue = if (isFilled) 18.dp else 12.dp,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "gate_dot_size_anim"
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (isError) Color(0xFFFF5252)
                            else if (isFilled) colors.textPrimary
                            else colors.border,
                            animationSpec = tween(durationMillis = 150),
                            label = "gate_dot_color_anim"
                        )
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .wrapContentSize(Alignment.Center)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(dotSize)
                                    .background(
                                        color = dotColor,
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
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
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(60.dp)
                                            .clip(CircleShape)
                                            .background(colors.accent, CircleShape)
                                            .clickable {
                                                if (repository.isTouchSoundEnabled()) {
                                                    try {
                                                        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                                                    } catch (e: Exception) {
                                                        // Safe fallback
                                                    }
                                                }
                                                playKeyHaptic()
                                                onBiometricClick()
                                            }
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
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(colors.cardBg, CircleShape)
                                        .border(1.dp, colors.border, CircleShape)
                                        .clickable {
                                            if (repository.isTouchSoundEnabled()) {
                                                try {
                                                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                                                } catch (e: Exception) {
                                                    // Safe fallback
                                                }
                                            }
                                            playKeyHaptic()
                                            if (enteredPin.isNotEmpty()) {
                                                enteredPin = enteredPin.dropLast(1)
                                            }
                                        }
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
                                        .clip(CircleShape)
                                        .background(colors.cardBg, CircleShape)
                                        .border(1.dp, colors.border, CircleShape)
                                        .clickable {
                                            if (repository.isTouchSoundEnabled()) {
                                                try {
                                                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                                                } catch (e: Exception) {
                                                    // Safe fallback
                                                }
                                            }
                                            playKeyHaptic()
                                            if (enteredPin.length < 4) {
                                                enteredPin += key
                                            }
                                        }
                                        .testTag("key_$key")
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

            // Reset PIN Option via Security Question
            val repo = remember { com.example.data.AppLockRepository.getInstance(context) }
            val securityQuestion = remember { repo.getSecurityQuestion() }
            val securityAnswer = remember { repo.getSecurityAnswer() }
            var showResetDialog by remember { mutableStateOf(false) }

            if (securityQuestion != null && securityAnswer != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = "Forgot PIN? 🔑",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                if (showResetDialog) {
                    ForgotPasswordResetDialog(
                        securityQuestion = securityQuestion,
                        correctAnswer = securityAnswer,
                        onDismiss = { showResetDialog = false },
                        onResetSuccess = { newPin ->
                            repo.savePin(newPin)
                            showResetDialog = false
                            onSuccess() // instantly unlock gating auth
                            Toast.makeText(context, "PIN Reset Successfully & Unlocked! 🚀", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

// ---------------------- Main Secured Dashboard Console ----------------------
@Composable
fun MainConsoleDashboard(viewModel: AppLockViewModel) {
    val hasUsageStats by viewModel.hasUsageStatsPermission.collectAsState()
    val hasOverlay by viewModel.hasOverlayPermission.collectAsState()
    val view = androidx.compose.ui.platform.LocalView.current
    val isTouchSoundEnabled by viewModel.isTouchSoundEnabled.collectAsState()

    var selectedTab by remember {
        mutableIntStateOf(if (hasUsageStats && hasOverlay) 1 else 0)
    }

    LaunchedEffect(hasUsageStats, hasOverlay) {
        if (hasUsageStats && hasOverlay) {
            selectedTab = 1
        }
    }

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
                    onClick = {
                        if (isTouchSoundEnabled) {
                            try {
                                view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                            } catch (e: Exception) {
                                // Default safe fallback
                            }
                        }
                        selectedTab = 0
                    },
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
                    onClick = {
                        if (isTouchSoundEnabled) {
                            try {
                                view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                            } catch (e: Exception) {
                                // Default safe fallback
                            }
                        }
                        selectedTab = 1
                    },
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
                        if (targetState > initialState) {
                            (slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessLow)) { width -> width / 3 } + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutHorizontally(animationSpec = spring(stiffness = Spring.StiffnessLow)) { width -> -width / 3 } + fadeOut(animationSpec = tween(300)))
                        } else {
                            (slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessLow)) { width -> -width / 3 } + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutHorizontally(animationSpec = spring(stiffness = Spring.StiffnessLow)) { width -> width / 3 } + fadeOut(animationSpec = tween(300)))
                        }
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
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val isTouchSoundEnabled by viewModel.isTouchSoundEnabled.collectAsState()

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

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Theme Mode Cycler Button (Light -> Dark -> System)
            val themeIcon = when (themeMode) {
                "LIGHT" -> Icons.Filled.WbSunny
                "DARK" -> Icons.Filled.NightlightRound
                else -> Icons.Filled.BrightnessMedium
            }
            val themeDesc = when (themeMode) {
                "LIGHT" -> "Light Theme"
                "DARK" -> "Dark Theme"
                else -> "System Auto Theme"
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.cardBg)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .clickable {
                        if (isTouchSoundEnabled) {
                            try {
                                view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                            } catch (e: Exception) {
                                // Default safe fallback
                            }
                        }
                        val nextTheme = when (themeMode) {
                            "LIGHT" -> "DARK"
                            "DARK" -> "SYSTEM"
                            else -> "LIGHT"
                        }
                        viewModel.setThemeMode(nextTheme)
                    }
                    .testTag("header_theme_toggle"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = themeIcon,
                    contentDescription = "$themeDesc (Click to Cycle)",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsAndStatusTab(viewModel: AppLockViewModel) {
    val context = LocalContext.current
    val isServiceActive by viewModel.isServiceActive.collectAsState()
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsState()
    val isTouchSoundEnabled by viewModel.isTouchSoundEnabled.collectAsState()
    val lockDelaySeconds by viewModel.lockDelaySeconds.collectAsState()
    val hasUsageStatsPermission by viewModel.hasUsageStatsPermission.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()

    var showPinChangeDialog by remember { mutableStateOf(false) }
    var showDelaySelectorDialog by remember { mutableStateOf(false) }
    var showSecurityQuestionDialog by remember { mutableStateOf(false) }
    val securityQuestion by viewModel.securityQuestion.collectAsState()
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

                    // Touch Feedback Sounds setting option
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Screen Touch Sounds",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Play responsive keypad and selection feedback sounds.",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }

                        Switch(
                            checked = isTouchSoundEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.toggleTouchSoundEnabled(enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.cardBg,
                                checkedTrackColor = colors.textPrimary,
                                uncheckedThumbColor = colors.textSecondary,
                                uncheckedTrackColor = colors.border
                            ),
                            modifier = Modifier.testTag("touch_sound_switch")
                        )
                    }

                    HorizontalDivider(color = colors.border, thickness = 1.dp)

                    // Re-lock Delay Period configuration row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDelaySelectorDialog = true }
                            .padding(vertical = 4.dp)
                            .testTag("relock_delay_setting_row"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Re-lock Delay Period",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val delayLabel = when (lockDelaySeconds) {
                                0 -> "Immediately"
                                15 -> "15 Seconds"
                                30 -> "30 Seconds"
                                60 -> "1 Minute"
                                120 -> "2 Minutes"
                                300 -> "5 Minutes"
                                else -> "$lockDelaySeconds Seconds"
                            }
                            Text(
                                text = "Delay before app locks again: $delayLabel",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = "Configure Re-lock Delay",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(24.dp)
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

                    HorizontalDivider(color = colors.border, thickness = 1.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSecurityQuestionDialog = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Security Question 🛡️",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (securityQuestion != null) "Configured: $securityQuestion" else "Reset your PIN if you ever forget it",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = "Change Security Question",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // Extra margin bottom
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

    if (showDelaySelectorDialog) {
        LockDelaySelectorDialog(
            currentDelaySeconds = lockDelaySeconds,
            onDismiss = { showDelaySelectorDialog = false },
            onDelaySelected = { seconds ->
                viewModel.setLockDelaySeconds(seconds)
                showDelaySelectorDialog = false
                Toast.makeText(context, "Re-lock delay period updated!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showSecurityQuestionDialog) {
        ConfigureSecurityQuestionDialog(
            currentQuestion = securityQuestion,
            currentAnswer = viewModel.securityAnswer.value,
            onDismiss = { showSecurityQuestionDialog = false },
            onSave = { question, answer ->
                viewModel.saveSecurityQuestionAndAnswer(question, answer)
                showSecurityQuestionDialog = false
                Toast.makeText(context, "Security question configurations updated! 👍", Toast.LENGTH_SHORT).show()
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

    // 0: All apps, 1: Locked Only
    var filterCategoryIndex by remember { mutableIntStateOf(0) }
    val colors = getThemeColors()

    val filteredApps = remember(displayedApps, filterCategoryIndex) {
        when (filterCategoryIndex) {
            1 -> displayedApps.filter { it.isLocked }
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

        // HTML specular category pills styled with equal weights
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
                modifier = Modifier.weight(1f).testTag("filter_all")
            )
            CategoryTabPill(
                label = "Locked",
                isSelected = filterCategoryIndex == 1,
                onClick = { filterCategoryIndex = 1 },
                modifier = Modifier.weight(1f).testTag("filter_locked")
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val repository = remember { com.example.data.AppLockRepository.getInstance(context) }
    val isTouchSoundEnabled = remember { repository.isTouchSoundEnabled() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (isSelected) colors.textPrimary else colors.cardBg)
            .border(
                BorderStroke(1.dp, if (isSelected) colors.textPrimary else colors.border),
                RoundedCornerShape(50)
            )
            .clickable {
                if (isTouchSoundEnabled) {
                    try {
                        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    } catch (e: Exception) {
                        // Safe fallback
                    }
                }
                onClick()
            }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val repository = remember { com.example.data.AppLockRepository.getInstance(context) }
    val isTouchSoundEnabled = remember { repository.isTouchSoundEnabled() }

    Surface(
        onClick = {
            if (isTouchSoundEnabled) {
                try {
                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                } catch (e: Exception) {
                    // Safe fallback
                }
            }
            onLockChange(!appItem.isLocked)
        },
        shape = RoundedCornerShape(16.dp),
        color = colors.cardBg,
        modifier = Modifier.fillMaxWidth()
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
                val isDark = colors.background == HDPrimaryDark
                val softIconBackground = remember(appItem.appName, isDark) {
                    val hash = appItem.appName.hashCode().coerceAtLeast(0)
                    if (isDark) {
                        val choices = listOf(
                            Color(0x1CE8F1FF), // Elegant semi-transparent pastels for dark mode
                            Color(0x1CFDE8EF),
                            Color(0x1CFFF7E1),
                            Color(0x1CE6F4EA)
                        )
                        choices[hash % choices.size]
                    } else {
                        val choices = listOf(
                            Color(0xFFE8F1FF), // Vibrant light pastels for light mode
                            Color(0xFFFDE8EF),
                            Color(0xFFFFF7E1),
                            Color(0xFFE6F4EA)
                        )
                        choices[hash % choices.size]
                    }
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
                    onCheckedChange = null, // Delegate click handling completely to the parent Surface row
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

@Composable
fun LockDelaySelectorDialog(
    currentDelaySeconds: Int,
    onDismiss: () -> Unit,
    onDelaySelected: (Int) -> Unit
) {
    val colors = getThemeColors()
    val delayOptions = listOf(
        0 to "Immediately",
        15 to "15 Seconds",
        30 to "30 Seconds",
        60 to "1 Minute",
        120 to "2 Minutes",
        300 to "5 Minutes"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Configure Re-lock Delay",
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                fontSize = 18.sp
            )
        },
        containerColor = colors.cardBg,
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "App will stay temporarily unlocked for this long after screen turns off or minimization.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                delayOptions.forEach { (seconds, label) ->
                    val isSelected = currentDelaySeconds == seconds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onDelaySelected(seconds) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) colors.textPrimary else colors.textSecondary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 15.sp
                        )
                        RadioButton(
                            selected = isSelected,
                            onClick = { onDelaySelected(seconds) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.textPrimary,
                                unselectedColor = colors.border
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ConfigureSecurityQuestionDialog(
    currentQuestion: String?,
    currentAnswer: String?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    val colors = getThemeColors()
    val predefinedQuestions = listOf(
        "What was the name of your first pet? 🐶",
        "What is your mother's maiden name? 👩",
        "What city were you born in? 🗺️",
        "What was the name of your first school? 🏫",
        "What is your favorite book or movie? 🍿"
    )

    var selectedQuestionIndex by remember {
        mutableIntStateOf(
            predefinedQuestions.indexOf(currentQuestion).coerceAtLeast(0)
        )
    }
    var answerText by remember { mutableStateOf(currentAnswer ?: "") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Configure Security Question 🛡️",
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                fontSize = 18.sp
            )
        },
        containerColor = colors.cardBg,
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "If you forget your security PIN, this question will allow you to instantly reset it securely. 😊",
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Choose a Question 👇",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.background)
                                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                .clickable { dropdownExpanded = true }
                                .padding(12.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = predefinedQuestions[selectedQuestionIndex],
                                    color = colors.textPrimary,
                                    fontSize = 14.sp
                                )
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = "Expand",
                                    tint = colors.textSecondary
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.background(colors.cardBg)
                        ) {
                            predefinedQuestions.forEachIndexed { index, question ->
                                DropdownMenuItem(
                                    text = { Text(question, color = colors.textPrimary, fontSize = 14.sp) },
                                    onClick = {
                                        selectedQuestionIndex = index
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Answer ✍️",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    OutlinedTextField(
                        value = answerText,
                        onValueChange = { 
                            answerText = it 
                            inputError = false
                        },
                        placeholder = { Text("Your case-insensitive answer...", color = colors.textSecondary) },
                        singleLine = true,
                        isError = inputError,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.textPrimary,
                            unfocusedBorderColor = colors.border
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (answerText.trim().isNotEmpty()) {
                        onSave(predefinedQuestions[selectedQuestionIndex], answerText.trim())
                    } else {
                        inputError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.textPrimary,
                    contentColor = colors.cardBg
                )
            ) {
                Text("Save Setup 🔐", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ForgotPasswordResetDialog(
    securityQuestion: String,
    correctAnswer: String,
    onDismiss: () -> Unit,
    onResetSuccess: (String) -> Unit
) {
    val colors = getThemeColors()
    var answerInput by remember { mutableStateOf("") }
    
    // State for setting new PIN once answer is correct
    var step by remember { mutableIntStateOf(1) } // 1: Verify Answer, 2: Enter New PIN, 3: Confirm New PIN
    var newPin1 by remember { mutableStateOf("") }
    var newPin2 by remember { mutableStateOf("") }
    
    var errorMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (step == 1) "Verify Security Answer 🛡️" else "Reset Security PIN 🔄",
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                fontSize = 18.sp
            )
        },
        containerColor = colors.cardBg,
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (step == 1) {
                    Text(
                        text = "Question: $securityQuestion",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )

                    OutlinedTextField(
                        value = answerInput,
                        onValueChange = { 
                            answerInput = it 
                            errorMsg = ""
                        },
                        placeholder = { Text("Your answer...", color = colors.textSecondary) },
                        singleLine = true,
                        isError = errorMsg.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.textPrimary,
                            unfocusedBorderColor = colors.border
                        )
                    )

                    if (errorMsg.isNotEmpty()) {
                        Text(
                            text = errorMsg,
                            color = Color(0xFFFF5252),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Text(
                        text = if (step == 2) "Type your new 4-digit PIN 🔐" else "Confirm your new 4-digit PIN 🔄",
                        color = colors.textSecondary,
                        fontSize = 14.sp
                    )

                    OutlinedTextField(
                        value = if (step == 2) newPin1 else newPin2,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                if (step == 2) newPin1 = input else newPin2 = input
                                errorMsg = ""
                            }
                        },
                        placeholder = { Text("xxxx", color = colors.textSecondary) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = errorMsg.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.textPrimary,
                            unfocusedBorderColor = colors.border
                        )
                    )

                    if (errorMsg.isNotEmpty()) {
                        Text(
                            text = errorMsg,
                            color = Color(0xFFFF5252),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (step == 1) {
                        if (answerInput.trim().equals(correctAnswer.trim(), ignoreCase = true)) {
                            step = 2
                            errorMsg = ""
                        } else {
                            errorMsg = "Incorrect answer! Please try again. 😢"
                        }
                    } else if (step == 2) {
                        if (newPin1.length == 4) {
                            step = 3
                            errorMsg = ""
                        } else {
                            errorMsg = "PIN must be exactly 4 digits."
                        }
                    } else {
                        if (newPin1 == newPin2) {
                            onResetSuccess(newPin1)
                        } else {
                            errorMsg = "PIN choices do not match. Try again."
                            step = 2
                            newPin1 = ""
                            newPin2 = ""
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.textPrimary,
                    contentColor = colors.cardBg
                )
            ) {
                Text(
                    text = when (step) {
                        1 -> "Verify 🗝️"
                        2 -> "Continue ➡️"
                        else -> "Save & Unlock 🚀"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}
