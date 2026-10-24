# Chrono Remote — guía para IAs (y personas)

Responde y comenta siempre **en español** (el dueño escribe desde el teléfono). Proyecto hermano de ChronOS
(repo `edoloco/ChronOS`), mucho más simple: la TV (Google TV / Android TV) es la central y los teléfonos el control.

## Reglas
1. Paquete `cl.chronoremote`: no cambiarlo. Java, sin AndroidX ni librerías. minSdk 28, targetSdk 34.
2. Una sola app para TV y teléfono (`U.isTv`). Dos versiones: `completa` (con accesibilidad `TvAccess`) y `liviana`
   (sin ella; Play Protect de Android 14 bloqueaba la completa en la TV). La TV usa la liviana.
3. Cada envío a `main` compila y publica una versión (GitHub Actions); los aparatos se actualizan solos (`Updater`).
4. Diseño como ChronOS: fondo naranjo `#F57C1F`, carpetas verdes `#2FA866` con corte en diagonal (`FolderBar`).

## Mapa
- Teléfono: `PhoneActivity` (Control, Apps, Enviar, Canales), `Remote` (conexión propia con la TV, puerto 47830,
  descubrimiento NSD), `AtvRemote` (**control total**: protocolo Android TV Remote v2 — vincular en 6467 con TLS y
  certificado propio en AndroidKeyStore + código de 6 hex; controlar en 6466; protobuf armado a mano), `DPadView`, `Glyph`.
- TV: `TvActivity` (inicio estilo ChronOS; también HOME), `TvService` (servidor para los teléfonos, abrir apps,
  volumen, canales; **reemplaza el inicio de Google TV**: si aparece, pone Chrono encima — con "Uso de apps" o
  `TvAccess`), `TvServer`, `ViewerActivity` (fotos/videos/música enviados), `Channels`, `FileHttp`, `Boot`.

## Ojo
- Las teclas en otras apps van por `AtvRemote` (no hay otra forma sin root/accesibilidad). "Inicio" desde el teléfono
  abre Chrono por la conexión propia (la tecla Inicio mostraría primero el inicio de Google).
