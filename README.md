# Taller Formativo: Multiplicación Distribuida de Matrices (Patrón Master-Worker)

**Asignatura:** Computación en Internet I (`09810 - TIC`)  
**Departamento:** Departamento de Computación y Sistemas Inteligentes (DCSI)  
**Facultad:** Facultad de Ingeniería, Diseño y Ciencias Aplicadas  
**Institución:** Universidad Icesi, Cali, Colombia  
**Tecnología:** Java 17+, ZeroC Ice 3.7.10, Slice IDL, Gradle 8.6 Multimódulo  
**Guía Oficial en PDF:** [docs/Taller_Multiplicacion_Matrices_Master_Worker.pdf](docs/Taller_Multiplicacion_Matrices_Master_Worker.pdf)  
**Respuestas, métricas y resultados del taller:** [docs/RESPUESTAS.md](docs/RESPUESTAS.md)  

---

## 🎯 Objetivo Pedagógico

Este taller permite a los estudiantes realizar la transición fundamental de **"comunicar dos componentes por la red"** (mediante Sockets o RPC) hacia el **"diseño de aplicaciones de procesamiento distribuido"** capaces de resolver problemas de alta intensidad computacional mediante el patrón arquitectónico **Master-Worker** y el modelo **MIMD + NORMA (Shared-Nothing)**.

---

## 🏗️ Arquitectura del Sistema

```
                  ┌────────────────────────────────────────┐
                  │                 CLIENTE                │
                  │  - Genera matrices A y B               │
                  │  - Invoca master.multiply(A, B)        │
                  │  - Mide T_sec vs T_dist y Speedup (S)  │
                  └──────────────────┬─────────────────────┘
                                     │
                             ZeroC Ice RPC
                           (tcp -p 10000)
                                     ▼
                  ┌────────────────────────────────────────┐
                  │             MASTER (SERVER)            │
                  │  - Gestiona pool de Workers activos    │
                  │  - Valida dimensiones de matrices      │
                  │  - Particiona A en bloques de filas    │
                  │  - Despacha bloques concurrentemente   │
                  │  - Ensambla y retorna la matriz C      │
                  └──────┬──────────────────────┬──────────┘
                         │                      │
                   ZeroC Ice RPC          ZeroC Ice RPC
                 (tcp -p 10001)         (tcp -p 10002)
                         ▼                      ▼
            ┌────────────────────────┐  ┌────────────────────────┐
            │   WORKER 1 (Worker-1)  │  │   WORKER 2 (Worker-2)  │
            │ - Filas A [0 .. N/2-1] │  │ - Filas A [N/2 .. N-1] │
            │ - Matriz B completa    │  │ - Matriz B completa    │
            │ - Cómputo O(k*N*M)     │  │ - Cómputo O(k*N*M)     │
            └────────────────────────┘  └────────────────────────┘
```

---

## 📁 Estructura del Proyecto Multimódulo

```text
taller-matrices-master-worker/
├── settings.gradle                        # Orquestador de submódulos Gradle
├── build.gradle                           # Configuración global y plugin Ice Builder
├── common/                                # Subproyecto de contrato e interfaces Slice
│   ├── build.gradle
│   └── src/main/slice/
│       └── MatrixCompute.ice              # Definición IDL de tipos y servicios
├── server/                                # Subproyecto del Servidor Maestro (Master)
│   ├── build.gradle
│   └── src/main/java/co/icesi/matrix/master/
│       ├── MasterMain.java                # Punto de entrada y listener Ice
│       └── MasterServiceI.java            # Servant: Partición y concurrencia
├── worker/                                # Subproyecto del Nodo Trabajador (Worker)
│   ├── build.gradle
│   └── src/main/java/co/icesi/matrix/worker/
│       ├── WorkerMain.java                # Punto de entrada y auto-registro
│       └── WorkerServiceI.java            # Servant: Cómputo numérico intensivo
└── client/                                # Subproyecto del Cliente de Pruebas
    ├── build.gradle
    └── src/main/java/co/icesi/matrix/client/
        └── ClientMain.java                # Benchmark, cálculo de Speedup y verificación
```

---

## 🚀 Guía de Ejecución Paso a Paso

### 1. Compilar todo el proyecto
```bash
./gradlew build
```

### 2. Iniciar el Nodo Maestro (Master) en la Terminal 1
```bash
./gradlew :server:runMaster
```
*El Master escuchará en el puerto TCP `10000` y esperará el registro de Workers.*

### 3. Iniciar los Nodos Trabajadores (Workers) en Terminales Separadas
**Terminal 2 (Worker 1):**
```bash
./gradlew :worker:runWorker --args="10001 Worker-1 localhost 10000"
```

**Terminal 3 (Worker 2):**
```bash
./gradlew :worker:runWorker --args="10002 Worker-2 localhost 10000"
```

*(Opcional: puedes iniciar tantos workers como desees en puertos diferentes, ej. `10003`, `10004`)*.

Formato de argumentos del worker: `<puertoWorker> <idWorker> <hostMaster> <puertoMaster> [ipWorker]`.
Para correr workers en **otras máquinas del laboratorio**, vea la sección
[Ejecución en el laboratorio (varias máquinas)](#-ejecución-en-el-laboratorio-varias-máquinas).

### 4. Ejecutar el Cliente de Validación y Benchmark en la Terminal 4
```bash
# Ejecuta validación 4x4 y benchmark de 500x500
./gradlew :client:runClient

# O especifica host, puerto y tamaño de matriz (ej. 800x800):
./gradlew :client:runClient --args="localhost 10000 800"
```

### Notas para Windows (PowerShell)
- Use `.\gradlew` en lugar de `./gradlew` (ejecuta `gradlew.bat`). Los mismos comandos funcionan tal cual, por ejemplo:
  `.\gradlew :worker:runWorker --args="10001 Worker-1 localhost 10000"`.
- Alternativa más liviana (sin un daemon de Gradle por terminal): compile una vez con `.\gradlew installDist` y ejecute los scripts generados:
  ```powershell
  .\server\build\install\server\bin\server.bat
  .\worker\build\install\worker\bin\worker.bat 10001 Worker-1 localhost 10000
  .\worker\build\install\worker\bin\worker.bat 10002 Worker-2 localhost 10000
  .\client\build\install\client\bin\client.bat localhost 10000 600
  ```
- **Tildes:** las tareas `runMaster`, `runWorker` y `runClient` hacen que el programa escriba en UTF-8 (sin eso, Gradle
  las convertía en `?`). Si la terminal muestra `Multiplicaci├│n` en lugar de `Multiplicación`, está usando la página
  de códigos antigua de Windows (850): ejecute `chcp 65001` en esa terminal antes de `.\gradlew ...`.

### Nota: límite de tamaño de mensaje de Ice (`Ice.MessageSizeMax`)
Ice rechaza por defecto cualquier mensaje mayor a **1 MB**. Una matriz de `600x600` doubles ya ocupa ~2.9 MB, así que con la
configuración por defecto el benchmark falla (`com.zeroc.Ice.SocketException`, causada por `java.io.IOException: Connection reset by peer`). Por eso
`MasterMain`, `WorkerMain` y `ClientMain` suben el límite a 100 MB al crear el `Communicator`
(detalles en [docs/RESPUESTAS.md](docs/RESPUESTAS.md)).

---

## 🌐 Ejecución en el laboratorio (varias máquinas)

Los mismos tres programas pueden correr en computadores distintos de la red del laboratorio. Ejemplo de topología:

| Máquina | Rol | Ejemplo de IP | Puerto(s) que debe aceptar |
|---|---|---|---|
| PC-A | Master | `192.168.1.20` | `10000` |
| PC-B (vecino) | Worker-B1, Worker-B2 | `192.168.1.21` | `10001`, `10002` |
| PC-C (vecino) | Worker-C1 | `192.168.1.22` | `10001` |
| Cualquiera | Cliente | — | ninguno |

### ¿Por qué funciona entre máquinas?
- Master y Workers abren su `ObjectAdapter` en `tcp -h 0.0.0.0 -p <puerto>`, es decir, **escuchan en todas las
  interfaces de red**, no solo en `localhost`.
- La comunicación va en **dos sentidos**: el Worker se conecta al Master para registrarse (`registerWorker`), y luego el
  **Master se conecta al Worker** para enviarle bloques (`computeBlock`). Por eso ambos lados necesitan su puerto abierto.
- Al registrarse, el Worker le envía al Master el endpoint donde puede contactarlo. Ese endpoint **no puede ser
  `localhost`** (para el Master, `localhost` es él mismo). `WorkerMain` detecta automáticamente la IP de la interfaz que
  usa para llegar al Master (abre un `DatagramSocket` UDP y lo "conecta" al Master: no envía paquetes, solo consulta la
  tabla de rutas del SO) y la imprime como `IP anunciada al Master`. Se puede forzar con el 5to argumento `[ipWorker]`.
- Como red de seguridad, `MasterServiceI.registerWorker` reemplaza un endpoint `localhost`/`127.x` recibido desde otra
  máquina por la IP real de la conexión TCP entrante (`current.con.getInfo()`).
- Master y Worker usan `Ice.Override.ConnectTimeout = 5000`: si una IP está mal o un firewall bloquea, el error aparece
  en ~5 s en lugar de quedarse colgado.

### Requisitos
- Todas las máquinas en la **misma red/subred** del laboratorio (que se puedan hacer `ping` entre sí).
- **Java 17+** en cada máquina.
- La misma versión del código en todas (mismo `MatrixCompute.ice`).
- Solo la máquina que **compila** necesita `slice2java` (Ubuntu: `sudo apt install zeroc-ice-compilers`, versión 3.7.10).
  Las máquinas que solo ejecutan workers no lo necesitan si usan la distribución ya compilada (opción B abajo).

### Paso 1 — Preparar las máquinas
**Opción A: clonar y compilar en cada máquina** (requiere `slice2java` en cada una):
```bash
git clone <url-del-repositorio> && cd Taller-Procesamiento-Distribuido
./gradlew build
```

**Opción B (recomendada para PCs vecinos): compilar una vez y copiar**
En la máquina que compila:
```bash
./gradlew installDist
```
Esto genera `worker/build/install/worker/` (con `bin/` y `lib/`, incluye `ice-3.7.10.jar`). Copie esa carpeta al PC
vecino por USB, carpeta compartida o `scp`:
```bash
scp -r worker/build/install/worker usuario@192.168.1.21:~/worker
```
En el PC vecino solo hace falta Java.

### Paso 2 — Iniciar el Master (PC-A)
1. Averigüe la IP del PC-A en la red del laboratorio:
   - Linux: `hostname -I` (o `ip -4 addr`)
   - Windows: `ipconfig` → "Dirección IPv4" del adaptador Ethernet/Wi-Fi del laboratorio
2. Abra el puerto `10000` en el firewall (ver [Firewall](#firewall)).
3. Ejecute:
   ```bash
   ./gradlew :server:runMaster
   ```

### Paso 3 — Iniciar Workers desde máquinas vecinas (PC-B, PC-C, ...)
1. **Verifique que el PC vecino alcanza al Master** antes de lanzar nada:
   ```bash
   ping 192.168.1.20
   nc -zv 192.168.1.20 10000          # Linux/macOS
   ```
   ```powershell
   Test-NetConnection 192.168.1.20 -Port 10000   # Windows
   ```
   Debe responder *succeeded* / `TcpTestSucceeded : True`.
2. Abra en el firewall **del PC vecino** el puerto de cada worker (ej. `10001`, `10002`): el Master se conectará a él.
3. Lance el worker pasando la **IP del Master** (no `localhost`) y un ID único en todo el clúster
   (sugerencia: incluir el nombre del PC):
   - Con el repo clonado (opción A):
     ```bash
     ./gradlew :worker:runWorker --args="10001 Worker-B1 192.168.1.20 10000"
     ```
   - Con la distribución copiada (opción B):
     ```bash
     ~/worker/bin/worker 10001 Worker-B1 192.168.1.20 10000
     ```
     ```powershell
     .\worker\bin\worker.bat 10001 Worker-B1 192.168.1.20 10000
     ```
4. Revise la salida. En el worker:
   ```
   [WORKER] IP anunciada al Master: 192.168.1.21
   [WORKER] Registrado exitosamente en Master (MasterService:tcp -h 192.168.1.20 -p 10000)
   ```
   Y en el Master:
   ```
   [MASTER] [+] Worker registrado exitosamente: Worker-B1 (tcp -h 192.168.1.21 -p 10001) | Total activos: 1
   ```
   Si la IP anunciada no es la de la red del laboratorio (p. ej. la de VirtualBox/Docker o `127.0.0.1`), fuércela
   con el 5to argumento:
   ```bash
   ./gradlew :worker:runWorker --args="10001 Worker-B1 192.168.1.20 10000 192.168.1.21"
   ```
5. Para más workers en el **mismo** PC vecino use otro puerto y otro ID (`10002 Worker-B2 ...`). Repita en PC-C, PC-D, etc.
6. Para retirar un worker presione `Ctrl+C`: se desregistra del Master automáticamente.

### Paso 4 — Ejecutar el Cliente (desde cualquier PC)
```bash
./gradlew :client:runClient --args="192.168.1.20 10000 800"
```
El Master reparte las filas de A entre todos los workers registrados (locales y remotos) y el cliente reporta
`T_sec`, `T_dist`, Speedup y Eficiencia. Con workers en máquinas distintas, cada worker aporta núcleos reales
adicionales, pero también se suma el costo de enviar B completa por la red a cada uno.

### Firewall
Abra el rango de puertos usado (Master `10000`, workers `10001+`) en **cada** máquina que escuche:
- **Linux (ufw):**
  ```bash
  sudo ufw allow 10000:10010/tcp
  ```
- **Windows (PowerShell como administrador):**
  ```powershell
  New-NetFirewallRule -DisplayName "Ice Master-Worker" -Direction Inbound -Protocol TCP -LocalPort 10000-10010 -Action Allow
  ```
  (O acepte el aviso "Permitir acceso" de Windows Defender la primera vez que Java abre el puerto, marcando la red privada.)

Si no tiene permisos de administrador en los equipos del laboratorio, pida al monitor/encargado que habilite esos puertos.

### Solución de problemas
| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| Worker: `No fue posible auto-registrarse ... ConnectTimeoutException` | IP del Master errada o puerto `10000` bloqueado | Verificar IP con `hostname -I`/`ipconfig`; probar `nc -zv` / `Test-NetConnection`; abrir firewall en PC-A |
| Worker: `ConnectionRefusedException` | El Master no está corriendo (o en otro puerto) | Iniciar el Master primero |
| Master: `Fallo al registrar worker ...` | El Master no puede conectarse **de vuelta** al worker | Abrir el puerto del worker en el firewall del PC vecino; revisar `IP anunciada` y usar el 5to argumento si es incorrecta |
| Worker anuncia `127.0.0.1` | Se pasó `localhost` como host del Master | Usar la IP real del Master |
| Cliente/Master: `SocketException` / `Connection reset` con matrices grandes | Límite de tamaño de mensaje | Ya se sube a 100 MB (`Ice.MessageSizeMax`); para matrices enormes, aumentar ese valor en los tres `*Main` |

---

## 📊 Métricas Calculadas en el Benchmark

1. **Aceleración (Speedup $S$):**
   $$S = \frac{T_{\text{sec}}}{T_{\text{dist}}}$$
2. **Eficiencia Paralela ($E$):**
   $$E = \frac{S}{p} \times 100\%$$
   *(donde $p$ es el número de workers activos).*
3. **Validación Numérica:**
   Compara que cada celda de la matriz distribuida sea exactamente idéntica al cómputo secuencial monohilo con tolerancia $|C_{\text{dist}}[i][j] - C_{\text{sec}}[i][j]| < 10^{-5}$.

*(Los tiempos se miden con `System.nanoTime()` después de un calentamiento de la JVM de al menos 0,5 s que no se mide, para que el JIT ya haya compilado el código. Resultados medidos en [docs/RESPUESTAS.md](docs/RESPUESTAS.md).)*

---

## 👨‍🏫 Autor y Créditos
- **Curso:** Computación en Internet I — Universidad Icesi
- **Profesor:** Alejandro Muñoz Bravo
- **Estudiante:** Tomás Jaramillo Caicedo
- **Departamento:** Departamento de Computación y Sistemas Inteligentes (DCSI)
