# Chrono Remote

Controla una TV con Google TV / Android TV desde uno o varios teléfonos, con el estilo de ChronOS.
La TV es la central; los teléfonos son la extensión.

- **TV**: Chrono es la pantalla de inicio (fondo naranjo y carpetas verdes como ChronOS: reloj arriba a la derecha,
  logo abajo a la izquierda), con apps fijadas y todas las apps en tarjetas grandes. Muestra fotos, videos y música
  que mandan los teléfonos.
- **Teléfono**: Control (cruceta, Atrás, Inicio, apagar, volumen, multimedia, canales, escribir), Apps (abrir, fijar,
  desinstalar apps de la TV) y Enviar (fotos, videos, música, enlaces de YouTube; también desde Compartir).

## Control total (en cualquier app de la TV)

El teléfono usa el mismo protocolo que la app oficial "Google TV" (Android TV Remote v2, puertos 6466/6467).
Las teclas llegan al sistema de la TV, así que funcionan en YouTube, Netflix, ajustes, etc., sin permisos en la TV.
Se vincula una vez: en el teléfono, tarjeta "Vincular con la TV" (o menú ⋯) → la TV muestra un código de 6
caracteres → se escribe en el teléfono.

## Chrono como inicio de la TV

Google TV no deja elegir otro inicio en sus ajustes. Chrono se pone encima cada vez que aparece el inicio de Google
(botón Inicio, al salir de una app, al prender la TV). Para notarlo necesita **"Uso de apps"** (Ajustes de la TV →
Apps → Acceso especial) o la accesibilidad (versión completa). En la TV: Ajustes de Chrono → "Abrir el inicio de
Google TV" deja verlo 2 minutos.

Opcional (definitivo, con un computador y ADB): `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx`
deja a Chrono como único inicio (se revierte con `pm enable`).

## Instalar

Instala `ChronoRemote-liviana.apk` (de Releases) en la TV y en cada teléfono, en el mismo WiFi.
En la TV, la primera vez: tarjeta "Prepara la TV" → "Mostrar sobre otras apps" y "Uso de apps".
