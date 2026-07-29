# Despliegue seguro detrás de Nginx Proxy Manager

Esta configuración usa la red Docker externa `nginx-proxy`, que ya existe en el servidor. No publica el puerto 3000 en el host y no detiene ni modifica los contenedores existentes.

## Preparación

En el servidor, copia `.env.example` como `.env` y configura secretos reales:

```powershell
Copy-Item .env.example .env
```

El archivo `.env` debe contener `JWT_SECRET` y `ADMIN_BOOTSTRAP_PASSWORD`.

Confirma que la red compartida existe:

```powershell
docker network inspect nginx-proxy
```

Si la red no existe, no la crees automáticamente: revisa primero el nombre de la red usada por Nginx Proxy Manager.

## Arranque sin afectar producción

Ejecuta desde la carpeta `server`:

```powershell
docker compose config
docker compose up -d --build
docker compose ps
```

Este Compose solo administra `coati-api` y `coati-db`. No debe ejecutarse `docker compose down` desde otro proyecto ni eliminar la red `nginx-proxy`.

## Configuración en Nginx Proxy Manager

Crea un Proxy Host con:

```text
Domain Names: el dominio real configurado en DNS
Scheme: http
Forward Hostname/IP: coati-api
Forward Port: 3000
```

Activa `Block Common Exploits` y configura el certificado SSL desde Nginx Proxy Manager. El backend interno usa HTTP; HTTPS termina en NPM.

No uses `coati-web:80` para esta API. Ese destino corresponde a otro contenedor y puede dejar el proxy Offline.

## Verificación

Desde la red compartida, prueba sin exponer el puerto al host:

```powershell
docker run --rm --network nginx-proxy curlimages/curl:8.10.1 http://coati-api:3000/api/health
```

La respuesta esperada contiene `"status":"ok"`.

Después de cambiar el Proxy Host, prueba:

```powershell
curl.exe -I https://tu-dominio-real/api/health
```

Si el DNS apunta a este servidor y el certificado coincide, debe devolver HTTP 200.