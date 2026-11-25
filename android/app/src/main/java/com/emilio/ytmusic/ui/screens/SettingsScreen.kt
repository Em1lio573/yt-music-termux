package com.emilio.ytmusic.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.emilio.ytmusic.storage.StorageManager
import com.emilio.ytmusic.ui.theme.*
import com.emilio.ytmusic.web.AuthCookieSync
import uniffi.ytmusic_core.ytmGetVersion

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onFolderChanged: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val storageManager = remember { StorageManager.getInstance(context) }
    var currentFolder by remember { mutableStateOf(storageManager.getDownloadFolderPath()) }
    var hasFullAccess by remember { mutableStateOf(storageManager.isStoragePermissionGranted()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasFullAccess = storageManager.isStoragePermissionGranted()
                currentFolder = storageManager.getDownloadFolderPath()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val resolved = storageManager.resolveDocTreeUriToPath(uri)
            if (resolved != null) {
                storageManager.setDownloadFolder(resolved, uri.toString())
                currentFolder = resolved
                onFolderChanged?.invoke()
            }
        }
    }

    val coreVersion = remember { ytmGetVersion() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Configuración",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        // Tarjeta de almacenamiento y carpeta de música (Android 16)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0x2618181B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = AppleRose,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Carpeta de Música (Android 16)",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Ruta donde se guardan tus canciones descargadas y carátulas:",
                    color = TextMuted,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x33000000)
                ) {
                    Text(
                        text = currentFolder,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { folderPickerLauncher.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FA2D48)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = AppleRose,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Seleccionar otra carpeta...", color = AppleRose, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Estado de permisos de almacenamiento
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (hasFullAccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (hasFullAccess) AppleCyan else Color(0xFFFF453A),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasFullAccess) {
                            "Acceso completo a archivos concedido (Android 16)"
                        } else {
                            "Permiso de acceso completo requerido"
                        },
                        color = if (hasFullAccess) AppleCyan else Color(0xFFFF453A),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (!hasFullAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        TextButton(
                            onClick = { storageManager.openManageAllFilesSettings(context) }
                        ) {
                            Text("Ajustes", color = AppleRose, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Tarjeta de información del motor Rust
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0x2618181B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = AppleCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Núcleo Nativo Rust",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = coreVersion,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Estado de Sesión de YouTube Music (Sincronizado automáticamente desde el navegador integrado)
        val hasSession = remember { AuthCookieSync.isAuthenticated(context) }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0x2618181B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasSession) Icons.Default.AccountCircle else Icons.Default.Sync,
                        contentDescription = null,
                        tint = if (hasSession) Color(0xFF30D158) else AppleRose,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sesión de YouTube Music",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (hasSession) {
                        "Tu cuenta está vinculada y sincronizada automáticamente desde el navegador integrado (Web Music). Tus recomendaciones y Me Gusta están activos en el motor nativo."
                    } else {
                        "Aún no has iniciado sesión. Entra a la pestaña 'Web Music' e inicia sesión con tu cuenta de Google para sincronizar tus gustos automáticamente sin configurar archivos."
                    },
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }
    }
}
