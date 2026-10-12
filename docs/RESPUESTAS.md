# Taller Master-Worker: Multiplicación Distribuida de Matrices con ZeroC Ice

**Universidad Icesi** · Computación en Internet I (09810 - TIC) · Período 2026-2

**Estudiante:** Tomás Jaramillo Caicedo — **Código:** A00404848

Este documento reúne:

- la evidencia de funcionamiento del sistema;
- las métricas medidas en el benchmark;
- las respuestas al cuestionario de la sección 9 de la guía ([PDF](Taller_Multiplicacion_Matrices_Master_Worker.pdf)).

---

## 1. ¿Cómo funciona el sistema?

```
CLIENTE --multiply(A, B)--> MASTER --computeBlock(startRow, filasDeA, B)--> WORKER 1..p
   ^                          |  ^                                              |
   |                          |  +------------ BlockResult (filas de C) --------+
   +------- matriz C ---------+
```

- **Contrato Slice** (`common/src/main/slice/MatrixCompute.ice`): define los tipos que viajan por la red y las
  interfaces `MasterService` y `WorkerService`. El tipo principal es `MatrixData` = `sequence<sequence<double>>`, que
  en Java es `double[][]`. El plugin de Gradle ejecuta `slice2java` y genera los *proxies* y las clases base en Java.
- **Worker:**
  - al arrancar, publica `WorkerService` en su puerto (10001, 10002, …) y se registra en el Master con
    `registerWorker`;
  - recibe un bloque de filas de A y la matriz B completa;
  - calcula esas filas de C y devuelve un `BlockResult` con su tiempo de cómputo local.
- **Master:** escucha en el puerto 10000. En cada `multiply`:
  1. valida las dimensiones;
  2. comprueba con `ice_ping` qué workers siguen vivos;
  3. **divide A en bloques contiguos de filas** (`ceil(N/p)` filas por worker);
  4. lanza las llamadas a los workers **en paralelo** con `CompletableFuture.supplyAsync`;
  5. reensambla C usando el `startRow` de cada bloque.
- **Cliente:**
  1. genera A y B pseudoaleatorias;
  2. calcula C localmente en un solo hilo (T_sec);
  3. pide la multiplicación al Master (T_dist);
  4. verifica celda por celda que ambos resultados coincidan;
  5. calcula Speedup y Eficiencia.

Es una arquitectura **MIMD + NORMA (shared-nothing)**: cada proceso tiene su propia memoria y todo dato compartido
(por ejemplo, la matriz B) se copia por la red mediante RPC.

---

## 2. Entorno y metodología

| Elemento | Valor |
|---|---|
| Máquina | Windows 11 Pro · AMD Ryzen 7 7700X (8 núcleos / 16 hilos, caché L3 de 32 MB compartida) · 31 GB RAM |
| Software | Temurin JDK 21.0.12 · Gradle 8.6 · ZeroC Ice 3.7.10 (runtime) / `slice2java` 3.7.11 |
| Despliegue | Master, workers y cliente en **la misma máquina** (red = TCP *loopback* `localhost`) |

**Cómo se midió:**

- **Tiempos** con `System.nanoTime()` (resolución de microsegundos).
- **Calentamiento sin medir.** Antes de medir, el cliente repite la multiplicación local y la distribuida durante al
  menos 0,5 s. La JVM primero *interpreta* el código y solo lo compila (JIT) después de muchas ejecuciones, así que
  sin esto se compararía código interpretado contra código compilado.
  - Con N = 20 el calentamiento alcanza para 77–145 repeticiones.
  - Con N ≥ 1000 basta una sola.
- **Barrido final:**
  - N = 20, 100, 200, 300, 500, 600, 1000 y 1500;
  - p = 1, 2 y 4 workers, agregados en caliente al mismo Master;
  - **5 repeticiones** por combinación, cada una con un proceso cliente nuevo.
  - En total, **120 ejecuciones**. Se reporta la **mediana**.
- **Las 120 ejecuciones pasaron la validación numérica** (|C_dist − C_sec| < 10⁻⁵ en todas las celdas).
- **Versión del código.** Todos los datos de este documento se obtuvieron con la **versión final del código
  entregado**. Durante el desarrollo se hicieron dos barridos exploratorios con una versión anterior del cliente,
  que tenía una sola ronda de calentamiento. Mostraron las mismas tendencias, pero se repitieron para que toda la
  evidencia provenga del código final.
- **Variabilidad.** El computador no estaba dedicado solo a las pruebas, así que los tiempos varían bastante. Por eso
  la tabla 5.1 muestra también el rango mínimo–máximo de S.
  - **Entre ejecuciones:** la S de una ejecución individual se alejó de la mediana hasta −56 %/+64 % con N = 300 y
    hasta −24 %/+40 % con N ≥ 500.
  - **Entre barridos:** las medianas también cambian. Por ejemplo, con N = 600 y p = 4 fueron 2.42 y 2.76 en los
    barridos exploratorios y 1.89 en el final.
  - **Lo que se repite en todos los barridos que incluyeron esos tamaños:** S < 1 con N ≤ 200, S > 1 con N ≥ 500 y
    p ≥ 2, y la eficiencia baja al subir p.
  - **Lo que solo vale para el barrido final:** los detalles más finos. Por ejemplo, el punto de equilibrio exacto
    de la pregunta 1, o cuánto mejora S de 2 a 4 workers y si (1 − f) crece con p en la pregunta 2.

**Cómo ejecutar la demo** (4 terminales PowerShell en la raíz del repositorio, igual que la sección 8 de la guía; si
las tildes se ven como `├│`, ejecute antes `chcp 65001` en cada terminal):

```powershell
.\gradlew :server:runMaster                                              # Terminal 1
.\gradlew :worker:runWorker --args="10001 Worker-1 localhost 10000"     # Terminal 2
.\gradlew :worker:runWorker --args="10002 Worker-2 localhost 10000"     # Terminal 3
.\gradlew :client:runClient --args="localhost 10000 600"                # Terminal 4
```

---

## 3. Cambios realizados al código base (y por qué)

El repositorio ya traía la implementación de la guía. Solo se corrigieron defectos que impedían cumplir la checklist o
que distorsionaban las mediciones:

| Archivo | Cambio | Motivo |
|---|---|---|
| `MasterMain`, `WorkerMain`, `ClientMain` | `Ice.MessageSizeMax = 102400` (100 MB) al crear el `Communicator` | **Bloqueante.** Ice rechaza por defecto mensajes de más de **1 MB**. Ver detalle abajo. |
| `ClientMain` | Semillas distintas para A (42) y B (43) | Antes A y B eran **la misma matriz**, porque ambas usaban `new Random(42)`. |
| `ClientMain` | `System.nanoTime()` en lugar de `currentTimeMillis()` | Con matrices pequeñas el cálculo local tarda menos de 1 ms, así que se medía `T_sec = 0 ms` y `S = 0.00`. |
| `ClientMain` | Calentamiento sin medir de al menos 0,5 s | Comparar código ya compilado por el JIT en ambos lados, también con matrices pequeñas. |
| `ClientMain` | Consultar `p` justo después del `multiply` medido | Cada `multiply` hace que el Master descarte los workers caídos (`ice_ping`). Así `p` ya no cuenta un worker que se cayó antes de la medición; en las 120 ejecuciones coincidió con el `usando N Worker(s)` del log del Master. |
| `ClientMain` | Imprimir `MatrixException.reason` | Antes un error del Master se mostraba como `Excepción en Cliente: null`. |
| `MasterServiceI` | Al registrar un worker, quitar otra entrada con el mismo host:puerto | Un worker reiniciado con otro ID se contaba dos veces (p falso). |
| `MasterServiceI` | Los logs muestran el ID real del worker | Antes decía `W1`, que no correspondía a `Worker-1`. |
| `build.gradle` de server, worker y client | `jvmArgs '-Dstdout.encoding=UTF-8', ...` en `runMaster`, `runWorker` y `runClient` | Al ejecutar con `.\gradlew`, las tildes llegaban a la terminal como `?` (Gradle lee la salida del programa como UTF-8). En consolas con la página de códigos 850 hay que ejecutar además `chcp 65001`. |

No se cambiaron:

- el contrato Slice;
- el despacho con `CompletableFuture.supplyAsync`;
- el particionamiento por filas.

Tampoco se implementó tolerancia a fallos: la pregunta 4 se responde conceptualmente.

**Detalle del límite de 1 MB.**

- Ice codifica cada `double` en 8 bytes, así que una matriz N×N ocupa ≈ 8·N² bytes.
- `multiply(A, B)` envía 16·N² bytes de datos, más los prefijos de tamaño de cada fila y los encabezados del mensaje
  Ice. Eso supera el límite por defecto de 1024 KB (1 048 576 bytes) **a partir de N = 256**: con N = 600 son
  ≈ 5.8 MB.
- El que *recibe* un mensaje demasiado grande lo rechaza y cierra la conexión. Por eso el límite se sube en los tres
  procesos:
  - el Master recibe A y B (del cliente) y los bloques de C (de cada worker; con p = 1 y N = 600 son ≈ 2.9 MB);
  - el Worker recibe su bloque de A y B completa;
  - el Cliente recibe C.
- Antes de la corrección, la fase 4×4 funcionaba, pero el benchmark de 600×600 fallaba así:

```
[ERROR] Excepción en Cliente: java.io.IOException: Connection reset by peer
com.zeroc.Ice.SocketException
    error = 0
	at com.zeroc.IceInternal.OutgoingAsync.waitForResponseOrUserEx(OutgoingAsync.java:140)
	at MatrixCompute.MasterServicePrx.multiply(MasterServicePrx.java:31)
	...
```

El Master no imprimía nada: el mensaje se descarta antes de llegar a `multiply`. El código de la guía (PDF) tiene
el mismo defecto, porque crea el `Communicator` con la configuración por defecto.

---

## 4. Checklist de autoverificación (sección 10)

| Hito | Estado | Evidencia |
|---|---|---|
| 1. `MatrixCompute.ice` compila con `./gradlew :common:build` | ✅ | `.\gradlew build` → `:common:compileSlice` + `BUILD SUCCESSFUL` |
| 2. Master en 10000 registra dinámicamente varios workers | ✅ | `[MASTER] [+] Worker registrado exitosamente: Worker-2 (tcp -h localhost -p 10002) \| Total activos: 2`. En el barrido se registraron hasta 4 workers en caliente. |
| 3. Cada worker calcula su bloque y retorna su tiempo local | ✅ | `[Worker-1] Bloque completado exitosamente en 98 ms.` y en el Master: `[MASTER <- Worker-2] Recibido bloque fila 0 completado en 98 ms` |
| 4. C se ensambla sin corromper el orden de filas | ✅ | Ver la verificación a mano debajo de esta tabla. Además, la validación celda por celda (que también detectaría filas desordenadas) fue exitosa en las 120 ejecuciones. |
| 5. Resultado distribuido = monohilo (Δ < 10⁻⁵) | ✅ | `Validación Numérica: EXITOSA (Delta < 1e-5)` en 120 de 120 ejecuciones |
| 6. Aceleración real (S > 1) con N ≥ 500 | ✅ | Protocolo de la guía (600×600, 2 workers): **S = 1.67×, E = 83.3 %**. Repetido más tarde con el equipo más cargado: S = 1.14–1.26×. Con 4 workers y N = 1000: **S = 2.09×** (mediana del barrido). |

**Verificación a mano del Hito 4.** En el 4×4, Worker-2 calculó las filas 0–1 (`startRow=0`) y Worker-1 las filas 2–3
(`startRow=2`). Se revisó una celda de cada bloque:

- C[0][0] = 1·2 + 2·1 + 3·0 + 4·3 = **16** (primer bloque);
- C[3][0] = 4·2 + 1·1 + 2·0 + 1·3 = **12** (segundo bloque).

Ambas coinciden con la salida del cliente.

Salida del protocolo de laboratorio (sección 8, comandos exactos de la guía). Esta corrida usó la versión final del
programa; después solo se cambiaron dos comentarios y la codificación de la salida de las tareas de Gradle:

```
  Dimensión de Matrices:      600 x 600
  Operaciones Aritméticas:    ~4.32e+08 ops
  Workers Activos (p):        2
  Tiempo Secuencial (T_sec):  185.270 ms
  Tiempo Distribuido (T_dist):111.142 ms
  Aceleración (Speedup S):    1.67x
  Eficiencia Paralela (E):    83.3%
  Validación Numérica:        EXITOSA (Delta < 1e-5)
```

El protocolo se repitió al final de la sesión: 4 corridas del cliente con los mismos comandos y el mismo código.
Todas validaron y dieron S > 1, pero menor: **1.20×, 1.25×, 1.26× y 1.14×**.

- Esta vez también el cálculo *local* fue más lento: T_sec = 195–223 ms frente a 185 ms.
- El equipo tenía ≈ 30 % de CPU ocupada en reposo por otras aplicaciones (navegador, editor con su servidor de
  Java, *daemons* de Gradle).

Es un buen ejemplo de que S depende de la carga del equipo. Para la demo conviene cerrar aplicaciones pesadas y
ejecutar el cliente dos veces.

| Métrica | Fórmula | Significado |
|---|---|---|
| Aceleración (Speedup) | $S = \dfrac{T_{sec}}{T_{dist}}$ | Cuántas veces más rápido es el clúster que un solo hilo local |
| Eficiencia paralela | $E = \dfrac{S}{p} \times 100\%$ | Qué tan bien se aprovecha cada worker (100 % = aceleración lineal ideal) |
| Validación numérica | $\lvert C_{dist}[i][j] - C_{sec}[i][j] \rvert < 10^{-5}$ | Que la versión distribuida calcule exactamente lo mismo |

### 5.1 Resultados (mediana de 5 ejecuciones por fila)

En la tabla, S = mediana(T_sec) / mediana(T_dist). Entre corchetes va el rango de S observado en las 5 ejecuciones.

| N | p | T_sec (ms) | T_dist (ms) | S [mín–máx] | E | Validación |
|---:|---:|---:|---:|---|---:|---|
| 20 | 1 | 0.005 | 3.454 | 0.0014 [0.0012–0.0022] | 0.1 % | ✅ 5/5 |
| 20 | 2 | 0.007 | 4.092 | 0.0017 [0.0015–0.0019] | 0.1 % | ✅ 5/5 |
| 20 | 4 | 0.005 | 6.403 | 0.0008 [0.0006–0.0014] | 0.02 % | ✅ 5/5 |
| 100 | 1 | 0.466 | 4.084 | 0.11 [0.11–0.12] | 11.4 % | ✅ 5/5 |
| 100 | 2 | 0.471 | 4.426 | 0.11 [0.10–0.12] | 5.3 % | ✅ 5/5 |
| 100 | 4 | 0.480 | 5.976 | 0.08 [0.08–0.08] | 2.0 % | ✅ 5/5 |
| 200 | 1 | 4.686 | 8.331 | 0.56 [0.53–0.60] | 56.2 % | ✅ 5/5 |
| 200 | 2 | 4.632 | 6.861 | 0.68 [0.47–0.70] | 33.8 % | ✅ 5/5 |
| 200 | 4 | 4.655 | 7.424 | 0.63 [0.60–0.71] | 15.7 % | ✅ 5/5 |
| 300 | 1 | 20.474 | 40.422 | 0.51 [0.40–0.63] | 50.7 % | ✅ 5/5 |
| 300 | 2 | 21.941 | 30.319 | 0.72 [0.42–1.13] | 36.2 % | ✅ 5/5 |
| 300 | 4 | 21.898 | 33.402 | 0.66 [0.29–1.07] | 16.4 % | ✅ 5/5 |
| 500 | 1 | 106.8 | 134.7 | 0.79 [0.75–0.83] | 79.3 % | ✅ 5/5 |
| 500 | 2 | 108.2 | 81.1 | 1.33 [1.01–1.55] | 66.7 % | ✅ 5/5 |
| 500 | 4 | 107.4 | 71.1 | 1.51 [1.29–2.11] | 37.7 % | ✅ 5/5 |
| 600 | 1 | 184.3 | 221.6 | 0.83 [0.78–0.91] | 83.2 % | ✅ 5/5 |
| 600 | 2 | 185.2 | 112.5 | 1.65 [1.54–1.66] | 82.3 % | ✅ 5/5 |
| 600 | 4 | 182.6 | 96.5 | 1.89 [1.83–2.62] | 47.3 % | ✅ 5/5 |
| 1000 | 1 | 867.7 | 921.6 | 0.94 [0.92–1.00] | 94.1 % | ✅ 5/5 |
| 1000 | 2 | 866.3 | 607.4 | 1.43 [1.18–1.70] | 71.3 % | ✅ 5/5 |
| 1000 | 4 | 853.8 | 408.0 | 2.09 [1.96–2.33] | 52.3 % | ✅ 5/5 |
| 1500 | 1 | 4484.7 | 3263.3 | 1.37* [1.33–1.57] | 137.4 %* | ✅ 5/5 |
| 1500 | 2 | 4611.6 | 2789.2 | 1.65 [1.56–1.81] | 82.7 % | ✅ 5/5 |
| 1500 | 4 | 4325.8 | 1836.2 | 2.36 [2.09–2.69] | 58.9 % | ✅ 5/5 |

**N = 300 fue la fila más ruidosa.** Ahí T_dist varió entre 18 y 73 ms. Con tiempos tan cortos, cualquier pausa
(por ejemplo, del recolector de basura de alguna de las JVM) pesa mucho.

\* **Anomalía con N = 1500 y un solo worker.** Que S > 1 con p = 1 **no es ganancia real**: el worker ejecuta
exactamente el mismo bucle que el cálculo local.

- **Es un efecto sistemático, no azar.** En las 5 ejecuciones el cálculo local tardó 4.34–5.57 s y el worker solo
  3.15–3.47 s.
- **La causa probable es el sistema de memoria.** Con N = 1500, B ocupa 18 MB: más de la mitad de la L3 de 32 MB,
  que además comparte con A y C. Y se recorre por columnas, así que cada acceso `B[k][j]` salta a una fila distinta.
  En ese régimen, el tiempo depende de en qué direcciones de memoria quedan las filas.
- **Una prueba aparte lo confirma.** En una sola JVM, después del calentamiento:
  - multiplicar las matrices originales tardó siempre 4.1–4.3 s;
  - multiplicar copias idénticas (mismos valores, otra ubicación en memoria) tardó 3.2 s, 4.1 s y 5.1 s;
  - con N = 1000, el mismo experimento da siempre ≈ 850 ms.

Por eso el análisis de la pregunta 2 se apoya sobre todo en N = 600 y N = 1000.

### 5.2 ¿En qué se va el tiempo distribuido? (N ≥ 500, mediana de 5 ejecuciones)

| N | p | Worker más lento (ms) | Suma de los workers (ms) | Total en el Master (ms) | T_dist (ms) | Overhead Master | Cliente↔Master |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 500 | 1 | 110 | 110 | 131 | 134.7 | 19 | 4 |
| 500 | 2 | 56 | 111 | 72 | 81.1 | 16 | 5 |
| 500 | 4 | 30 | 116 | 58 | 71.1 | 28 | 5 |
| 600 | 1 | 191 | 191 | 200 | 221.6 | 10 | 7 |
| 600 | 2 | 97 | 193 | 105 | 112.5 | 8 | 8 |
| 600 | 4 | 50 | 197 | 65 | 96.5 | 15 | 19 |
| 1000 | 1 | 883 | 883 | 894 | 921.6 | 14 | 29 |
| 1000 | 2 | 581 | 1063 | 592 | 607.4 | 13 | 33 |
| 1000 | 4 | 362 | 1300 | 377 | 408.0 | 15 | 31 |
| 1500 | 1 | 3201 | 3201 | 3232 | 3263.3 | 31 | 33 |
| 1500 | 2 | 2735 | 5072 | 2758 | 2789.2 | 21 | 32 |
| 1500 | 4 | 1761 | 6893 | 1793 | 1836.2 | 23 | 32 |

De dónde sale cada columna:

- Los tiempos de los workers y el total vienen del log del Master; T_dist se mide en el cliente.
- *Overhead Master* = mediana, por ejecución, de (total en el Master − worker más lento). Incluye los `ice_ping`,
  enviar A y B a los workers, recibir sus bloques y ensamblar C.
- *Cliente↔Master* = mediana, por ejecución, de (T_dist − total en el Master). Incluye enviar A y B al Master y
  recibir C.

---

## 6. Cuestionario de análisis conceptual (sección 9)

### Pregunta 1 — El umbral de amortización de red

> Con matrices de 20×20, ¿por qué T_dist es mucho mayor que T_sec (S < 1)?

**Lo medido:** con N = 20, el cálculo local tarda **≈ 0.005 ms** y el distribuido **≈ 3.5–6.4 ms**. El clúster es
**entre ≈ 600 y ≈ 1300 veces más lento** (S ≈ 0.001–0.002), y pierde con cualquier número de workers.

**Explicación.** Una multiplicación 20×20 son solo 20³ = 8 000 multiplicaciones-suma. Compiladas por el JIT,
cuestan ≈ 0.6 ns cada una, unos 5 µs en total. En cambio, una sola llamada distribuida paga una serie de costos
fijos:

1. **Serialización Slice (marshalling).** Cada `double[][]` se recorre y se copia a un buffer de bytes.
   - Cada `double` ocupa 8 bytes, y cada secuencia lleva antes su tamaño.
   - Cada mensaje lleva el encabezado del protocolo Ice (14 bytes).
   - Las peticiones agregan el id de petición, la identidad del objeto, la faceta, el nombre de la operación, el
     modo, el contexto y la encapsulación. Las respuestas solo agregan el id de petición, el estado y la
     encapsulación.
   - Al otro lado se hace lo inverso: se leen los bytes y se crean arreglos Java nuevos.
   - Esto ocurre en cada salto: cliente → Master, Master → cada worker, worker → Master y Master → cliente.
2. **Buffers de red y copias.** Los datos van del heap de la JVM al buffer de Ice y luego a los buffers del socket en el
   kernel (llamadas al sistema). En recepción se recorre el camino inverso, una vez por cada mensaje.
3. **Latencia TCP y de *dispatch*.** Cada RPC es al menos un viaje de ida y vuelta, y una multiplicación encadena
   varios:
   - cliente → Master;
   - un `ice_ping` por worker para verificar que está vivo;
   - las llamadas `computeBlock`.
   En cada salto además hay cambios de hilo: hilo del *thread pool* de Ice → `ForkJoinPool` del
   `CompletableFuture` → hilo cliente de Ice → de vuelta. Aun en *loopback*, esa cadena cuesta milisegundos.
4. **Más workers encarecen el costo fijo:**
   - con N = 20, T_dist sube de 3.5 ms (p = 1) a 4.1 ms (p = 2) y 6.4 ms (p = 4);
   - con N = 100, de 4.1 a 4.4 y 6.0 ms.
   Cada worker agrega mensajes (ping, petición, respuesta), y no hay trabajo suficiente que repartir.

**El umbral.** El cómputo crece como **O(N³)**, pero los datos transmitidos crecen solo como **O(N²)**, más un
costo fijo por mensaje. La razón cómputo/comunicación crece proporcionalmente a N, así que existe un tamaño a partir
del cual el trabajo aritmético amortiza el costo de la red.

| N | 20 | 100 | 200 | 300 | 500 | 1000 |
|---|---:|---:|---:|---:|---:|---:|
| S con p = 2 | 0.002 | 0.11 | 0.68 | 0.72 | **1.33** | 1.43 |
| S con p = 4 | 0.001 | 0.08 | 0.63 | 0.66 | **1.51** | 2.09 |

En el barrido final, el punto de equilibrio (S ≈ 1) está **entre N = 300 y N = 500**. Con N = 300 algunas
ejecuciones ya llegaron a S ≈ 1.1, y con N = 500 todas superaron 1. En el primer barrido exploratorio la mediana ya
llegaba a S ≈ 1.0–1.2 con N = 300. Por eso, en esta máquina el umbral está alrededor de **N ≈ 300–500**. En una red
real, con más latencia y menos ancho de banda que *loopback*, se desplazaría hacia matrices más grandes.

### Pregunta 2 — Ley de Amdahl y eficiencia

> Al pasar de 1 a 2 y de 2 a 4 workers, ¿S se duplica? ¿Qué partes corresponden a la fracción secuencial (1 − f)?

**No se duplica de forma sostenida** (barrido final, medianas de 5 ejecuciones):

| N | S (p = 1) | S (p = 2) | S (p = 4) | Mejora 1 → 2 | Mejora 2 → 4 | E (p = 4) |
|---:|---:|---:|---:|---:|---:|---:|
| 600 | 0.83 | 1.65 | 1.89 | ×1.98 | ×1.15 | 47.3 % |
| 1000 | 0.94 | 1.43 | 2.09 | ×1.51 | ×1.47 | 52.3 % |
| 1500 | 1.37* | 1.65 | 2.36 | ×1.20 | ×1.42 | 58.9 % |

\* Con N = 1500 la línea base T_sec está inflada (ver 5.1).

- **De 1 a 2 workers** la mejora es casi ×2 con N = 600, pero no con N = 1000 ni N = 1500. Con N = 1000 las
  ejecuciones con p = 2 variaron mucho: S entre 1.18 y 1.70.
- **De 2 a 4 workers** en el barrido final no se acerca a ×2: va de ×1.15 a ×1.47. En los barridos exploratorios,
  con N = 600, llegó a ×1.69–1.79, que sigue estando por debajo de ×2.
- **La eficiencia** cae del 82 % con p = 2 (N = 600) a ≈ 47–59 % con p = 4.

**Fracción secuencial medida.** La ley de Amdahl dice que $S(p) = \dfrac{1}{(1-f) + f/p}$. Despejando
$(1-f) = \dfrac{1/S - 1/p}{1 - 1/p}$ con los datos de p = 4 (métrica de Karp-Flatt) se obtiene:

- con N = 600: (1 − f) ≈ **0.37**;
- con N = 1000: (1 − f) ≈ **0.30**.

O sea, alrededor de un tercio del tiempo se comporta como no paralelizable. Por la ley de Amdahl, aunque hubiera
infinitos workers, en esta configuración S no pasaría de 1/(1 − f) ≈ **2.7–3.3**.

Además, en el barrido final, con N = 500 y N = 600 **(1 − f) crece con p** (0.50 → 0.55 y 0.215 → 0.37).

- Con N = 600, usando p = 2 sale (1 − f) ≈ 0.215, y con eso Amdahl predeciría S(4) ≈ 2.43. Se midió **1.89**:
  hay costos que aumentan con el número de workers, no solo una parte fija.
- Con N = 1000 no ocurre (0.40 → 0.30: desde p = 2, Amdahl predeciría S(4) ≈ 1.81 y se midió 2.09), en parte por la
  gran dispersión de p = 2 (S entre 1.18 y 1.70).
- En los barridos exploratorios, con N = 600 (1 − f) bajó de p = 2 a p = 4. Así que este punto es una tendencia
  del barrido final, no una regla firme. Lo que sí es firme es que existe una fracción grande, del orden de 0.2–0.5,
  que no se reparte.

**Partes que no se reducen al agregar workers (1 − f) en nuestro sistema:**

1. **En el cliente:**
   - serializar y enviar A y B al Master (16 MB con N = 1000);
   - recibir y deserializar C (8 MB).
2. **En el Master, antes de repartir:**
   - deserializar A y B;
   - el `ice_ping` a cada worker, uno tras otro;
   - particionar A.
3. **En el Master, al despachar:**
   - serializar y enviar **B completa a cada worker**: este costo **crece con p**;
   - recibir y deserializar los bloques, ensamblar C;
   - enviar C al cliente.
4. **En cada worker:** deserializar B completa (O(N²)) aunque solo calcule N/p filas. Ocurre al mismo tiempo en
   todos los workers, pero como no baja con p, cuenta como 1 − f.

Según la tabla 5.2, la comunicación (overhead del Master + cliente↔Master) suma **≈ 45 ms** con N = 1000. Con
N = 500 y p = 4 pesa incluso más que el cálculo: el worker más lento calcula solo 30 ms, mientras todo lo demás
(Master y red) suma ≈ 41 ms (T_dist 71.1 ms − 30 ms). Por eso ahí S = 1.51 y E = 38 %.

**Desbalance de carga.** No es parte de (1 − f), pero también limita el crecimiento lineal: T_dist lo marca el
worker más lento, no el promedio. Por ejemplo, con N = 1000 y p = 4 los cuatro bloques tienen 250 filas cada uno,
pero en una ejecución tardaron 324, 331, 390 y 313 ms.

**El factor más importante en esta máquina: contención de memoria compartida.** La **suma del tiempo de cómputo de
los workers crece** con p, aunque el trabajo total (N³ operaciones) es el mismo:

| N | Suma workers p = 1 | p = 2 | p = 4 | Tamaño total de las copias de B (p = 2 / p = 4) |
|---:|---:|---:|---:|---|
| 600 | 191 ms | 193 ms (+1 %) | 197 ms (+3 %) | 5.8 MB / 11.5 MB → caben en la L3 de 32 MB |
| 1000 | 883 ms | 1063 ms (+20 %) | 1300 ms (+47 %) | 16 MB / 32 MB → la L3 se llena |
| 1500 | 3201 ms | 5072 ms (+58 %) | 6893 ms (+115 %) | 36 MB / 72 MB → no caben |

- **Es un resultado robusto.** Con N = 1000 y N = 1500 los rangos de las 5 ejecuciones no se solapan entre p = 1,
  2 y 4. Por ejemplo, con N = 1000: 869–897 ms, 926–1176 ms y 1204–1385 ms.
- **La causa:** los 4 "nodos" son procesos en el **mismo** computador.
  - Cada worker recorre su copia completa de B por cada fila que calcula, y todas compiten por la misma caché L3 y
    el mismo bus de memoria.
  - El algoritmo ijk, que recorre B por columnas, está limitado por la memoria y no por la aritmética.
- **El efecto depende de N.** Por eso con N = 600 casi no hay contención, y con N = 1000 o 1500 sí.
- **En un clúster real** cada worker tendría su propia caché y memoria, y esta contención desaparecería. A cambio,
  aparecería el costo real de la red (ver pregunta 3).

**Conclusión:** S no se duplica porque:
- (a) hay una fracción de comunicación y serialización que no se reparte;
- (b) parte de ese costo crece con p, porque B se envía p veces;
- (c) el bloque más lento marca el tiempo;
- (d) en esta máquina, los workers comparten caché y memoria, así que el "trabajo paralelo" se vuelve más lento
  a medida que se agregan workers.

### Pregunta 3 — Patrón *shared-nothing* (NORMA)

> ¿Cuántas copias de la matriz B existen simultáneamente en memoria? ¿Qué impacto tiene en el ancho de banda y qué
> optimización propondría?

**Copias de B.** En UMA (un solo computador con hilos) hay **1** copia de B que todos los hilos leen. En nuestra
arquitectura NORMA, durante una multiplicación con p workers existen **p + 2 copias** deserializadas:

- **1 en el Cliente** (la matriz original `bigB`);
- **1 en el Master** (la `matrixB` recibida en `multiply`);
- **p en los Workers** (cada uno recibe B completa en `computeBlock`).

A esto se suman copias **transitorias serializadas**: los buffers de salida del cliente y los p mensajes que el Master
arma para los workers. Ejemplo: con N = 1000 y p = 4 hay 6 × 8 MB = **48 MB** ocupados por B, frente a 8 MB en UMA.

**Impacto en el ancho de banda.** Por cada multiplicación viajan (A y C se transmiten repartidos en bloques, pero
suman una matriz completa):

| Tramo | Datos |
|---|---|
| Cliente → Master | A + B = 2 matrices |
| Master → Workers | A (repartida) + **B × p** |
| Workers → Master | C (repartida) = 1 matriz |
| Master → Cliente | C = 1 matriz |
| **Total** | **(5 + p) · 8N² bytes**, de los cuales B aporta (1 + p) · 8N² |

| N | p | Tamaño de una matriz | Tráfico total por multiplicación | Parte de B |
|---:|---:|---:|---:|---:|
| 600 | 2 | 2.9 MB | 20.2 MB | 8.6 MB (43 %) |
| 1000 | 4 | 8.0 MB | 72 MB | 40 MB (56 %) |

- **Crece linealmente con p:** cada worker nuevo agrega 8N² bytes de B, aunque haga menos trabajo.
- **Todo pasa por el enlace del Master** (topología en estrella), que se vuelve el cuello de botella.
- **En una red real el costo sería mucho mayor que el medido.**
  - En *loopback* "enviar" es copiar memoria a varios GB/s, por eso medimos solo ≈ 45 ms de comunicación con
    N = 1000.
  - En una LAN de 1 Gbps (≈ 125 MB/s), 72 MB tardarían **≈ 0.6 s**.
  - Eso es más que el cómputo de cada worker con p = 4 (≈ 0.36 s): la red anularía la ganancia.
- **Aun en una sola máquina**, las p copias de B compiten por la caché L3 (pregunta 2).

**Optimizaciones propuestas:**

1. **Partición 2D (por bloques de C) en lugar de solo por filas** *(reduce el tráfico de una sola multiplicación)*.
   - Con p = q² workers en una grilla q×q, el worker (i, j) recibe el bloque de filas i de A y el bloque de columnas
     j de B, y calcula el bloque (i, j) de C.
   - Cada worker recibe N²/q datos de B en vez de N², así que el tráfico de B hacia los workers baja de p·N² a
     √p·N².
   - A cambio, cada bloque de filas de A se envía a los q workers de su fila de la grilla, así que el tráfico de A
     sube de N² a √p·N².
   - En el tramo Master → Workers, A + B pasa de (1 + p)·N² a 2√p·N²: con 4 workers de 5 N² a 4 N², con 16 workers
     de 17 N² a 8 N². La ganancia crece con p. Además, cada worker guarda solo una parte de B.
2. **Difusión en árbol o *pipeline*** *(quita la carga del enlace del Master)*.
   - *Árbol binomial*: en cada ronda, cada nodo que ya tiene B la reenvía a otro que aún no la tiene. Tras
     ⌈log₂(p+1)⌉ rondas todos la tienen, y el Master envía B solo ⌈log₂(p+1)⌉ veces en lugar de p.
   - *Pipeline* (cadena Master → W1 → W2 → …): B se parte en trozos y cada worker reenvía un trozo mientras recibe
     el siguiente. El Master envía B una sola vez.
   - En ambos casos cada worker sigue recibiendo B completa: no baja el total de bytes, pero reparte la carga entre
     todos los enlaces.
3. **Cachear B en el worker** *(útil cuando B se reutiliza)*.
   - Separar la operación en dos: `loadMatrixB(jobId, B)` y luego `computeBlock(jobId, startRow, rowsA)`.
   - En una sola multiplicación con un bloque por worker (como hoy), B ya viaja exactamente una vez a cada worker,
     así que esto **no** reduce su tráfico.
   - Sí ahorra p·8N² bytes por cada multiplicación adicional con la misma B.
   - Si el trabajo se parte en k > p bloques (para balancear carga o tolerar fallos), ahorra (k − p)·8N².
4. **Compresión o menos precisión** (secundario).
   - `ice_compress(true)` sirve para matrices dispersas o con estructura; con doubles aleatorios casi no reduce el
     tamaño. En Ice 3.7 para Java, la compresión (bzip2) requiere la librería Apache Commons Compress en el
     classpath de todos los procesos. Este proyecto no la incluye, así que hoy se ignoraría sin aviso.
   - Usar `float` en lugar de `double` reduciría el tráfico a la mitad, pero no cumpliría la tolerancia Δ < 10⁻⁵.

### Pregunta 4 — Tolerancia a fallos

> ¿Qué ocurriría si un worker se desconecta abruptamente en medio del cálculo de un bloque? ¿Cómo podría el Master
> detectar el fallo y reasignar el bloque a un worker superviviente?

**Lo que ocurre hoy.** Se observó experimentalmente con el código final, que no implementa tolerancia a fallos:
p = 2, N = 1500, y se mató el proceso de Worker-2 (`Stop-Process -Force`) ≈ 0.35 s después de que empezara a
calcular las filas 0–749.

1. **Ice detecta el fallo de inmediato, pero no hay recuperación.**
   - El sistema operativo cerró el socket TCP del worker muerto.
   - La llamada pendiente `computeBlock` falló en el Master con `com.zeroc.Ice.ConnectionLostException`, y el
     cliente ya tenía el error menos de 50 ms después de matar el proceso.
   - Fue tan rápido solo porque el bloque de Worker-2 (filas 0–749) era el primero que el Master espera con
     `join()`; los futures se esperan en orden. Si el worker muerto hubiera tenido las filas 750–1499, el Master
     habría esperado primero a que Worker-1 terminara su bloque (2435 ms), y el error habría llegado al cliente
     ≈ 2 s después.
   - Ice **no reintenta** automáticamente: `computeBlock` no está marcada como `idempotent`, y Ice garantiza
     semántica *at-most-once*.
2. **Se pierde toda la multiplicación.** El bucle de `join()` del Master convierte el error en `MatrixException`, y el
   cliente recibe:
   ```
   [ERROR] El Master reportó un fallo: Fallo en la ejecucion distribuida de un worker: com.zeroc.Ice.ConnectionLostException
       error = 0
   ```
3. **Se desperdicia trabajo.** Worker-1 siguió calculando su bloque (filas 750–1499) aunque el Master ya había
   abandonado la petición. El bloque le tomó **2435 ms** en total, así que trabajó **≈ 2 s más** después del fallo.
   Ese resultado nunca se usó.
4. **El worker muerto sigue registrado hasta la siguiente petición.**
   - En el siguiente `multiply`, el `ice_ping` a Worker-2 falló y el Master lo descartó:
     `[MASTER WARN] Worker inalcanzable, removiendo del pool: Worker-2`.
   - Esa multiplicación 4×4 tardó **1857 ms** en lugar de ~5 ms. El ping al worker muerto falló enseguida: el puerto
     ya estaba cerrado.
   - Casi todo el retraso fue el `ice_ping` a Worker-1. Tuvo que esperar a que su único hilo de *dispatch*
     (`Ice.ThreadPool.Server.Size` = 1 por defecto) terminara el bloque huérfano. La petición terminó justo cuando
     ese bloque acabó.
   - Después el sistema siguió funcionando con p = 1 (validación EXITOSA).
5. **Caso peor: fallo "silencioso".** Si en vez de morir el proceso se cae la máquina o el cable de red, no llega
   ningún aviso TCP. Como las invocaciones no tienen *timeout* (`Ice.Default.InvocationTimeout = -1`), el Master
   esperaría hasta que ACM (*Active Connection Management*, por defecto 60 s sin actividad) cerrara la conexión. Si
   el worker está vivo pero colgado, sigue enviando *heartbeats* y el Master **esperaría indefinidamente**.

**Cómo detectar el fallo:**

1. **Excepciones de la invocación.** Una excepción de conexión en `computeBlock` indica que ese worker falló:
   `com.zeroc.Ice.SocketException`, que incluye `ConnectionLostException` y `ConnectionRefusedException`. Es
   inmediato cuando el proceso muere.
   - Ojo: no toda `LocalException` es un fallo del worker.
   - `CommunicatorDestroyedException` significa que se está cerrando el propio Master.
   - `MarshalException`/`MemoryLimitException` significa que el Master no pudo leer la respuesta, por ejemplo por
     su `Ice.MessageSizeMax`.
   - Tratar esas como "worker caído" sacaría del pool a workers sanos.
2. **Timeouts de invocación** para workers colgados o fallos silenciosos:
   - usar `worker.ice_invocationTimeout(ms)`, que lanza `InvocationTimeoutException`;
   - elegir `ms` según el tiempo esperado del bloque (por ejemplo, 3–5 veces la estimación).
3. **Heartbeats / monitoreo.** Un hilo del Master puede hacer `ice_ping` periódico a los workers y sacarlos del pool
   antes de que llegue la siguiente petición; hoy solo se hace al inicio de cada `multiply`. Hay dos cuidados:
   - **Un worker ocupado responde tarde.** Cada worker atiende con un solo hilo de *dispatch*, así que un `ice_ping`
     a un worker que está calculando espera a que termine su bloque. Para hacer ping con *timeout* durante el
     cálculo habría que subir `Ice.ThreadPool.Server.Size` en los workers (por ejemplo, a 2); si no, un worker
     ocupado parecería caído. Aun así, que responda el ping no prueba que el bloque avance: para eso sigue haciendo
     falta el *timeout* de `computeBlock`.
   - **ACM debe estar sincronizado.** Si se ajusta `Ice.ACM.Timeout`, debe ponerse el mismo valor en el Master y en
     los workers. ZeroC indica que la configuración ACM de todos los procesos debe coincidir; si solo el Master la
     baja, puede cerrar conexiones sanas durante bloques largos.

**Cómo reasignar el bloque:**

- **Se puede reenviar sin riesgo.** Cada bloque es independiente y determinista: calcular de nuevo las filas
  `startRow..startRow+count-1` produce exactamente el mismo resultado y no tiene efectos secundarios.
- **El Master guarda los datos de cada bloque** (`startRow`, `sliceA`) y, si su worker falla:
  1. lo saca del pool;
  2. reenvía **el mismo bloque** a un worker superviviente;
  3. solo si ninguno responde, lanza `MatrixException`.

Propuesta (no implementada), para llamar dentro del `supplyAsync` de cada bloque:

```java
private BlockResult computeWithRetry(int startRow, double[][] sliceA, double[][] matrixB,
                                     List<WorkerServicePrx> pool, int first) throws MatrixException {
    for (int intento = 0; intento < pool.size(); intento++) {
        WorkerServicePrx w = pool.get((first + intento) % pool.size());   // primero el asignado, luego los demás
        try {
            return w.ice_invocationTimeout(60_000).computeBlock(startRow, sliceA, matrixB);
        } catch (com.zeroc.Ice.SocketException | com.zeroc.Ice.TimeoutException e) { // caído, inalcanzable o colgado
            workers.values().remove(w);                                    // sacarlo del pool
            System.err.printf("[MASTER] Worker falló; reasignando bloque fila %d: %s%n", startRow, e);
        }
    }
    throw new MatrixException("Ningún worker sobreviviente pudo calcular el bloque de la fila " + startRow);
}
```

**Mejoras adicionales:**

- **Granularidad más fina (bolsa de tareas).** Dividir A en más bloques que workers y repartirlos desde una cola. Si un
  worker cae, se pierde menos trabajo, los supervivientes se reparten el bloque perdido y además se balancea la
  carga.
- **No descartar el trabajo ya terminado.** Ensamblar los bloques que sí llegaron y reintentar solo los fallidos (hoy
  el primer error aborta todo).
- **Tareas de respaldo (*speculative execution*).** Al final del cálculo, duplicar en otro worker los bloques más
  lentos y quedarse con el primer resultado (estrategia usada por MapReduce).
