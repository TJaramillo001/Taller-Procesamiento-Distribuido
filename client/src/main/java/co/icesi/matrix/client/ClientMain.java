package co.icesi.matrix.client;

import MatrixCompute.MasterServicePrx;
import MatrixCompute.MatrixException;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;

import java.util.Random;

/**
 * ClientMain
 * 
 * Punto de entrada del nodo Cliente.
 * Responsabilidades:
 *  1. Se conecta al Master vía ZeroC Ice.
 *  2. Ejecuta una prueba de validación funcional con matrices pequeñas (4x4).
 *  3. Ejecuta un Benchmark de rendimiento con matrices grandes (NxN):
 *     - Mide el tiempo secuencial monohilo en local (T_sec).
 *     - Mide el tiempo distribuido con el cluster Master-Worker (T_dist).
 *     - Valida la exactitud numérica entre ambas matrices resultantes.
 *     - Calcula y muestra la Aceleración (Speedup S) y Eficiencia (E).
 */
public class ClientMain {

    public static void main(String[] args) {
        String masterHost = "localhost";
        int masterPort = 10000;
        int benchmarkSize = 500; // Tamaño por defecto para el benchmark

        if (args.length >= 1) {
            masterHost = args[0];
        }
        if (args.length >= 2) {
            try {
                masterPort = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {}
        }
        if (args.length >= 3) {
            try {
                benchmarkSize = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {}
        }

        System.out.println("=============================================================");
        System.out.println("   CLIENTE DE MULTIPLICACIÓN DISTRIBUIDA DE MATRICES (ICE)");
        System.out.printf("   Destino Master: %s:%d | Dimensión Benchmark: %dx%d%n", masterHost, masterPort, benchmarkSize, benchmarkSize);
        System.out.println("=============================================================\n");

        // Ice rechaza por defecto mensajes de mas de 1 MB (Ice.MessageSizeMax = 1024 KB).
        // El Cliente recibe la matriz C completa (~2.9 MB en 600x600), asi que subimos el limite a 100 MB (valor en KB).
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties();
        initData.properties.setProperty("Ice.MessageSizeMax", "102400");

        try (Communicator communicator = Util.initialize(args, initData)) {
            String masterEndpoint = String.format("MasterService:tcp -h %s -p %d", masterHost, masterPort);
            ObjectPrx base = communicator.stringToProxy(masterEndpoint);
            MasterServicePrx master = MasterServicePrx.checkedCast(base);

            if (master == null) {
                System.err.println("[ERROR] No se pudo conectar al Master en " + masterEndpoint);
                return;
            }

            int activeWorkers = master.getWorkerCount();
            System.out.printf("[CLIENTE] Conexión establecida. Workers activos en el clúster: %d%n%n", activeWorkers);

            if (activeWorkers == 0) {
                System.err.println("[ADVERTENCIA] No hay workers registrados en el Master.");
                System.err.println("Inicie al menos un worker con: ./gradlew :worker:runWorker");
                return;
            }

            // =========================================================================
            // FASE 1: PRUEBA DE EXACTITUD CON MATRICES PEQUEÑAS (4x4)
            // =========================================================================
            System.out.println(">>> FASE 1: Verificación de Exactitud con Matrices Pequeñas (4x4)");
            double[][] smallA = {
                {1.0, 2.0, 3.0, 4.0},
                {5.0, 6.0, 7.0, 8.0},
                {2.0, 0.0, 1.0, 3.0},
                {4.0, 1.0, 2.0, 1.0}
            };
            double[][] smallB = {
                {2.0, 0.0, 1.0, 2.0},
                {1.0, 3.0, 2.0, 0.0},
                {0.0, 1.0, 4.0, 1.0},
                {3.0, 2.0, 0.0, 1.0}
            };

            System.out.println("[CLIENTE] Enviando matrices pequeñas al Master...");
            double[][] smallDist = master.multiply(smallA, smallB);
            double[][] smallLocal = multiplyLocal(smallA, smallB);

            System.out.println("\nMatriz Resultante C (Distribuida 4x4):");
            printMatrix(smallDist);

            boolean match = compareMatrices(smallLocal, smallDist, 1e-6);
            System.out.printf("[CLIENTE] ¿Coincidencia exacta con cálculo local? %s%n%n", (match ? "SI (CORRECTO)" : "NO (ERROR)"));

            // =========================================================================
            // FASE 2: BENCHMARK DE RENDIMIENTO (NxN)
            // =========================================================================
            System.out.printf(">>> FASE 2: Benchmark de Rendimiento Masivo (%dx%d)%n", benchmarkSize, benchmarkSize);
            System.out.println("[CLIENTE] Generando matrices pseudo-aleatorias...");
            double[][] bigA = generateRandomMatrix(benchmarkSize, benchmarkSize, 42);
            double[][] bigB = generateRandomMatrix(benchmarkSize, benchmarkSize, 43);

            // 0. Calentamiento (sin medir): la JVM primero interpreta el codigo y solo lo compila (JIT)
            //    cuando se ha ejecutado muchas veces. Repetimos ambas versiones durante al menos medio
            //    segundo para que las dos mediciones usen codigo ya compilado (con matrices pequenas
            //    una sola repeticion no alcanzaria).
            System.out.println("[CLIENTE] Calentamiento de la JVM (no se mide)...");
            long finCalentamiento = System.nanoTime() + 500_000_000L;
            do {
                multiplyLocal(bigA, bigB);
                master.multiply(bigA, bigB);
            } while (System.nanoTime() < finCalentamiento);

            // 1. Cómputo Secuencial Local
            // (nanoTime es mas preciso que currentTimeMillis: permite medir fracciones de milisegundo)
            System.out.print("[CLIENTE] Ejecutando multiplicación secuencial monohilo local... ");
            long t0Sec = System.nanoTime();
            double[][] resultLocal = multiplyLocal(bigA, bigB);
            double tSec = (System.nanoTime() - t0Sec) / 1_000_000.0;
            System.out.printf("Listo en %.3f ms.%n", tSec);

            // 2. Cómputo Distribuido con Master y Workers
            System.out.print("[CLIENTE] Despachando al clúster distribuido... ");
            long t0Dist = System.nanoTime();
            double[][] resultDist = master.multiply(bigA, bigB);
            double tDist = (System.nanoTime() - t0Dist) / 1_000_000.0;

            // En cada multiply el Master hace ice_ping y descarta los workers caidos. Por eso consultamos p
            // despues de la llamada medida: asi p ya no cuenta un worker que se cayo antes de la medicion.
            activeWorkers = master.getWorkerCount();
            System.out.printf("Listo en %.3f ms con %d worker(s).%n", tDist, activeWorkers);

            // 3. Validación Numérica
            boolean validBenchmark = compareMatrices(resultLocal, resultDist, 1e-5);

            // 4. Cálculo de Métricas de Escalabilidad
            double speedup = tSec / tDist;
            double efficiency = (speedup / activeWorkers) * 100.0;

            System.out.println("\n=============================================================");
            System.out.println("                 RESULTADOS DEL BENCHMARK");
            System.out.println("=============================================================");
            System.out.printf("  Dimensión de Matrices:      %d x %d%n", benchmarkSize, benchmarkSize);
            System.out.printf("  Operaciones Aritméticas:    ~%.2e ops%n", 2.0 * Math.pow(benchmarkSize, 3));
            System.out.printf("  Workers Activos (p):        %d%n", activeWorkers);
            System.out.printf("  Tiempo Secuencial (T_sec):  %.3f ms%n", tSec);
            System.out.printf("  Tiempo Distribuido (T_dist):%.3f ms%n", tDist);
            System.out.printf("  Aceleración (Speedup S):    %.2fx%n", speedup);
            System.out.printf("  Eficiencia Paralela (E):    %.1f%%%n", efficiency);
            System.out.printf("  Validación Numérica:        %s%n", (validBenchmark ? "EXITOSA (Delta < 1e-5)" : "FALLIDA"));
            System.out.println("=============================================================\n");

        } catch (MatrixException e) {
            // MatrixException se declaro en MatrixCompute.ice con el campo "reason": el mensaje viaja ahi
            // (getMessage() es null porque el constructor generado no se lo pasa a java.lang.Exception)
            System.err.println("[ERROR] El Master reportó un fallo: " + e.reason);
        } catch (Exception e) {
            System.err.println("[ERROR] Excepción en Cliente: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static double[][] generateRandomMatrix(int rows, int cols, long seed) {
        Random rand = new Random(seed); // Semilla fija => reproducible (una distinta para A y para B)
        double[][] m = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                m[i][j] = rand.nextDouble() * 10.0;
            }
        }
        return m;
    }

    private static double[][] multiplyLocal(double[][] A, double[][] B) {
        int rA = A.length;
        int cA = A[0].length;
        int cB = B[0].length;
        double[][] C = new double[rA][cB];
        for (int i = 0; i < rA; i++) {
            for (int j = 0; j < cB; j++) {
                double s = 0;
                for (int k = 0; k < cA; k++) {
                    s += A[i][k] * B[k][j];
                }
                C[i][j] = s;
            }
        }
        return C;
    }

    private static boolean compareMatrices(double[][] A, double[][] B, double epsilon) {
        if (A.length != B.length || A[0].length != B[0].length) return false;
        for (int i = 0; i < A.length; i++) {
            for (int j = 0; j < A[0].length; j++) {
                if (Math.abs(A[i][j] - B[i][j]) > epsilon) return false;
            }
        }
        return true;
    }

    private static void printMatrix(double[][] M) {
        for (int i = 0; i < Math.min(M.length, 6); i++) {
            System.out.print("  [ ");
            for (int j = 0; j < Math.min(M[0].length, 6); j++) {
                System.out.printf("%6.1f ", M[i][j]);
            }
            if (M[0].length > 6) System.out.print("... ");
            System.out.println("]");
        }
    }
}
