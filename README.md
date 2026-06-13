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
| Reconocimiento facial | ML Kit Face Detection + MobileFaceNet TFLite | 16.1.5 / 2.14.0 |
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
│       ├── employee-enrollment/      # Registro de empleados, captura facial con ML Kit
│       ├── face-recognition/         # FaceRecognitionEngine, MobileFaceNet TFLite, Hilt DI
│       │   └── src/main/assets/      # mobilefacenet.tflite (5.2 MB)
│       ├── settings/                 # Configuración del kiosk
│       ├── location/                 # LocationTracker wrapper
│       └── device-auth/              # [Pendiente — autenticación vs backend]
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
| `feature/employee-enrollment` | ✅ Completo | Clean Architecture completa, captura facial con ML Kit, cifrado AES-256-GCM |
| `feature/face-recognition` | ✅ Completo | `FaceRecognitionEngine` (interfaz domain), `EmbeddingService` con TFLite real, `FaceRecognitionModule` Hilt |
| `feature/settings` | ✅ Completo | URL API, GPS timeout, umbral facial, PIN admin |
| `feature/location` | ✅ Completo | Fused Location Provider wrapper |
| `feature/device-auth` | ⚠️ Pendiente | Autenticación del dispositivo vs backend |
| Modelo `mobilefacenet.tflite` | ✅ Incluido | 5.2 MB en `feature/face-recognition/src/main/assets/`. Probado en dispositivo: **"Reconocido: Roberto (52%)"** |
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

### Instalar en dispositivo via ADB

```bash
# Conectar celular con Depuración USB activada
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
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
2. Tocar el ícono de **persona con +** (registro de empleado)
3. Llenar nombre, código de empleado, puesto
4. Tocar **"Capturar foto"** → apuntar la cámara frontal al rostro
5. ML Kit detecta y valida el rostro automáticamente (iluminación, posición, ojos abiertos)
6. Guardar → el embedding MobileFaceNet queda cifrado en Room

### Paso 2 — Registrar asistencia

1. En la pantalla **"Registro de Asistencia"** la cámara frontal se activa automáticamente
2. Apuntar la cámara al rostro — el óvalo se pone verde cuando detecta una cara
3. Tocar el botón de obturador (ícono de cámara verde)
4. MobileFaceNet calcula el embedding y busca coincidencia 1:N en la base de datos local
5. Si la confianza supera el umbral (55%) → muestra **"Reconocido: [nombre] (XX%)"**
6. Seleccionar tipo de evento: Entrada / Salida / Entrada Comida / Salida Comida
7. Tocar **"Guardar Asistencia"** → registro se guarda con GPS

---

## Pantallas

| Pantalla | Descripción |
|---|---|
| Splash | Logo COATI, 4 segundos |
| Registro de Asistencia | Cámara frontal + reconocimiento 1:N + GPS + tipo de registro |
| Registro de Empleado | Alta con foto, nombre, puesto, número de empleado |
| Empleados Registrados | Lista, ver perfiles y eliminar empleados |
| Historial | Últimos registros de asistencia del dispositivo |
| Configuración | URL API, umbral facial, GPS timeout, PIN admin |

---

## Seguridad

| Medida | Implementación |
|---|---|
| BD cifrada | SQLCipher — AES-256 sobre todo el archivo |
| Embeddings cifrados | AES-256-GCM por cada embedding antes de guardarse en Room |
| Clave maestra | Android Keystore hardware-backed (nunca sale del chip) |
| Sin backup de biometría | `allowBackup="false"` en AndroidManifest |
| Embeddings en el dispositivo | El servidor solo recibe metadatos, nunca el vector facial |

---

## Problemas Conocidos / Resueltos

| Problema | Estado | Fix aplicado |
|---|---|---|
| Crash al arrancar (Accompanist 0.32.0 + Compose 1.6.x) | ✅ Resuelto | Actualizado a Accompanist 0.34.0 |
| Rutas largas en Gradle 8.9 Windows (MAX_PATH 260) | Workaround | `--project-cache-dir C:/GH` o habilitar LongPaths |
| Reconocimiento facial sin modelo TFLite | ✅ Resuelto | MobileFaceNet incluido en assets — reconocimiento real activo |
| `Math.abs` overload ambiguity en Kotlin (Float) | ✅ Resuelto | Cambiado a `kotlin.math.abs` |
| `feature/attendance` acoplado a `employee-enrollment` | ✅ Resuelto | Refactorizado a `FaceRecognitionEngine` en módulo propio |
| `feature/device-auth` no implementado | ⚠️ Pendiente | Requerido antes de producción |

---

## Repositorio

```
https://github.com/raov15/CoatiCheck.git
```

---

## Licencia

Propiedad de **COATI Tecnología & Desarrollo**. Uso interno.
