package co.icesi.matrix.master;

import MatrixCompute.BlockResult;
import MatrixCompute.MasterService;
import MatrixCompute.MatrixException;
import MatrixCompute.WorkerServicePrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.ObjectPrx;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MasterServiceI
 * 
 * Implementación del Servant del Maestro (Master / Server).
 * Responsabilidades:
 *  1. Gestiona dinámicamente el pool de Workers registrados en el clúster.
 *  2. Al recibir una solicitud de multiplicación desde un Cliente:
 *     - Valida dimensiones de las matrices A y B.
 *     - Particiona la matriz A en sub-bloques horizontales de filas.
 *     - Despacha concurrentemente cada bloque a un Worker disponible (RPC de Ice).
 *     - Recolecta los bloques calculados y reensambla la matriz C final.
 */
public class MasterServiceI implements MasterService {

    private final Map<String, WorkerServicePrx> workers = new ConcurrentHashMap<>();

    @Override
    public double[][] multiply(double[][] matrixA, double[][] matrixB, Current current) throws MatrixException {
        long globalStartTime = System.currentTimeMillis();

        // 1. Validaciones estructurales y de dimensiones
        if (matrixA == null || matrixB == null || matrixA.length == 0 || matrixB.length == 0) {
            throw new MatrixException("Las matrices de entrada no pueden ser nulas ni estar vacias.");
        }

        int rowsA = matrixA.length;
        int colsA = matrixA[0].length;
        int rowsB = matrixB.length;
        int colsB = matrixB[0].length;

        if (colsA != rowsB) {
            throw new MatrixException(String.format(
                    "Dimensiones incompatibles: columnas de A (%d) != filas de B (%d)", colsA, rowsB));
        }

        // 2. Verificar disponibilidad de Workers
        List<WorkerServicePrx> activeWorkers = new ArrayList<>();
        List<String> activeWorkerIds = new ArrayList<>(); // mismo orden que activeWorkers (para los logs)
        synchronized (workers) {
            for (Map.Entry<String, WorkerServicePrx> entry : workers.entrySet()) {
                try {
                    // Validar conectividad con el worker
                    entry.getValue().ice_ping();
                    activeWorkers.add(entry.getValue());
                    activeWorkerIds.add(entry.getKey());
                } catch (Exception e) {
                    System.err.println("[MASTER WARN] Worker inalcanzable, removiendo del pool: " + entry.getKey());
                    workers.remove(entry.getKey());
                }
            }
        }

        if (activeWorkers.isEmpty()) {
            throw new MatrixException("No hay Workers operacionales registrados en el Master.");
        }

        int numWorkers = activeWorkers.size();
        System.out.printf("%n[MASTER] Iniciando multiplicación distribuida: A[%dx%d] * B[%dx%d] usando %d Worker(s)%n",
                rowsA, colsA, rowsB, colsB, numWorkers);

        // 3. Particionar la matriz A en bloques de filas contiguas (Grano Grueso)
        int rowsPerWorker = (int) Math.ceil((double) rowsA / numWorkers);
        List<CompletableFuture<BlockResult>> taskFutures = new ArrayList<>();

        int currentRow = 0;
        for (int w = 0; w < numWorkers && currentRow < rowsA; w++) {
            int startRow = currentRow;
            int count = Math.min(rowsPerWorker, rowsA - startRow);

            // Extraer las filas correspondientes a este bloque
            double[][] sliceA = new double[count][colsA];
            for (int r = 0; r < count; r++) {
                sliceA[r] = matrixA[startRow + r];
            }

            WorkerServicePrx worker = activeWorkers.get(w);
            String workerId = activeWorkerIds.get(w);

            // Invocación asíncrona hacia el Worker
            CompletableFuture<BlockResult> future = CompletableFuture.supplyAsync(() -> {
                System.out.printf("[MASTER -> %s] Despachando bloque: filas [%d .. %d] (%d filas)%n",
                        workerId, startRow, (startRow + count - 1), count);
                try {
                    return worker.computeBlock(startRow, sliceA, matrixB);
                } catch (MatrixException e) {
                    throw new RuntimeException("Error en cálculo remoto del worker: " + e.reason, e);
                }
            });

            taskFutures.add(future);
            currentRow += count;
        }

        // 4. Recolectar resultados parciales y ensamblar matriz resultante C
        double[][] resultC = new double[rowsA][colsB];
        long totalWorkerComputeMs = 0;

        for (CompletableFuture<BlockResult> future : taskFutures) {
            try {
                BlockResult block = future.join();
                totalWorkerComputeMs += block.computeTimeMs;

                // Copiar las filas computadas en la matriz destino C
                for (int i = 0; i < block.computedRows.length; i++) {
                    resultC[block.startRow + i] = block.computedRows[i];
                }
                System.out.printf("[MASTER <- %s] Recibido bloque fila %d completado en %d ms%n",
                        block.workerId, block.startRow, block.computeTimeMs);

            } catch (Exception e) {
                throw new MatrixException("Fallo en la ejecucion distribuida de un worker: " + e.getMessage());
            }
        }

        long totalTimeMs = System.currentTimeMillis() - globalStartTime;
        System.out.printf("[MASTER] Multiplicación finalizada con éxito. Tiempo total: %d ms (Suma tiempos workers: %d ms)%n",
                totalTimeMs, totalWorkerComputeMs);

        return resultC;
    }

    @Override
    public void registerWorker(String workerId, String endpoint, Current current) {
        try {
            Communicator communicator = current.adapter.getCommunicator();
            String proxyStr = "WorkerService:" + endpoint;
            ObjectPrx base = communicator.stringToProxy(proxyStr);
            WorkerServicePrx prx = WorkerServicePrx.checkedCast(base);

            if (prx != null) {
                // Si este mismo host:puerto ya estaba registrado con otro ID (ej. un worker reiniciado),
                // quitamos la entrada vieja para no contar dos veces el mismo proceso.
                workers.values().removeIf(old -> old.equals(prx));
                workers.put(workerId, prx);
                System.out.printf("[MASTER] [+] Worker registrado exitosamente: %s (%s) | Total activos: %d%n",
                        workerId, endpoint, workers.size());
            } else {
                System.err.printf("[MASTER] [-] Error al validar proxy de worker %s en %s%n", workerId, endpoint);
            }
        } catch (Exception e) {
            System.err.printf("[MASTER] [-] Fallo al registrar worker %s: %s%n", workerId, e.getMessage());
        }
    }

    @Override
    public void unregisterWorker(String workerId, Current current) {
        if (workers.remove(workerId) != null) {
            System.out.printf("[MASTER] [-] Worker desregistrado: %s | Total restantes: %d%n", workerId, workers.size());
        }
    }

    @Override
    public int getWorkerCount(Current current) {
        return workers.size();
    }
}
