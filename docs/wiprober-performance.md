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
  "measurements": [ { …fila… }, … ],
  "networkTests": [ { …fila… }, … ],
  "scriptSteps":  [ { …fila… }, … ],
  "roamingSessions": [ { …fila… }, … ],
  "roamingEvents":   [ { …fila… }, … ]
}
```

| Campo | Tipo | Descripción |
|---|---|---|
| `format` | string | Siempre `"wiprober-performance"` |
| `version` | int | `1`. Solo cambiará si se rompe la compatibilidad; los campos nuevos se añaden sin subirla |
| `generatedAt` | string ISO-8601 UTC | Momento de la exportación |
| `esxFile` | string | Nombre del `.esx` que acompaña al JSON en el ZIP |
| `measurements` | array | Una fila por **punto Stop-and-Go × destino de rendimiento** (iPerf3 / Internet) |
| `networkTests` | array | Una fila por **punto × prueba de red importada** (sección 3b) |
| `scriptSteps` | array | Una fila por **punto × paso de guion HTTP importado** (sección 3c) |
| `roamingSessions` | array | Una fila por **recorrido continuo con sonda de roaming** (sección 3d) |
| `roamingEvents` | array | Una fila por **roaming o corte** detectado en esos recorridos (sección 3d) |

Todos los arrays aparecen siempre (vacíos si no hay datos).

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

## 3b. Pruebas de red (`networkTests`)

Configuradas en el fichero importado (sección `tests`, ver [ejemplo](wiprober-config.example.json)); se ejecutan en cada punto tras los tests de rendimiento. Cada fila lleva además los campos de posición de la sección anterior (`surveyId`, `floorPlanId`, `floorName`, `x`, `y`, `measuredAt`).

| Campo | Tipo | Descripción |
|---|---|---|
| `name` | string | Nombre de la prueba en la configuración |
| `type` | string | `dns`, `ping`, `tcp` o `http` |
| `target` | string \| null | Host consultado (`dns`/`ping`), `host:puerto` (`tcp`) o `host/ruta` sin query (`http`) |
| `method` | string \| null | `udp` (consulta directa al DNS de la WiFi) o `system` (resolver de Android, puede usar caché); `icmp` (ping del sistema) o `tcp` (ping por TCP si ICMP no está disponible); `tcp`; `http`/`https` |
| `ok` | bool | La prueba cumplió: respuesta DNS, sin pérdidas, puerto abierto, estado y texto esperados |
| `ssid`, `bssid`, `rssi` | | AP conectado al empezar la prueba |
| `dnsServer` | string \| null | Servidor DNS consultado (`dns`) |
| `resolvedAddress` | string \| null | Primera IPv4 obtenida (`dns`) |
| `dnsMs` | number \| null | Tiempo de la consulta DNS (`dns`, `http`) |
| `connectMs` | number \| null | Handshake TCP (`http`; en `tcp`, el mínimo) |
| `tlsMs` | number \| null | Handshake TLS (`https`) |
| `ttfbMs` | number \| null | Tiempo hasta el primer byte de la respuesta (`http`) |
| `downloadMs` | number \| null | Descarga del cuerpo (`http`) |
| `totalMs` | number \| null | Total de la petición (`http`) |
| `status` | int \| null | Código HTTP |
| `bytes` | int \| null | Tamaño del cuerpo HTTP |
| `sent`, `received` | int \| null | Sondas enviadas / respondidas (`ping`, `tcp`) |
| `lossPct` | number \| null | Pérdida 0–100 % |
| `rttMinMs`, `rttAvgMs`, `rttMaxMs` | number \| null | Ida y vuelta mín/media/máx |
| `jitterMs` | number \| null | Diferencia media entre respuestas consecutivas |
| `error` | string \| null | Motivo del fallo: `no reply`, `2 of 10 lost`, `NXDOMAIN`, `SERVFAIL`, `status 500, expected 200`, `no default gateway`… |

`${gateway}` y `${dns}` en la configuración se sustituyen por la puerta de enlace y el primer DNS que dio la WiFi.

## 3c. Pasos de guiones HTTP (`scriptSteps`)

Guiones de la sección `scripts` (o importados de un `.har`): peticiones encadenadas con variables, extracción de valores (p. ej. un token) y cookies entre pasos. Solo GET/HEAD salvo `"allowWrite": true` en el paso. Cada paso abre conexión nueva, así que DNS/TCP/TLS se miden siempre. Cada fila lleva los campos de posición.

| Campo | Tipo | Descripción |
|---|---|---|
| `script`, `stepIndex`, `step` | | Guion, número de paso (desde 1) y nombre |
| `method`, `host`, `path` | | Petición (sin query string) |
| `status` | int \| null | Código HTTP |
| `ok` | bool | Estado, texto esperado y extracciones correctos |
| `ssid`, `bssidStart`, `bssidEnd`, `rssi` | | AP conectado al empezar/terminar el guion |
| `dnsMs`, `connectMs`, `tlsMs`, `ttfbMs`, `downloadMs`, `totalMs` | number \| null | Desglose de tiempos del paso, ms |
| `bytes` | int \| null | Tamaño del cuerpo |
| `error` | string \| null | P. ej. `status 401`, `response does not contain "A-01"`, `cannot extract token from $.data.token`, `unknown variable token` |

## 3d. Roaming en modo continuo (`roamingSessions`, `roamingEvents`)

Si la configuración importada activa `roaming`, durante cada recorrido continuo la app, cada `intervalMs` (200 ms por defecto), lee el AP conectado y hace una conexión TCP al destino (`host:port`, p. ej. `${gateway}:443`) por la WiFi; una conexión rechazada también prueba conectividad. Los eventos se sitúan **sobre el recorrido marcado en el plano** interpolando por tiempo entre los toques, igual que los escaneos del recorrido en el `.esx`. El `surveyId` es el del survey del recorrido en el `.esx` (el id de la sesión, estable entre exportaciones).

`roamingSessions`:

| Campo | Tipo | Descripción |
|---|---|---|
| `surveyId`, `floorPlanId`, `floorName` | | Recorrido y planta |
| `startedAt`, `endedAt` | string ISO-8601 UTC | Inicio y fin del recorrido |
| `target` | string | Destino de la sonda (`gateway:443`, `host:puerto`) |
| `intervalMs` | int | Intervalo entre sondas |
| `probes`, `lost` | int | Sondas enviadas / fallidas |
| `lossPct` | number \| null | Pérdida 0–100 % |
| `avgRttMs` | number \| null | Tiempo medio de conexión de las sondas correctas |
| `roams`, `outages` | int | Eventos de cada tipo |

`roamingEvents`:

| Campo | Tipo | Descripción |
|---|---|---|
| `surveyId`, `floorPlanId`, `floorName` | | Recorrido y planta |
| `x`, `y` | number \| null | Posición en el recorrido (píxeles del plano) |
| `at` | string ISO-8601 UTC | Momento del evento |
| `type` | string | `roam` (cambió el BSSID) u `outage` (≥3 sondas seguidas fallidas sin cambiar de AP) |
| `fromBssid`, `toBssid` | string \| null | AP anterior y nuevo (iguales en un `outage`) |
| `fromRssi`, `toRssi` | int \| null | Señal antes y después, dBm |
| `outageMs` | int \| null | Tiempo sin conexión más allá de un intervalo (0 = roaming sin corte); `null` si no se recuperó antes de acabar el recorrido |
| `lostProbes` | int | Sondas fallidas en el evento |
| `duringScan` | bool | Hubo un escaneo WiFi durante el corte: el móvil estaba fuera de canal y parte del corte puede deberse al escaneo |

## 4. Cómo cruzarlo con el `.esx`

- **Dentro del mismo ZIP**: por `surveyId` → `survey-<surveyId>.json`. El escaneo WiFi completo del punto (todos los APs) está en ese survey (`wifiTracks[0]`) y en su binario `track-<binaryFileId>.bin`.
- **Entre exportaciones distintas** (histórico, comparativas): por `floorPlanId` + `x` + `y` (tolerancia sugerida 0,5 px). El `surveyId` de los puntos se **regenera en cada exportación**; `floorPlanId` y las coordenadas no cambian.
- **A metros**: `metros = píxeles × metersPerUnit` (de `floorPlans.json`). Tamaño del plano: `width`/`height`; imagen: `image-<imageId>`.
- Puntos **importados de otro `.esx`** no tienen filas. Los recorridos del **modo continuo** solo aparecen en `roamingSessions`/`roamingEvents`.

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
| `v3.0.0-throughput.4` | Añadidos los arrays `networkTests` y `scriptSteps` (configuración importada) y `roamingSessions`/`roamingEvents` (roaming en modo continuo) |
