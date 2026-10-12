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
