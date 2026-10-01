# CoatiCheck iOS

Aplicación iOS de CoatiCheck desarrollada con **Kotlin Multiplatform Mobile (KMP)**.

## Estructura

```
ios-app/
├── shared/                 # Módulo KMP con lógica de negocio compartida
│   ├── src/commonMain/     # Código común: modelos, casos de uso, Ktor, SQLDelight
│   ├── src/androidMain/    # Actuals Android (Keystore, drivers SQLite, WorkManager)
│   ├── src/iosMain/        # Actuals iOS (Keychain, drivers SQLite, BGTaskScheduler)
│   └── src/commonTest/     # Tests unitarios compartidos
├── iosApp/                 # Proyecto Xcode con UI en SwiftUI
│   ├── iosApp.xcodeproj
│   └── iosApp/
│       ├── App.swift
│       ├── ContentView.swift
│       └── ...
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
./gradlew :shared:assembleCoatiCheckSharedXCFramework
```

Salida:
- `shared/build/XCFrameworks/debug/CoatiCheckShared.xcframework`
- `shared/build/XCFrameworks/release/CoatiCheckShared.xcframework`

### Tests unitarios

```bash
./gradlew :shared:testDebugUnitTest
```

### Compilación Android del módulo compartido

```bash
./gradlew :shared:compileDebugKotlinAndroid
```

## Abrir y ejecutar la app iOS

1. Abre `ios-app/iosApp/iosApp.xcodeproj` en Xcode.
2. Selecciona un simulador o dispositivo iOS 16+.
3. Presiona **Run** (`Cmd + R`).

La primera vez Xcode vinculará automáticamente el XCFramework referenciado en `Frameworks, Libraries, and Embedded Content`.

> **Nota:** Si mueves el proyecto o cambias el nombre del XCFramework, vuelve a ejecutar `./gradlew :shared:assembleCoatiCheckSharedXCFramework` y refresca el proyecto Xcode.

## Próximos pasos de migración

1. **Dominio y modelos**: migrar `Employee`, `AttendanceRecord`, `Device`, `SyncStatus`, etc. desde `android-app/core/common`.
2. **Networking**: reemplazar Retrofit por Ktor con los mismos endpoints del backend.
3. **Base de datos**: portar el esquema Room completo a archivos `.sq` de SQLDelight (tablas, índices, foreign keys).
4. **Seguridad**: implementar `SecureStorage` con Keychain en iOS y Keystore en Android.
5. **Reconocimiento facial**: integrar CoreML en iOS y mantener TFLite en Android.
6. **Cámara y ubicación**: implementar `expect/actual` con AVFoundation/CoreLocation en iOS.
7. **Sincronización**: reemplazar WorkManager por `BGTaskScheduler` en iOS.
8. **UI SwiftUI**: construir pantallas de autenticación de dispositivo, asistencia, enrollment, historial y ajustes.

## Correcciones a aplicar también en Android

Durante el port conviene corregir en `android-app`:
- `AttendanceViewModel.recognizeFace()` debe comparar embeddings reales.
- `EmployeeDao.countPending()` debe comparar contra `'PENDING'` mayúscula.
- Desactivar logging de red BODY en release.
- Hashear el PIN de administrador.
- Eliminar fallback HTTP hardcodeado.

## Notas

- El target mínimo de iOS es **16.0**.
- El bundle ID inicial es `com.coati.checador.ios`.
- El backend se reutiliza sin cambios.
