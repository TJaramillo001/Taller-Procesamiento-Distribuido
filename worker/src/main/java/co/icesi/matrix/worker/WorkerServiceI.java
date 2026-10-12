package co.icesi.matrix.worker;

import MatrixCompute.BlockResult;
import MatrixCompute.MatrixException;
import MatrixCompute.WorkerService;
import com.zeroc.Ice.Current;

/**
 * WorkerServiceI
 * 
 * Implementación del Servant del Worker en ZeroC Ice.
 * Responsabilidad: Recibe un bloque de filas de la matriz A y la matriz B completa,
 * ejecuta el cómputo intensivo local y retorna el resultado parcial cronometrado.
 */
public class WorkerServiceI implements WorkerService {

    private final String workerId;

    public WorkerServiceI(String workerId) {
        this.workerId = workerId;
    }

    @Override
    public BlockResult computeBlock(int startRow, double[][] rowsA, double[][] matrixB, Current current) 
            throws MatrixException {
        
        long startTime = System.currentTimeMillis();

        if (rowsA == null || matrixB == null || rowsA.length == 0 || matrixB.length == 0) {
            throw new MatrixException("Datos de matriz invalidos o vacios recibidos en el worker " + workerId);
        }

        int numRowsA = rowsA.length;
        int numColsA = rowsA[0].length;
        int numRowsB = matrixB.length;
        int numColsB = matrixB[0].length;

        if (numColsA != numRowsB) {
            throw new MatrixException("Incompatibilidad de dimensiones en worker: cols(A)=" + numColsA + " != rows(B)=" + numRowsB);
        }

        System.out.printf("[%s] Procesando bloque: startRow=%d, filasA=%d x colsA=%d contra matrizB=%d x %d%n",
                workerId, startRow, numRowsA, numColsA, numRowsB, numColsB);

        // =========================================================================
        // NÚCLEO ALGORÍTMICO: MULTIPLICACIÓN MATRICIAL POR BLOQUE DE FILAS
        // =========================================================================
        double[][] computedRows = new double[numRowsA][numColsB];

        for (int i = 0; i < numRowsA; i++) {
            for (int j = 0; j < numColsB; j++) {
                double sum = 0.0;
                for (int k = 0; k < numColsA; k++) {
                    sum += rowsA[i][k] * matrixB[k][j];
                }
                computedRows[i][j] = sum;
            }
        }

        long computeTime = System.currentTimeMillis() - startTime;
        System.out.printf("[%s] Bloque completado exitosamente en %d ms.%n", workerId, computeTime);

        return new BlockResult(startRow, computedRows, computeTime, workerId);
    }

    @Override
    public String getWorkerInfo(Current current) {
        return "WorkerID: " + workerId + " | Estado: OPERACIONAL | Cores: " + Runtime.getRuntime().availableProcessors();
    }
}
