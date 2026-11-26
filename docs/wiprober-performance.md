# Exportación de rendimiento de WiProber (`wiprober-performance.json`)

Especificación del fichero que genera el fork [mpedreira/WiProber](https://github.com/mpedreira/WiProber) para que una aplicación externa (p. ej. una web de análisis) lo cruce con el `.esx` de Ekahau.

Estado: **formato 1**, implementado desde la release `v3.0.0-throughput.1` y ampliado (sin romper nada) en `v3.0.0-throughput.2`. La guía de medición de `v3.0.0-throughput.3` no cambia la exportación.

## 1. Qué se exporta

| Situación del proyecto | Resultado de *Exportar* |
|---|---|
| Ningún punto tiene mediciones de rendimiento | `Proyecto.esx`, igual que la app original |
| Al menos un punto tiene mediciones | `Proyecto.zip` |

Contenido del ZIP (siempre exactamente estas dos entradas, en la raíz):

```
Proyecto.zip
├── Proyecto.esx                 ← el mismo .esx que daría una exportación normal; Ekahau lo abre como siempre
└── wiprober-performance.json    ← las mediciones (este documento)
```

Nada de WiProber se escribe dentro del `.esx`, y los campos nativos `throughputSessions`/`pingSessions` de Ekahau no se rellenan (su esquema no es público).

## 2. Estructura del JSON

```json
{
  "format": "wiprober-performance",
  "version": 1,
  "generatedAt": "2026-11-26T10:41:03.512Z",
  "esxFile": "Proyecto.esx",
  "measurements": [ { …fila… }, … ]
}
```

| Campo | Tipo | Descripción |
|---|---|---|
| `format` | string | Siempre `"wiprober-performance"` |
| `version` | int | `1`. Solo cambiará si se rompe la compatibilidad; los campos nuevos se añaden sin subirla |
| `generatedAt` | string ISO-8601 UTC | Momento de la exportación |
| `esxFile` | string | Nombre del `.esx` que acompaña al JSON en el ZIP |
| `measurements` | array | Una fila por **punto Stop-and-Go × destino medido** (puede estar vacío) |

## 3. Campos de cada fila

Regla general: **lo no medido o no disponible es `null`, nunca `0`**. Todos los campos aparecen siempre (con `null` si no hay valor). Si un test falló, el motivo está en `error`.

### Posición y enlace con el `.esx`

| Campo | Tipo | Descripción |
|---|---|---|
| `surveyId` | string UUID | Id del `survey-<surveyId>.json` de ese punto **en el `.esx` del mismo ZIP** |
| `floorPlanId` | string UUID | Planta (`floorPlans.json`). Estable entre exportaciones |
| `floorName` | string | Nombre de la planta |
| `x`, `y` | number | Posición en **píxeles de la imagen original del plano**, origen arriba-izquierda. Mismo valor que `surveys[0].routePoints[0][0].location` del survey |
| `measuredAt` | string ISO-8601 UTC | Inicio de ese test |

### Test

| Campo | Tipo | Descripción |
|---|---|---|
| `target` | string | `internet`, o la etiqueta configurada en el móvil para el servidor iPerf3 (por defecto `corporate`; p. ej. `inditex`) |
| `method` | string | `iperf3-tcp` (servidor iPerf3) o `http` (test de velocidad de Internet) |
| `durationSeconds` | int | Segundos por dirección (por defecto 5) |
| `downMbps` | number \| null | Bajada en Mbit/s (10⁶ bit/s), 1 decimal |
| `upMbps` | number \| null | Subida en Mbit/s, 1 decimal |
| `latencyMs` | number \| null | Latencia = **mínimo** de 10 handshakes TCP contra el servidor, ms |
| `latencyAvgMs` | number \| null | Media de esas 10 muestras, ms |
| `jitterMs` | number \| null | Diferencia media entre muestras consecutivas, ms |
| `latencySamples` | int \| null | Muestras tomadas (10) |
| `error` | string \| null | Motivo de lo que falló; `null` si todo se midió |

### AP conectado durante el test

Leído al **empezar** el test, salvo `bssidEnd`.

| Campo | Tipo | Descripción |
|---|---|---|
| `ssid` | string \| null | SSID conectado |
| `bssidStart` | string \| null | BSSID (minúsculas) al empezar |
| `bssidEnd` | string \| null | BSSID al terminar. **Distinto de `bssidStart` ⇒ hubo roaming durante el test** |
| `rssi` | int \| null | Señal del AP conectado, dBm |
| `linkMbps` | int \| null | Velocidad de enlace de **transmisión**, Mbit/s |
| `rxLinkMbps` | int \| null | Velocidad de enlace de **recepción**, Mbit/s |
| `frequencyMhz` | int \| null | Frecuencia del canal, MHz |
| `channel` | int \| null | Canal (bandas 2,4 / 5 / 6 GHz) |
| `channelWidthMhz` | int \| null | 20, 40, 80, 160 o 320 (del escaneo de ese punto) |
| `wifiStandard` | string \| null | P. ej. `"802.11ax (Wi-Fi 6/6E)"`, `"802.11ac (Wi-Fi 5)"` |
| `bssStationCount` | int \| null | Clientes asociados según el elemento *BSS Load* del beacon (solo si el AP lo anuncia) |
| `bssChannelUtilizationPct` | int \| null | Ocupación del canal 0–100 % según *BSS Load* |

### Dispositivo

| Campo | Tipo | Descripción |
|---|---|---|
| `deviceModel` | string \| null | Fabricante y modelo del móvil (el RSSI varía varios dB entre modelos) |
| `androidVersion` | string \| null | Versión de Android |

### Valores de `error`

| Texto | Significado |
|---|---|
| `not connected to a Wi-Fi network` | Sin WiFi: no se envió nada; todos los valores son `null` |
| `iPerf3 server not configured` / `download URL not configured` | Destino activado sin servidor/URL |
| `latency: <motivo>; download/upload skipped: server unreachable` | No se pudo ni conectar: se saltan las transferencias |
| `download: <motivo>` / `upload: <motivo>` | Falló esa dirección (p. ej. `iperf3 server is busy`, `HTTP 403 from speed.cloudflare.com`) |
| `upload: not configured` | Test de Internet sin URL de subida |

Varios motivos se separan con `; `.

## 4. Cómo cruzarlo con el `.esx`

- **Dentro del mismo ZIP**: por `surveyId` → `survey-<surveyId>.json`. El escaneo WiFi completo del punto (todos los APs) está en ese survey (`wifiTracks[0]`) y en su binario `track-<binaryFileId>.bin`.
- **Entre exportaciones distintas** (histórico, comparativas): por `floorPlanId` + `x` + `y` (tolerancia sugerida 0,5 px). El `surveyId` de los puntos se **regenera en cada exportación**; `floorPlanId` y las coordenadas no cambian.
- **A metros**: `metros = píxeles × metersPerUnit` (de `floorPlans.json`). Tamaño del plano: `width`/`height`; imagen: `image-<imageId>`.
- Puntos del **modo continuo** y puntos **importados de otro `.esx`** no tienen filas.

Datos que tu app puede derivar del `.esx` sin cambios en WiProber: APs en el mismo canal por encima de −82 dBm (cocanal), APs por encima de −67 dBm (cobertura redundante), diferencia entre el mejor y el segundo AP, y el *BSS Load* de cualquier AP (los elementos del beacon están en `informationElements` de `accessPointMeasurements.json`).

## 5. Cómo se mide

- Tras cada escaneo Stop-and-Go, si hay destinos activados, se mide en el mismo punto, uno detrás de otro (nunca en paralelo, para no falsear resultados). Una ventana muestra el progreso.
- **Todo el tráfico va atado a la red WiFi**; nunca sale por datos móviles.
- **iPerf3**: cliente propio del protocolo iperf3, TCP, 1 flujo. Bajada = modo inverso medido por el móvil; subida = lo que reporta el servidor como recibido.
- **Internet**: HTTP con 4 conexiones en paralelo; por defecto Cloudflare (`speed.cloudflare.com/__down` y `/__up`), configurable (p. ej. LibreSpeed).
- **Latencia**: 10 handshakes TCP al puerto del servidor (iPerf3) o al 443/80 de la URL de descarga. Si el primero falla, se salta el resto del destino.
- **No hay ruido ni SNR**: Android no expone el nivel de ruido.
- Configuración (servidores, URLs, etiqueta del destino) solo en el móvil, nunca en el código.

## 6. Lo que no se exporta

- La **guía de medición** (propuesta de siguiente punto, casillas saltadas, cobertura en degradado): es solo visual en la app.
- Escaneos sin tests de rendimiento: solo están en el `.esx`.

## 7. Historial

| Release | Cambios en este fichero |
|---|---|
| `v3.0.0-throughput.1` | Formato 1: ZIP con `.esx` + JSON; posición, `surveyId`, `target`, `method`, `downMbps`, `upMbps`, `latencyMs`, `ssid`, `bssidStart`/`bssidEnd`, `rssi`, `linkMbps`, `error`. Ventana de progreso y salto rápido de servidores caídos |
| `v3.0.0-throughput.2` | Añadidos `rxLinkMbps`, `frequencyMhz`, `channel`, `channelWidthMhz`, `wifiStandard`, `bssStationCount`, `bssChannelUtilizationPct`, `latencyAvgMs`, `jitterMs`, `latencySamples`, `deviceModel`, `androidVersion`. Latencia con 10 muestras |
| `v3.0.0-throughput.3` | Sin cambios en la exportación (guía de medición en la app) |

## 8. Propuesto, aún no implementado

Sujeto a aprobación; cuando se haga se documentará aquí:

- **Pruebas de servicios de red configurables** importadas desde un fichero de configuración (no en el código): `dns`, `ping`, `tcp`, `http` con desglose DNS/TCP/TLS/primer byte, y puerta de enlace automática. Guiones HTTP de varios pasos y HAR como opción avanzada.
- **Roaming en modo continuo**: eventos de cambio de AP y tiempo sin conexión, situados sobre el recorrido marcado en el plano.
