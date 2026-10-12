module MatrixCompute {

    // Secuencias para matrices de numeros flotantes de doble precision
    sequence<double> DoubleSeq;
    sequence<DoubleSeq> MatrixData;

    // Estructura con el resultado del calculo de un bloque de filas
    struct BlockResult {
        int startRow;
        MatrixData computedRows;
        long computeTimeMs;
        string workerId;
    };

    // Excepcion para validaciones de negocio o red
    exception MatrixException {
        string reason;
    };

    // Servicio provisto por cada Worker (invocado concurrentemente por el Master)
    interface WorkerService {
        // Multiplica un bloque de filas de A por la matriz B completa
        BlockResult computeBlock(int startRow, MatrixData rowsA, MatrixData matrixB) throws MatrixException;

        // Identificador y estado del Worker
        idempotent string getWorkerInfo();
    };

    // Servicio provisto por el Master (invocado por el Client)
    interface MasterService {
        // Multiplicacion distribuida orquestada por el Master
        MatrixData multiply(MatrixData matrixA, MatrixData matrixB) throws MatrixException;

        // Registro de Worker (el worker se da de alta en el Master)
        void registerWorker(string workerId, string endpoint);

        // Desregistro de Worker al finalizar
        void unregisterWorker(string workerId);

        // Consulta de cantidad de workers activos
        idempotent int getWorkerCount();
    };

};
