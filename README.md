# COATI Check — Reloj Checador Android

Sistema de control de asistencia con **reconocimiento facial 100% local**, geolocalización y operación offline-first para **COATI Tecnología & Desarrollo**.

---

## Descripción

Un único dispositivo Android compartido (modo kiosk) permite que múltiples empleados registren su entrada/salida mediante reconocimiento facial. Los datos se almacenan cifrados localmente y se sincronizan automáticamente al servidor cuando hay conexión a internet. **La app funciona completamente sin internet.**

---

## Stack Tecnológico

| Capa | Tecnología | Versión |
|---|---|---|
| Lenguaje | Kotlin | 1.9.22 |
| UI | Jetpack Compose (BOM) | 2024.02.00 |
| Arquitectura | Clean Architecture + MVVM | — |
| DI | Hilt | 2.50 |
| Base de datos local | Room + SQLCipher | 2.6.1 / 4.5.4 |
| Seguridad biométrica | Android Keystore + AES-256-GCM | — |
| Reconocimiento facial | ML Kit Face Detection + TFLite | 16.1.5 / 2.14.0 |
| Cámara | CameraX | 1.3.1 |
| Geolocalización | Fused Location Provider | 21.2.0 |
| Sincronización | WorkManager | 2.9.0 |
| Red | Retrofit + OkHttp | 2.11.0 |
| Permisos Compose | Accompanist Permissions | 0.34.0 |

---

## Estructura del Proyecto

```
reloj-checador/
├── android-app/
│   ├── app/                          # Entry point, Hilt, MainActivity, navegación
│   ├── core/
│   │   ├── common/                   # Constants, Result<T>
│   │   ├── database/                 # Room + SQLCipher, 6 entidades, 6 DAOs
│   │   ├── datastore/                # DataStore preferences
│   │   ├── network/                  # Retrofit interfaces
│   │   ├── security/                 # KeystoreHelper AES-256-GCM
│   │   ├── sync/                     # WorkManager + SyncManager
│   │   └── ui/                       # Tema COATI (colores, tipografía)
│   └── feature/
│       ├── attendance/               # Pantalla principal, reconocimiento 1:N, GPS
│       ├── employee-enrollment/      # Registro de empleados, EmbeddingService TFLite
│       ├── settings/                 # Configuración del kiosk
│       ├── location/                 # LocationTracker wrapper
│       ├── device-auth/              # [Pendiente]
│       └── face-recognition/         # [Pendiente — lógica en EmbeddingService]
├── architecture.md                   # Documento de arquitectura completo
└── assets/branding/                  # Logos y branding COATI
```

---

## Estado de Implementación

| Módulo | Estado | Notas |
|---|---|---|
| `app` (MainActivity + CoatiApplication) | ✅ Completo | Navegación Compose, Hilt, crash logger debug |
| `core/database` | ✅ Completo | 6 entidades, 6 DAOs, SQLCipher + Keystore |
| `core/security` | ✅ Completo | KeystoreHelper AES-256-GCM hardware-backed |
| `core/sync` | ✅ Completo | WorkManager Worker + SyncManager |
| `core/network` | ✅ Completo | Interfaces Retrofit definidas |
| `core/datastore` | ✅ Completo | DataStore preferences |
| `core/ui` | ✅ Completo | Tema COATI (Navy/Blue/Teal), tipografía |
| `feature/attendance` | ✅ Completo | Reconocimiento 1:N, GPS, historial, lista empleados |
| `feature/employee-enrollment` | ✅ Completo | Clean Architecture, TFLite + fallback simulado, cifrado |
| `feature/settings` | ✅ Completo | URL API, GPS timeout, umbral facial, PIN admin |
| `feature/location` | ✅ Completo | Fused Location Provider wrapper |
| `feature/device-auth` | ⚠️ Pendiente | Autenticación del dispositivo vs backend |
| `feature/face-recognition` | ⚠️ Pendiente | Módulo placeholder (lógica en EmbeddingService) |
| Modelo `mobilefacenet.tflite` | ⚠️ Pendiente | Colocar en `assets/`; hay fallback simulado activo |
| Backend NestJS | ❌ No iniciado | — |
| Panel Admin Next.js | ❌ No iniciado | — |

---

## Compilar y Ejecutar

### Requisitos

- JDK 17
- Android Studio Hedgehog o superior
- Android SDK API 34
- Dispositivo Android 8.0+ (API 26)

### Compilar APK debug

```bash
cd android-app
./gradlew assembleDebug --project-cache-dir C:/GH
```

> **Windows — error de ruta larga:** Si Gradle falla con `Could not move temporary workspace`, habilitar Long Paths (PowerShell como Administrador):
> ```powershell
> New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" -Name "LongPathsEnabled" -Value 1 -PropertyType DWORD -Force
> ```
> Reiniciar Windows y volver a compilar normalmente.

**APK generado en:**
```
android-app/app/build/outputs/apk/debug/app-debug.apk
```

### Abrir en Android Studio

1. **File → Open** → seleccionar la carpeta `android-app/`
2. Esperar sincronización de Gradle
3. Conectar dispositivo con **Depuración USB** activada
4. Ejecutar con **Run ▶** (`Shift + F10`)

---

## Cómo Usar el Reconocimiento Facial

### Paso 1 — Registrar empleado con foto

1. Abrir la app → pantalla de asistencia
2. Tocar el botón **"Registrar empleado"**
3. Llenar nombre, ID/código, departamento
4. Tocar **"Capturar foto"** → apuntar la cámara al rostro
5. ML Kit detecta el rostro automáticamente
6. Guardar → el embedding facial queda cifrado en Room

### Paso 2 — Registrar asistencia

1. Otorgar permiso de cámara en la pantalla principal
2. Apuntar la cámara al rostro del empleado registrado
3. La app compara el embedding en tiempo real (1:N)
4. Si la similitud supera el umbral → muestra **"Bienvenido [nombre]"**
5. Seleccionar tipo de evento: Entrada / Salida / Inicio comida / Fin comida
6. La asistencia se guarda con GPS automáticamente

### Colocar el modelo TFLite (reconocimiento real)

Sin el modelo, la app usa un **modo simulado** (embeddings por hash del bitmap) que permite desarrollo y pruebas pero no es biométrico real.

Para activar el reconocimiento real:
1. Obtener el modelo **MobileFaceNet** en formato `.tflite`
2. Copiarlo a:
   ```
   android-app/feature/employee-enrollment/src/main/assets/mobilefacenet.tflite
   ```
3. Recompilar

---

## Pantallas

| Pantalla | Ruta | Descripción |
|---|---|---|
| Splash | `splash` | Logo COATI, 4 segundos |
| Asistencia | `attendance` | Cámara + reconocimiento 1:N + GPS |
| Registro de empleado | `registro_empleado` | Alta con foto y datos |
| Lista de empleados | `employee_list` | Ver y eliminar empleados |
| Historial | `history` | Últimos 50 registros de asistencia |
| Configuración | `settings` | URL API, umbral facial (0.4), PIN admin |

---

## Seguridad

| Medida | Implementación |
|---|---|
| BD cifrada | SQLCipher — AES-256 sobre todo el archivo |
| Embeddings cifrados | AES-256-GCM por cada embedding antes de guardarse |
| Clave maestra | Android Keystore hardware-backed |
| Sin backup de biometría | `allowBackup="false"` en AndroidManifest |
| Embeddings sin salir del dispositivo | El servidor solo recibe metadatos, nunca el vector |

---

## Problemas Conocidos

| Problema | Estado | Fix aplicado |
|---|---|---|
| Crash al arrancar (Accompanist 0.32.0 + Compose 1.6.x) | Corregido | Actualizado a Accompanist 0.34.0 |
| Rutas largas en Gradle 8.9 Windows (MAX_PATH 260) | Workaround | `--project-cache-dir C:/GH` o habilitar LongPaths |
| Reconocimiento facial sin modelo TFLite | Activo | Fallback simulado (modo desarrollo) |
| `feature/device-auth` no implementado | Pendiente | — |

---

## Repositorio

```
https://github.com/raov15/CoatiCheck.git
```

---

## Licencia

Propiedad de **COATI Tecnología & Desarrollo**. Uso interno.
