# NEXORA: archivos y ubicación

El paquete contiene 47 archivos de código y esta guía.
Las rutas son relativas a tu carpeta CoatiCheck. Extrae las carpetas server y android-app
dentro de CoatiCheck aceptando reemplazar. No copies las rutas dentro del código.
Los archivos marcados NUEVO deben crearse respetando su carpeta.

## Aplicar

1. Conserva una copia de tu proyecto antes de reemplazar los archivos.
2. Copia TODO el paquete antes de compilar. Las partes nuevas dependen entre sí.
3. En CMD, dentro de CoatiCheck\server:

```cmd
docker compose up -d --build api web
docker compose logs --tail=50 api
cd ..\android-app
gradlew.bat assembleDebug
explorer app\build\outputs\apk\debug
```

4. Instala app-debug.apk como actualización, sin desinstalar ni borrar datos.
5. Abre la app con internet y actualiza el panel con Ctrl+F5.

La migración añade los campos y el catálogo en PostgreSQL automáticamente.
Android pasa de la base local 6 a 7 mediante una migración que conserva las checadas.
El nombre visible y el icono cambian a NEXORA. Se conserva el identificador interno
de la aplicación y el dominio de conexión actual para poder actualizar la app existente.

## Qué cambia

- Conectar dispositivos > Administrar cargos permite agregar, editar y retirar cargos por empresa.
  El catálogo alimenta Generar código, el alta compartida y el selector de la app móvil.
  Editar un cargo actualiza a los trabajadores asignados. Eliminarlo lo retira de nuevas
  selecciones y conserva los registros de los trabajadores que lo tenían.
  El administrador de cargos está en Conectar dispositivos con campos y botones compactos.
- Agregar trabajador compartido permite dejar Tablet en Sin tablet (opcional).
  Se siguen capturando la categoría y el centro de trabajo de cada día.
- Comida y descanso pueden registrarse tantas veces como sea necesario durante la jornada.
  Cada salida requiere su regreso. La salida final de jornada sigue cerrando el día.
  El servidor clasifica por horario; cada comida y descanso se numera por trabajador y día.
  Después de sincronizar, Android muestra la clasificación y el número recibidos del servidor.
  Mientras no se haya sincronizado, una pausa sin tipo confirmado se muestra como PAUSA.
- Una salida que coincide con la ventana configurada se guarda como Comida N o Descanso N.
  Si no coincide con ninguna, se guarda como Descanso N y genera una incidencia pendiente.
  El regreso conserva el tipo y número de su salida: Regreso de comer N o Regreso del descanso N.
  Para regresos se conserva
  la tolerancia previa de 10 minutos alrededor del fin programado. Las marcas fuera de
  rango se guardan como incidencias pendientes. Ya no se marca como incidencia una pausa
  solo por repetirse; se revisa su horario.
- La falta real de entrada se presenta en rojo como SIN REGISTRO DE ENTRADA (probable ausentismo).
  Se elimina la frase al cumplirse el plazo de los avisos automáticos que todavía estaban pendientes.
- Historial guarda y permite corregir las observaciones en la misma calificación.
  También funciona cuando una checada todavía no tiene categoría y se elige Historial.
  No se cambia la fecha/hora original de la checada al calificarla.
- Vacaciones usa días generados acumulados menos días tomados, sin limitar el resultado a cero.
  Un adelanto requiere confirmación y queda registrado. Si hay 12 y se toman 15, el saldo
  queda en -3. Al acumular 14 días más en el siguiente aniversario, queda en 11.
  Solo los trabajadores con saldo negativo se resaltan en rosa, tanto en el resumen de
  vacaciones como en la tabla de trabajadores.
  En el resumen, los saldos positivos se muestran en verde; los saldos en cero no llevan color.
  Las tarjetas son compactas y Registrar vacaciones / Ver historial están en la misma fila.
- El logo anterior se conserva junto al nombre NEXORA en el acceso al panel.

## Archivos exactos

### Servidor y datos

- Reemplazar `server/src/index.ts`
- Reemplazar `server/src/db/migrate.ts`
- **NUEVO** `server/src/routes/nexora.ts`
- Reemplazar `server/src/routes/administrative-attendance.ts`
- Reemplazar `server/src/routes/configuration-security.ts`
- **NUEVO** `server/src/services/vacation-balance.ts`
- Reemplazar `server/src/routes/admin.ts`
- Reemplazar `server/src/routes/devices.ts`
- Reemplazar `server/src/routes/employees.ts`
- Reemplazar `server/src/routes/shared-checker-employees.ts`
- Reemplazar `server/src/routes/attendance.ts`
- Reemplazar `server/src/routes/shared-checker.ts`
- Reemplazar `server/src/services/break-policy.ts`
- Reemplazar `server/src/services/missing-exits.ts`

### Panel web

- Reemplazar `server/public/img/cooatiilogo.jpeg`
- Reemplazar `server/public/index.html`
- **NUEVO** `server/public/nexora.js`
- Reemplazar `server/public/configuration-security.js`
- Reemplazar `server/public/payroll-report.js`
- Reemplazar `server/public/labor-report.js`
- Reemplazar `server/public/administrative-attendance.js`
- Reemplazar `server/public/report-preview.js`
- Reemplazar `server/public/checador.html`
- Reemplazar `server/public/superadmin.html`

### Nombre e icono NEXORA

- Reemplazar `android-app/app/src/main/res/values/strings.xml`
- Reemplazar `android-app/app/src/main/kotlin/com/coati/checador/MainActivity.kt`
- Reemplazar `android-app/app/src/main/AndroidManifest.xml`
- **NUEVO** `android-app/app/src/main/res/drawable/nexora_icon.xml`
- Reemplazar `android-app/app/src/main/res/drawable/logo_coati.jpeg`

### Pausas y almacenamiento móvil

- Reemplazar `android-app/feature/attendance/src/main/kotlin/com/coati/checador/feature/attendance/AttendanceViewModel.kt`
- Reemplazar `android-app/feature/attendance/src/main/kotlin/com/coati/checador/feature/attendance/AttendanceScreen.kt`
- Reemplazar `android-app/feature/attendance/src/main/kotlin/com/coati/checador/feature/attendance/HistoryScreen.kt`
- Reemplazar `android-app/core/network/src/main/kotlin/com/coati/checador/core/network/CoatiApiService.kt`
- Reemplazar `android-app/core/network/src/main/kotlin/com/coati/checador/core/network/dto/AttendanceDtos.kt`
- Reemplazar `android-app/core/network/src/main/kotlin/com/coati/checador/core/network/dto/EmployeeWorkAssignmentDtos.kt`
- Reemplazar `android-app/core/database/src/main/kotlin/com/coati/checador/core/database/CoatiDatabase.kt`
- Reemplazar `android-app/core/database/src/main/kotlin/com/coati/checador/core/database/di/DatabaseModule.kt`
- Reemplazar `android-app/core/database/src/main/kotlin/com/coati/checador/core/database/entity/AttendanceRecordEntity.kt`
- Reemplazar `android-app/core/database/src/main/kotlin/com/coati/checador/core/database/dao/AttendanceRecordDao.kt`
- Reemplazar `android-app/core/sync/src/main/kotlin/com/coati/checador/core/sync/AttendanceSyncWorker.kt`
- Reemplazar `android-app/core/sync/src/main/kotlin/com/coati/checador/core/sync/ExitReminderWorker.kt`
- Reemplazar `android-app/core/sync/src/test/kotlin/com/coati/checador/core/sync/ExitReminderPolicyTest.kt`
- Reemplazar `android-app/core/sync/src/main/kotlin/com/coati/checador/core/sync/EmployeeWorkAssignmentSyncWorker.kt`

### Catálogo de cargos en Android

- Reemplazar `android-app/feature/employee-enrollment/build.gradle.kts`
- Reemplazar `android-app/feature/employee-enrollment/src/main/kotlin/com/coati/checador/feature/employeeenrollment/ui/viewmodel/RegistroEmpleadoViewModel.kt`
- Reemplazar `android-app/feature/employee-enrollment/src/main/kotlin/com/coati/checador/feature/employeeenrollment/ui/screen/PantallaRegistroEmpleado.kt`
- Reemplazar `android-app/feature/employee-enrollment/src/main/kotlin/com/coati/checador/feature/employeeenrollment/domain/usecase/RegistrarEmpleadoUseCase.kt`

## Verificación realizada y límites

- El servidor TypeScript compila.
- Los scripts de index, checador, superadmin y nexora.js tienen sintaxis válida.
- Se probaron el catálogo (agregar, renombrar y retirar), el alta sin tablet, la confirmación
  del adelanto, el saldo negativo, su compensación en el siguiente aniversario y el guardado
  sin duplicar vacaciones mediante handlers con base simulada y consultas sobre SQLite.
- Se comprobó el guardado/edición de observaciones de Historial y la conservación de la fecha.
- Se probó con SQLite que la migración móvil agrega sus campos conservando registros existentes.
- No se ejecutó esta migración contra tu PostgreSQL ni se compiló Android en este entorno,
  porque no hay Java/SDK disponibles. El envío y la visualización deben comprobarse al instalar.
- La detección de pausas omitidas mantiene la revisión de las jornadas recientes de 7 días
  con entrada registrada, sin vacaciones ni día de descanso, usando la categoría del día.
- El formulario de comidas/descansos contempla horarios dentro del mismo día; las ventanas
  que cruzan medianoche requieren una adaptación adicional.
- Si modificaste el proyecto por fuera de este chat desde el último paquete, compara esos
  cambios antes de reemplazar, porque estos archivos parten de la versión trabajada aquí.
