# GDB Viewer — Android (Kotlin)

App Android para **visualizar y consultar archivos GDB (File Geodatabase de Esri)**.
Como no existe forma práctica de leer un GDB directamente en el teléfono, el flujo es:

```
tu_archivo.gdb  --(convertir en tu computador)-->  archivo.gpkg  --(abrir en la app)-->  mapa + atributos
```

## Paso 1: Convertir el GDB a GeoPackage (una sola vez, en tu computador)

Necesitas GDAL instalado (`brew install gdal` en Mac, `sudo apt install gdal-bin` en Linux,
o el instalador de OSGeo4W en Windows).

```bash
ogr2ogr -f GPKG salida.gpkg entrada.gdb
```

Esto convierte **todas las capas** del GDB a un único `.gpkg`, conservando geometría y
atributos. Si tu GDB es muy grande, puedes convertir solo algunas capas:

```bash
ogr2ogr -f GPKG salida.gpkg entrada.gdb "NombreCapa1" "NombreCapa2"
```

Copia el `.gpkg` resultante a tu teléfono (por Drive, cable USB, etc.).

## Paso 2: Abrir el proyecto en Android Studio

1. Abre Android Studio (versión reciente, con SDK 34).
2. `File > Open` y selecciona la carpeta `GDBViewer`.
3. Deja que Gradle sincronice (descargará automáticamente `geopackage-android` y `osmdroid`).
4. Conecta un dispositivo o usa un emulador y presiona Run ▶.

## Qué hace la app

- **Pantalla principal**: botón para elegir un `.gpkg` desde el almacenamiento del teléfono,
  lista de capas disponibles con checkboxes.
- **Pantalla de mapa**: dibuja las capas seleccionadas (puntos, líneas, polígonos) sobre un
  mapa OpenStreetMap. Tocar una geometría muestra sus atributos.
- **Filtro (ícono de lupa)**: elige una capa, una columna y una condición SQL simple
  (ej. columna `poblacion`, condición `> 5000`) para mostrar solo las features que cumplan.

## Sistema de coordenadas (CTM12 y otros)

La app detecta automáticamente el CRS de cada capa leyendo los metadatos que el propio
`.gpkg` guarda (`gpkg_spatial_ref_sys`) — esto ya viene correcto si convertiste con `ogr2ogr`,
sin que tengas que hacer nada.

Si una capa no trae CRS reconocible, o quieres forzar uno distinto, toca el ícono de lápiz
junto a la capa en la pantalla principal:
- Elegir un sistema conocido de la lista (**CTM12 / EPSG:9377**, MAGNA-SIRGAS Bogotá, MAGNA-SIRGAS
  geográfico, WGS84).
- O cargar un archivo **.PRJ**: se busca el código EPSG explícito en el WKT, y si no está
  (común en `.prj` antiguos de Esri), se intenta reconocer por palabras clave (CTM12, MAGNA,
  Bogotá, WGS84). Si no se reconoce nada, te lo advierte para que elijas manualmente.

Los parámetros de CTM12 (EPSG:9377) están tomados de la definición oficial publicada por el
IGAC: `+proj=tmerc +lat_0=4.0 +lon_0=-73.0 +k=0.9992 +x_0=5000000 +y_0=2000000 +ellps=GRS80 +units=m`.

**Nota:** proj4j (la librería usada para reproyectar, corre 100% en Android sin GDAL) no
interpreta WKT arbitrario de cualquier `.prj` — solo GDAL/PROJ en C++ hacen eso de forma
completa. Por eso el reconocimiento de `.prj` es "mejor esfuerzo" con las reglas de arriba,
y siempre puedes seleccionar el sistema manualmente como respaldo.

## Menú del mapa (barra superior)

- **Filtrar**: igual que antes, condición SQL por columna sobre una capa.
- **Cambiar mapa base**: alterna entre OpenStreetMap (calles) y **Esri World Imagery**
  (satelital, gratuito). El crédito correspondiente aparece en la esquina inferior derecha —
  es obligatorio mostrarlo por los términos de uso de Esri. Si vas a tener muchos usuarios
  simultáneos, revisa los términos de Esri antes de publicar la app (el servicio gratuito
  tiene límites de volumen).
- **Mi ubicación**: pide permiso de ubicación la primera vez, luego centra el mapa en tu
  posición GPS y la sigue en tiempo real. Se apaga automáticamente al salir de la pantalla
  para no gastar batería.
- **Medir distancia**: actívalo y toca puntos sobre el mapa; dibuja una línea y muestra la
  distancia acumulada (en metros o kilómetros) en la parte inferior. Vuelve a tocar el botón
  para salir del modo medición.

## Simbología

En la pantalla principal, cada capa trae un color distinto asignado automáticamente (para
distinguirlas sin configurar nada). Toca el ícono de imagen junto a una capa para personalizar:
- **Color** (paleta de swatches).
- **Grosor** de línea/borde.
- **Opacidad de relleno** (solo afecta a capas de polígonos).
- **Tamaño de punto** (solo afecta a capas de puntos).

## Tabla de atributos

Desde el menú del mapa (**Tabla de atributos**), si tienes varias capas cargadas primero
eliges cuál ver. Se abre una tabla con todas las columnas y filas de esa capa (con scroll
horizontal y vertical), y un buscador arriba que filtra las filas cuyo contenido —en
cualquier columna— coincida con el texto escrito.

**Nota de rendimiento:** para capas con muchos miles de registros, construir la tabla completa
en memoria puede tardar unos segundos; si tus capas son muy grandes convendría luego agregar
paginación o virtualización (RecyclerView) en vez de un `TableLayout` completo.

## Digitalización (crear capas de puntos/polígonos)

Desde el menú del mapa, **Crear capa**: eliges nombre y tipo (puntos o polígonos). Se crea
una tabla nueva dentro del mismo `.gpkg` que tienes abierto, en WGS84 (los toques sobre el
mapa ya vienen en esas coordenadas, sin necesidad de reproyectar).

- **Puntos**: cada toque sobre el mapa pide un nombre/etiqueta y guarda el punto de inmediato;
  puedes seguir tocando para agregar más. Termina con **Cancelar digitalización** (a pesar del
  nombre, los puntos ya guardados quedan guardados — solo sales del modo).
- **Polígonos**: cada toque agrega un vértice (se ve una línea de vista previa en amarillo).
  Cuando tengas al menos 3 vértices, usa **Guardar geometría** para cerrar el anillo, ponerle
  nombre y guardarlo.

Mientras digitalizas, el menú cambia para mostrar solo las acciones relevantes (evita guardar
un filtro o activar el GPS a mitad de una digitalización).

**Limitaciones honestas de esta primera versión:**
- Solo se crea un atributo de texto ("nombre") además del id y la geometría — agregar más
  columnas personalizadas (número, fecha, etc.) quedaría como siguiente mejora.
- Las funciones de crear tablas (`createFeatureTableWithMetadata`, `TableColumnKey`, etc.) usan
  una parte de la librería NGA GeoPackage bastante menos documentada que la lectura; verifiqué
  las firmas contra la documentación oficial, pero si Android Studio marca algún import como no
  encontrado, su autocorrector (Alt+Enter) casi siempre resuelve la ruta correcta para la
  versión exacta que Gradle descargue.
- Al guardar un punto o polígono se vuelve a dibujar todo el mapa (`drawLayers`), lo que también
  quita temporalmente el overlay de GPS o de medición si estaban activos — quedaría pendiente
  conservarlos en redibujos futuros.

## Importar KML, KMZ y Shapefile

Desde la pantalla principal, además de abrir un `.gpkg`, puedes:

- **Importar KML / KMZ**: lee el archivo directamente (KML es XML puro; KMZ es un KML
  comprimido en ZIP) — no usa GDAL. Cada Placemark se convierte en una geometría, y sus
  campos `<name>`, `<description>` y `<ExtendedData>` se guardan como atributos.
- **Importar Shapefile (.zip)**: como el selector de archivos de Android solo permite
  elegir un archivo, comprime tu `.shp` + `.dbf` (+ `.prj` si quieres) en un `.zip` y
  selecciona ese zip. El parser del `.shp`/`.dbf` está escrito en Kotlin puro (formato
  binario público de Esri), también sin GDAL.

En ambos casos, si no tienes ningún `.gpkg` abierto todavía, la app crea uno nuevo
(`espacio_de_trabajo.gpkg`) para guardar ahí las capas importadas. Como KML/Shapefile
pueden mezclar tipos de geometría en un mismo archivo, se crea una capa por tipo (ej.
`miArchivo_puntos`, `miArchivo_poligonos`).

**Limitaciones honestas de estos importadores:**
- **Shapefile**: se ignoran los componentes Z/M de shapefiles 3D (se importa solo X, Y);
  en polígonos con varios anillos se asume que el primero es el exterior y el resto son
  huecos (sin analizar la orientación horaria/antihoraria como exige el estándar al pie
  de la letra); un `PolyLine` con varias partes solo importa la primera parte; no se
  soporta `MultiPoint` ni `MultiPatch`; y si el `.prj` no está en WGS84, el sistema de
  coordenadas no se reproyecta automáticamente al importar — conviene revisarlo/asignarlo
  manualmente después con el flujo de CRS ya existente.
- **KML/KMZ**: los `<MultiGeometry>` se "aplanan" en features separados (se pierde el
  agrupamiento original), y no se importan estilos (colores/íconos) de KML — la
  simbología se asigna luego dentro de la app.
- **General**: si asignas manualmente un CRS a una capa (con el ícono de lápiz) y luego
  importas otro archivo, la lista de capas se recarga y esa asignación manual se
  pierde (vuelve a detectarse automáticamente desde los metadatos del `.gpkg`). Quedaría
  como mejora futura persistir los overrides manuales por separado.
- La creación del workspace nuevo (`ensureWorkspaceGpkg`) asume que la librería guarda
  las bases de datos "creadas" (a diferencia de las "importadas") en el directorio
  estándar de bases de datos de Android (`getDatabasePath`) — es el punto de esta fase
  con menos verificación directa; si falla, revísalo en Android Studio.

## [EXPERIMENTAL, NO FUNCIONAL] Conversión GDB → GPKG en el dispositivo

En `convert/GdalWebViewBridge.kt` y `assets/gdal/index.html` hay un **prototipo de
arquitectura**, no una función lista para usar. La idea: usar el mismo motor que
QuickMapTools corre en el navegador — **gdal3.js**, GDAL real compilado a WebAssembly
(proyecto open source: https://github.com/bugra9/gdal3.js) — pero dentro de un WebView
oculto en la app, para convertir GDB→GPKG **100% en el teléfono, sin subir nada a ningún
servidor y sin costo**.

**No lo pude verificar ni probar en este entorno** (no hay forma de descargar los ~30 MB
de binarios de gdal3.js aquí, ni de correr un WebView). Falta completar, en este orden:

1. Descargar el paquete npm `gdal3.js` y copiar `gdal3.js`, `gdal3WebAssembly.js`,
   `gdal3WebAssembly.wasm` y `gdal3WebAssembly.data` (de su carpeta `dist/package/`) a
   `app/src/main/assets/gdal/`. Esto agrega bastante peso al APK.
2. Agregar una librería de descompresión ZIP en JavaScript (ej.
   [fflate](https://github.com/101arrowz/fflate)) al HTML: un `.gdb` es una **carpeta**
   con varios archivos binarios, no un solo archivo, así que hay que descomprimirlo en
   memoria dentro del WebView y pasarle cada archivo a `Gdal.open()` — este paso está
   marcado con `TODO` explícito en `index.html` y **no está escrito todavía**.
3. Probarlo con un `.gdb` real pequeño primero. Para archivos grandes, el puente actual
   (que manda todo el `.zip` codificado en base64 como un único string a
   `evaluateJavascript`) puede toparse con límites de tamaño de WebView en algunos
   dispositivos — si eso pasa, habría que trocear el envío en varias llamadas más
   pequeñas en vez de mandarlo todo de una vez.
4. Verificar la licencia de gdal3.js/GDAL antes de distribuir la app (GDAL usa licencia
   MIT desde la versión 2, generalmente permisiva para embeber, pero conviene
   confirmarlo en el repositorio antes de publicar).

## Limitaciones de este punto de partida (a mejorar)

- Geometrías **multi-parte** (MULTIPOINT, MULTIPOLYGON, MULTILINESTRING) no se dibujan aún —
  está marcado con un comentario `TODO` en `MapActivity.kt`, es sencillo agregarlo iterando
  `geometry.geometries`.
- El filtro construye una condición SQL simple concatenando texto; para producción conviene
  usar parámetros preparados para evitar errores con comillas o inyección.
- No se ha probado compilando (este entorno no tiene Android SDK), así que es posible que al
  sincronizar Gradle debas ajustar alguna versión de dependencia si `mil.nga.geopackage:geopackage-android:6.7.2`
  no es la última disponible.
- No hay soporte para archivos GDB grandes con miles de features por capa optimizado (paginación) —
  para eso conviene añadir consultas con límite/bounding box en vez de `queryForAll()`.

## Estructura del proyecto

```
GDBViewer/
├── app/
│   ├── build.gradle.kts          # dependencias (geopackage-android, osmdroid)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/gdbviewer/
│       │   ├── MainActivity.kt   # selector de archivo + lista de capas
│       │   ├── MapActivity.kt    # mapa, dibujo de geometrías, filtro
│       │   └── LayerAdapter.kt   # RecyclerView de capas
│       └── res/layout/           # XML de pantallas
├── build.gradle.kts
└── settings.gradle.kts
```
