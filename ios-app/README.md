# CoatiCheck iOS

Aplicación iOS de CoatiCheck desarrollada con **Kotlin Multiplatform Mobile (KMP)** y **SwiftUI**.

## Estructura

```
ios-app/
├── shared/                 # Módulo KMP con lógica de negocio compartida
│   ├── src/commonMain/     # Código común: modelos, casos de uso, Ktor, SQLDelight
│   │   └── app/SharedApp.kt   # Fachada única que consume la app Swift
│   ├── src/androidMain/    # Actuals Android (Keystore, TFLite, WorkManager)
│   ├── src/iosMain/        # Actuals iOS (Keychain, Vision, CoreLocation, BGTask)
│   └── src/commonTest/     # Tests unitarios compartidos
├── iosApp/                 # Proyecto Xcode con UI en SwiftUI
│   ├── iosApp.xcodeproj
│   └── iosApp/
│       ├── App.swift               # @main + AppDelegate (BGTaskScheduler)
│       ├── ContentView.swift       # Router + MainTabView
│       ├── AppDependencies.swift   # Construcción de SharedApp + AppState
│       ├── KotlinInterop.swift     # Data<->KotlinByteArray, Result de Kotlin
│       ├── CoreMLFaceEngine.swift  # Motor facial real (si hay modelo CoreML)
│       ├── CameraService.swift     # AVFoundation: preview + captura JPEG
│       ├── Views/                  # DeviceAuth, Attendance, Enrollment, History, Settings
│       └── ViewModels/             # Un ObservableObject por pantalla
└── gradle/
```

## Requisitos

- macOS 13+ (para compilar el target iOS).
- Xcode 15+ con Command Line Tools (necesario para firmar y ejecutar en simulador/dispositivo).
- JDK 17+ (se usa el ubicado en `../.jdk/jdk17` si está disponible).
- Android SDK (para compilar el target Android del módulo `shared`); se usa el ubicado en `../.android-sdk`.

## Configuración del entorno

Si no tienes `JAVA_HOME` y `ANDROID_HOME` configurados globalmente, usa las rutas locales del repositorio:

```bash
export JAVA_HOME=/Users/pc-dan/Documents/GitHub/CoatiCheck/.jdk/jdk17/Contents/Home
export ANDROID_HOME=/Users/pc-dan/Documents/GitHub/CoatiCheck/.android-sdk
```

## Compilar el módulo compartido

### Framework para iOS

Genera el XCFramework que consume el proyecto Xcode:

```bash
cd ios-app
./gradlew :shared:assembleCoatiCheckSharedReleaseXCFramework
```

Salida:
- `shared/build/XCFrameworks/release/CoatiCheckShared.xcframework` (referenciado por el proyecto Xcode)

### Tests unitarios

```bash
./gradlew :shared:allTests   # JVM + iOS simulator
```

### Compilación Android del módulo compartido

```bash
./gradlew :shared:compileDebugKotlinAndroid
```

## Compilar la app iOS

```bash
cd ios-app/iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' -configuration Debug build CODE_SIGNING_ALLOWED=NO
```

O abre `ios-app/iosApp/iosApp.xcodeproj` en Xcode, selecciona un simulador iOS 16+ y presiona **Run** (`Cmd + R`).

El XCFramework se enlaza como framework estático; la app añade `-lsqlite3` en `OTHER_LDFLAGS` para el driver SQLDelight.

## Flujo funcional

1. **Registro de dispositivo**: en el primer arranque se pide la URL del servidor y se registra el dispositivo (`/devices/register` o `/devices/enroll` con código). El token JWT se guarda en Keychain.
2. **Asistencia**: tras reconocer el rostro, se elige el movimiento permitido por la jornada local: entrada, hasta dos pausas con su regreso, o salida. La salida requiere confirmación. Los movimientos se validan por día en la zona `America/Mexico_City` y se guardan como `PENDING` para sincronizarlos.
3. **Enrolamiento**: se detecta y recorta el rostro antes de generar y cifrar el embedding; una imagen sin rostro o un embedding inválido no se registran.
4. **Historial**: asistencias locales con estado de sincronización.
5. **Configuración**: URL del servidor (aplicable en caliente), PIN admin (PBKDF2) y sincronización manual.
6. **Sincronización**: orquestador de 4 pasos (empleados, work-assignments, asistencias, ubicaciones) con refresh de token ante 401/403; `BGAppRefreshTask` con identificador `com.coati.checador.ios.sync`.

## Motor facial

- `mobilefacenet.mlmodel` se genera a partir del `mobilefacenet.tflite` incluido en Android (SHA-256 `be4bc7cfc53f7bc336d0f28b1ab92535f618c913a422b683210750f6b5354854`). Xcode lo compila e incluye en la aplicación.
- La entrada es RGB de 112 × 112 píxeles, con normalización `(canal - 127.5) / 128`; la salida de 192 valores se normaliza con L2 en la app. La conversión se contrastó con TFLite usando cinco entradas deterministas; el error absoluto máximo fue inferior a 0.000001.
- Solo se comparan perfiles generados por la misma versión del modelo. Los perfiles anteriores del motor simulado deben reemplazarse mediante un enrolamiento nuevo; no son compatibles con el modelo real.
- Si el modelo no se carga, se bloquean el enrolamiento y la asistencia. El reconocimiento facial requiere validación adicional con personas y dispositivos reales antes de usarse en producción.

## Notas

- El target mínimo de iOS es **16.0**.
- El bundle ID es `com.coati.checador.ios`.
- El backend se reutiliza sin cambios.
- No hay logging de cuerpos de red en release.
