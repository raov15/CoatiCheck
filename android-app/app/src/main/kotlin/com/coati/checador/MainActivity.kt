package com.coati.checador

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.coati.checador.core.ui.theme.CoatiCheckTheme
import com.coati.checador.feature.attendance.AttendanceScreen
import com.coati.checador.feature.employeeenrollment.ui.screen.PantallaRegistroEmpleado
import com.coati.checador.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CoatiCheckTheme {
                // Mostrar el crash anterior si existe (solo debug)
                val crashPrefs = getSharedPreferences("crash_log", Context.MODE_PRIVATE)
                val lastCrash = remember { crashPrefs.getString("last_crash", null) }
                var showCrashDialog by remember { mutableStateOf(lastCrash != null) }
                if (showCrashDialog && lastCrash != null) {
                    AlertDialog(
                        onDismissRequest = { showCrashDialog = false },
                        title = { Text("Último crash detectado", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text(text = lastCrash, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                crashPrefs.edit().remove("last_crash").apply()
                                showCrashDialog = false
                            }) { Text("OK (borrar)") }
                        }
                    )
                }
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "splash") {
                    composable("splash") {
                        SplashScreen(onTimeout = {
                            navController.navigate("attendance") {
                                popUpTo("splash") { inclusive = true }
                            }
                        })
                    }
                    composable("attendance") {
                        AttendanceScreen(
                            onClose = { finish() },
                            onRegisterEmployee = {
                                navController.navigate("registro_empleado")
                            },
                            onOpenSettings = {
                                navController.navigate("settings")
                            }
                        )
                    }
                    // Pantalla de registro de empleado — accesible desde el menú de asistencia
                    composable("registro_empleado") {
                        PantallaRegistroEmpleado(
                            onRegistroCompleto = {
                                navController.popBackStack()
                            },
                            onRegresar = {
                                navController.popBackStack()
                            }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(4000)
        onTimeout()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1B2A4A),
                        Color(0xFF2E7DA6)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = R.drawable.logo_coati,
                    contentDescription = stringResource(R.string.logo_description),
                    modifier = Modifier.size(120.dp)
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_subtitle),
                color = Color(0xFF3BAF8E),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.company_name),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SplashScreenPreview() {
    CoatiCheckTheme {
        SplashScreen(onTimeout = {})
    }
}
