package com.example.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import com.example.data.AppLockRepository
import com.example.ui.theme.*
import kotlinx.coroutines.delay

class LockActivity : FragmentActivity() {

    private lateinit var repository: AppLockRepository
    private var targetPackage: String = ""
    private var appLabel: String = ""
    private var appIcon: Drawable? = null
    private val isUnlockedAnimTriggered = mutableStateOf(false)

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

        repository = AppLockRepository.getInstance(this)
        targetPackage = intent.getStringExtra("target_package") ?: ""

        if (targetPackage.isEmpty()) {
            finish()
            return
        }

        resolveAppDetails()

        if (repository.isBiometricEnabled()) {
            showBiometricPrompt()
        }

        setContent {
            var themeMode by remember { mutableStateOf(repository.getThemeMode()) }
            val isUnlocked = isUnlockedAnimTriggered.value
            var isEntered by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                isEntered = true
            }

            val scale by animateFloatAsState(
                targetValue = when {
                    isUnlocked -> 0.88f
                    !isEntered -> 0.95f
                    else -> 1.0f
                },
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                label = "exit_scale"
            )
            val opacity by animateFloatAsState(
                targetValue = when {
                    isUnlocked -> 0f
                    !isEntered -> 0f
                    else -> 1.0f
                },
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                label = "exit_opacity",
                finishedListener = {
                    if (isUnlocked) {
                        onUnlockSuccess()
                    }
                }
            )

            MyApplicationTheme(themeMode = themeMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            alpha = opacity
                        )
                ) {
                    LockScreenContent(
                        appName = appLabel,
                        appIcon = appIcon,
                        savedPin = repository.getSavedPin() ?: "",
                        isBiometricAvailable = repository.isBiometricEnabled(),
                        currentTheme = themeMode,
                        onThemeSelected = { newTheme ->
                            repository.setThemeMode(newTheme)
                            themeMode = newTheme
                        },
                        onSuccess = {
                            triggerHapticFeedback(this@LockActivity, success = true)
                            isUnlockedAnimTriggered.value = true
                        },
                        onBiometricClick = {
                            showBiometricPrompt()
                        },
                        onBackPress = {
                            exitToHomeScreen()
                        }
                    )
                }
            }
        }
    }

    private fun onUnlockSuccess() {
        repository.markTemporarilyUnlocked(targetPackage)
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.e("LockActivity", "Failed to launch target app", e)
        }
        finish()
    }

    private fun resolveAppDetails() {
        val pm = packageManager
        try {
            val appInfo = pm.getApplicationInfo(targetPackage, 0)
            appLabel = pm.getApplicationLabel(appInfo).toString()
            appIcon = pm.getApplicationIcon(appInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            appLabel = targetPackage.substringAfterLast('.')
            appIcon = null
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    playBiometricSuccessFeedback(this@LockActivity)
                    isUnlockedAnimTriggered.value = true
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(this@LockActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock App")
            .setSubtitle("Confirm biometric authentication to open $appLabel")
            .setNegativeButtonText("Use PIN")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun exitToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        exitToHomeScreen()
    }
}

@Composable
fun LockScreenContent(
    appName: String,
    appIcon: Drawable?,
    savedPin: String,
    isBiometricAvailable: Boolean,
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onSuccess: () -> Unit,
    onBiometricClick: () -> Unit,
    onBackPress: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { com.example.data.AppLockRepository.getInstance(context) }
    val isSoundEnabled = remember { repository.isTouchSoundEnabled() }

    fun playKeyHaptic() {
        // Disabled as requested
    }

    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(enteredPin) {
        if (enteredPin.length == savedPin.length) {
            delay(150)
            if (enteredPin == savedPin) {
                onSuccess()
            } else {
                triggerHapticFeedback(context, success = false)
                isError = true
            }
        }
    }

    LaunchedEffect(isError) {
        if (isError) {
            repeat(3) {
                shakeOffset.animateTo(20f, spring(stiffness = Spring.StiffnessHigh))
                shakeOffset.animateTo(-20f, spring(stiffness = Spring.StiffnessHigh))
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
            // Top Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Icon Box: Open App Lock App
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.cardBg)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .clickable {
                            try {
                                val intent = Intent(context, com.example.MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        .testTag("lock_open_app_lock_app"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Open App Lock Control Panel",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Right Icon Box: Theme Selector
                val themeIcon = when (currentTheme) {
                    "LIGHT" -> Icons.Filled.WbSunny
                    "DARK" -> Icons.Filled.NightlightRound
                    else -> Icons.Filled.BrightnessMedium
                }
                val themeDesc = when (currentTheme) {
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
                            val nextTheme = when (currentTheme) {
                                "LIGHT" -> "DARK"
                                "DARK" -> "SYSTEM"
                                else -> "LIGHT"
                            }
                            onThemeSelected(nextTheme)
                        }
                        .testTag("lock_theme_toggle"),
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

            // Header: Locked Screen Banner Card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .background(colors.cardBg, CircleShape)
                        .border(1.dp, colors.border, CircleShape)
                        .shadow(2.dp, CircleShape)
                ) {
                    if (appIcon != null) {
                        Image(
                            bitmap = appIcon.toBitmap().asImageBitmap(),
                            contentDescription = "App Icon",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Locked",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                     text = appName,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "This application is secured",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            // PIN Dots and Setup/Unlock Confirmation Button Group
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
                    for (i in 0 until savedPin.length) {
                        val isFilled = i < enteredPin.length
                        val dotSize by animateDpAsState(
                            targetValue = if (isFilled) 18.dp else 12.dp,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "dot_size_anim"
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (isError) Color(0xFFFF5252)
                            else if (isFilled) colors.textPrimary
                            else colors.border,
                            animationSpec = tween(durationMillis = 150),
                            label = "dot_color_anim"
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

            // Keypad Grid Layout - beautiful high-density round buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "BIO", "0", "DEL")

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.widthIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(keys) { key ->
                        when (key) {
                            "BIO" -> {
                                if (isBiometricAvailable) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .background(colors.accent, CircleShape)
                                            .clickable {
                                                if (isSoundEnabled) {
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
                                            contentDescription = "Fingerprint Unlocking",
                                            tint = colors.textPrimary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(72.dp))
                                }
                            }
                            "DEL" -> {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(colors.cardBg, CircleShape)
                                        .border(1.dp, colors.border, CircleShape)
                                        .clickable {
                                            if (isSoundEnabled) {
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
                                        contentDescription = "Backspace Button",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            else -> {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(colors.cardBg, CircleShape)
                                        .border(1.dp, colors.border, CircleShape)
                                        .clickable {
                                            if (isSoundEnabled) {
                                                try {
                                                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                                                } catch (e: Exception) {
                                                    // Safe fallback
                                                }
                                            }
                                            playKeyHaptic()
                                            if (enteredPin.length < savedPin.length) {
                                                enteredPin += key
                                            }
                                        }
                                        .testTag("key_$key")
                                ) {
                                    Text(
                                        text = key,
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Exit & Reset Option row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val repository = remember { com.example.data.AppLockRepository.getInstance(context) }
                    val sQuestion = remember { repository.getSecurityQuestion() }
                    val sAnswer = remember { repository.getSecurityAnswer() }
                    var showResetDialog by remember { mutableStateOf(false) }

                    if (sQuestion != null && sAnswer != null) {
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
                                securityQuestion = sQuestion,
                                correctAnswer = sAnswer,
                                onDismiss = { showResetDialog = false },
                                onResetSuccess = { newPin ->
                                    repository.savePin(newPin)
                                    showResetDialog = false
                                    onSuccess() // instantly unlocks target app!
                                    android.widget.Toast.makeText(context, "PIN Reset Successfully & Unlocked! 🚀", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    TextButton(
                        onClick = onBackPress,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(
                            text = "Exit Secured App 🚪",
                            color = Color(0xFFFF5252),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

fun triggerHapticFeedback(context: Context, success: Boolean) {
    // Disabled keyboard and unlock vibrations as requested
}

fun triggerLockToggleVibration(context: Context) {
    try {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(55, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(55)
            }
        }
    } catch (e: Exception) {
        // Safe fallback
    }
}

fun playBiometricSuccessFeedback(context: Context) {
    // 1. Soft vibration (tactile click)
    try {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            val duration = 40L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // soft/light amplitude = 35 for subtle tactile response
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, 35))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    } catch (e: Exception) {
        // Safe fallback
    }

    // 2. Play success clicking sound effect if sound is enabled
    try {
        val repository = com.example.data.AppLockRepository.getInstance(context)
        if (repository.isTouchSoundEnabled()) {
            if (context is android.app.Activity) {
                context.window.decorView.post {
                    try {
                        context.window.decorView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    } catch (e: Exception) {
                        // Safe fallback
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Safe fallback
    }
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
    var step by remember { mutableStateOf(1) } // 1: Verify Answer, 2: Enter New PIN, 3: Confirm New PIN
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
