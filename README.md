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
| `feature/device-auth` | 🟡 Implementado | Enrolamiento mediante código temporal, token y branding empresarial; falta validación productiva completa |
| Modelo `mobilefacenet.tflite` | ✅ Incluido | 5.2 MB en `feature/face-recognition/src/main/assets/`. Probado en dispositivo: **"Reconocido: Roberto (52%)"** |
| Backend Express + PostgreSQL | 🟡 Implementado | Empresas, usuarios, sitios, logos, dispositivos, enrolamiento y sincronización |
| Portal Admin | 🟡 Implementado | Portal estático con login, cambio de contraseña, empresas, sitios, usuarios, códigos, celulares y asistencias |

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
| `feature/device-auth` no implementado | ✅ Resuelto parcialmente | Enrolamiento mediante código temporal y branding; falta prueba contra backend |

## Portal administrativo y empresas

El backend soporta el vínculo seguro entre empresa, usuario, sitio y celular:

1. El administrador inicia sesión en `/api/admin/login`.
2. El administrador crea una empresa y puede cargar un logo PNG/JPG de hasta 2 MB.
3. Se genera un código temporal con `/api/admin/companies/:companyId/enrollment-codes`.
4. El celular utiliza el código en `POST /api/devices/enroll`.
5. El servidor asigna la empresa y sitio desde el código, no desde datos declarados por el celular.
6. Android guarda el nombre, ID y URL del logo para utilizarlo offline.

Variables obligatorias del servidor:

```env
JWT_SECRET=secreto-largo-y-aleatorio
ADMIN_BOOTSTRAP_USERNAME=admin
ADMIN_BOOTSTRAP_PASSWORD=contraseña-temporal-segura
```

`admin/admin` no debe configurarse en producción. La contraseña bootstrap se almacena como hash y obliga a cambiarla en el primer acceso.

Para desplegar el backend:

```bash
cd server
cp .env.example .env
docker compose up -d --build
```

En Windows PowerShell se puede usar:

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

Antes de iniciar, reemplaza `JWT_SECRET` y `ADMIN_BOOTSTRAP_PASSWORD` en `.env`. El archivo `.env` no debe subirse al repositorio.

Endpoints principales:

| Método | Endpoint | Uso |
|---|---|---|
| POST | `/api/admin/login` | Acceso administrativo |
| POST | `/api/admin/change-password` | Cambio de contraseña inicial |
| POST | `/api/admin/companies` | Crear empresa y logo |
| POST | `/api/admin/companies/:companyId/enrollment-codes` | Generar código temporal |
| POST | `/api/devices/enroll` | Asociar celular mediante código |
| GET | `/api/devices/branding` | Obtener empresa del dispositivo autenticado |
| GET | `/api/admin/companies/:companyId/attendance` | Consultar asistencias por empresa |

### Despliegue Docker

El despliegue actual utiliza tres servicios:

| Servicio | Función | Exposición |
|---|---|---|
| `coati-web` | Nginx, archivos estáticos y proxy `/api` | Puerto 80 dentro de Docker |
| `coati-api` | API Express/TypeScript | Solo red interna, puerto 3000 |
| `coati-db` | PostgreSQL 16 | Solo red interna |

`coati-web` y `coati-api` se conectan a la red externa `nginx-proxy` para que Nginx Proxy Manager pueda publicar el portal sin exponer directamente el puerto 3000.

```powershell
cd server
Copy-Item .env.example .env
# Editar .env y reemplazar los secretos
docker compose up -d --build
docker compose ps
```

Comprobaciones desde el servidor:

```powershell
docker run --rm --network nginx-proxy curlimages/curl:8.10.1 http://coati-web:80/api/health
docker logs coati-api --tail 100
```

En Nginx Proxy Manager, el upstream debe ser `coati-web`, puerto `80` y esquema `http`. Cloudflare debe apuntar al servidor y el certificado del origen debe estar correctamente configurado antes de usar `Full (strict)`. Un error HTTP `525` indica un problema de handshake TLS entre Cloudflare y el origen, no una credencial incorrecta del portal.

### Contraseña administrativa

`ADMIN_BOOTSTRAP_PASSWORD` solo se utiliza al crear inicialmente el usuario bootstrap. Si `admin` ya existe en la base de datos, cambiar `.env` no cambia su hash. Para restablecer una contraseña existente debe realizarse una operación administrativa controlada sobre PostgreSQL; no se deben borrar volúmenes ni ejecutar `docker compose down -v` en producción.

El login real debe abrirse desde el dominio publicado o desde una URL que tenga acceso al proxy `/api`. Una copia estática servida por un servidor de preview puede mostrar el formulario, pero no garantiza que las peticiones de autenticación lleguen a `coati-api`.

## Estado de validación — 2 de agosto de 2026

| Verificación | Estado | Resultado |
|---|---|---|
| Repositorio | ✅ | `master` sincronizado con GitHub |
| Backend Docker | ✅ | Imagen construida con `npm run build` exitoso |
| PostgreSQL | ✅ | `coati-db` saludable con volumen persistente |
| API | ✅ | `coati-api` saludable |
| Frontend | ✅ | `coati-web` activo y conectado a `nginx-proxy` |
| APK Android debug | ✅ | `:app:assembleDebug` exitoso |
| Login administrativo productivo | ⚠️ | Requiere confirmar contraseña existente y URL con proxy |
| HTTPS público | ⚠️ | Requiere validar certificado/origen si Cloudflare devuelve 525 |

No se versionan `.env`, secretos, cargas de logos ni volúmenes Docker.

### Validación de sincronización Android

Los registros que aparecen inicialmente en el portal pueden corresponder a datos de prueba insertados manualmente en PostgreSQL. Por ejemplo, los registros con identificador `06f0d426-c456-4d05-b1bf-a966d71b9601` y fecha `29/06/2026` no prueban por sí mismos una sincronización originada en Android.

Para validar el flujo completo:

1. Configurar Android con `https://cooatii.com`.
2. Confirmar que el dispositivo esté enrolado y no aparezca como “Solo local”.
3. Registrar una asistencia nueva desde la pantalla principal.
4. Mantener el celular conectado a internet y esperar la sincronización.
5. Revisar los logs de `coati-api` durante la prueba.
6. En el portal seleccionar la empresa y presionar **Cargar asistencias**.
7. Confirmar un identificador nuevo y una fecha/hora actual, diferente de los registros manuales existentes.

```powershell
docker logs -f coati-api
docker compose ps
```

La prueba se considera aprobada únicamente cuando una asistencia creada en Android aparece posteriormente en PostgreSQL y en el portal. No se requiere una contraseña adicional de la empresa: la empresa se determina por el dispositivo enrolado y el token autorizado.

---

## Repositorio

```
https://github.com/raov15/CoatiCheck.git
```

---

## Licencia

Propiedad de **COATI Tecnología & Desarrollo**. Uso interno.
