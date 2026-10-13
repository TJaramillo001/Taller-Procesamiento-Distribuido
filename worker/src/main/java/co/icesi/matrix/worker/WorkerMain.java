package co.icesi.matrix.worker;

import MatrixCompute.MasterServicePrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;

/**
 * WorkerMain
 * 
 * Punto de entrada del nodo Trabajador (Worker).
 * Inicia el Communicator de Ice, publica su ObjectAdapter en un puerto específico
 * y se registra automáticamente ante el Master para incorporarse al clúster.
 */
public class WorkerMain {

    public static void main(String[] args) {
        int workerPort = 10001;
        String workerId = "Worker-" + workerPort;
        String masterHost = "localhost";
        int masterPort = 10000;

        // Permite configurar puerto, ID, master y (opcional) la IP que este worker anuncia:
        // ./gradlew :worker:runWorker --args="10002 Worker-2 <IP_MASTER> 10000 [IP_WORKER]"
        if (args.length >= 1) {
            try {
                workerPort = Integer.parseInt(args[0]);
                workerId = "Worker-" + workerPort;
            } catch (NumberFormatException ignored) {}
        }
        if (args.length >= 2) {
            workerId = args[1];
        }
        if (args.length >= 4) {
            masterHost = args[2];
            try {
                masterPort = Integer.parseInt(args[3]);
            } catch (NumberFormatException ignored) {}
        }

        // IP con la que el Master podrá contactar a este worker (no puede ser "localhost" si el Master
        // está en otra máquina). Si no se pasa como 5to argumento, se pregunta al SO qué interfaz
        // local usaría para llegar al Master (connect UDP no envía paquetes, solo consulta la ruta).
        String workerHost = null;
        if (args.length >= 5) {
            workerHost = args[4];
        } else {
            try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
                s.connect(java.net.InetAddress.getByName(masterHost), masterPort);
                workerHost = s.getLocalAddress().getHostAddress();
            } catch (Exception e) {
                System.err.println("[WARN] No se pudo detectar la IP local: " + e.getMessage());
            }
        }
        if (workerHost == null || workerHost.equals("0.0.0.0")) {
            workerHost = "localhost";
        }
        System.out.println("[WORKER] IP anunciada al Master: " + workerHost);

        System.out.println("=============================================================");
        System.out.printf("  INICIANDO WORKER [%s] EN PUERTO %d%n", workerId, workerPort);
        System.out.println("=============================================================");

        // Ice rechaza por defecto mensajes de mas de 1 MB (Ice.MessageSizeMax = 1024 KB).
        // El Worker recibe la matriz B completa (~2.9 MB en 600x600), asi que subimos el limite a 100 MB (valor en KB).
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties();
        initData.properties.setProperty("Ice.MessageSizeMax", "102400");
        // Si el Master es inalcanzable (IP errada o firewall), fallar en 5 s en vez de quedarse colgado
        initData.properties.setProperty("Ice.Override.ConnectTimeout", "5000");

        try (Communicator communicator = Util.initialize(args, initData)) {
            // 1. Crear el adaptador de objetos del Worker
            String endpoint = "tcp -h 0.0.0.0 -p " + workerPort;
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints("WorkerAdapter", endpoint);

            // 2. Instanciar y registrar el Servant
            WorkerServiceI servant = new WorkerServiceI(workerId);
            adapter.add(servant, Util.stringToIdentity("WorkerService"));
            adapter.activate();

            System.out.println("[WORKER] Servicio activo en " + endpoint);

            // 3. Registrarse dinámicamente ante el Master
            String masterEndpoint = String.format("MasterService:tcp -h %s -p %d", masterHost, masterPort);
            try {
                ObjectPrx masterBase = communicator.stringToProxy(masterEndpoint);
                MasterServicePrx masterPrx = MasterServicePrx.checkedCast(masterBase);
                if (masterPrx != null) {
                    // Notificar al master la dirección IP/puerto alcanzable de este worker
                    String workerContactEndpoint = "tcp -h " + workerHost + " -p " + workerPort;
                    masterPrx.registerWorker(workerId, workerContactEndpoint);
                    System.out.printf("[WORKER] Registrado exitosamente en Master (%s)%n", masterEndpoint);
                } else {
                    System.err.println("[WARN] No se pudo verificar la interfaz del Master en " + masterEndpoint);
                }
            } catch (Exception e) {
                System.err.println("[WARN] No fue posible auto-registrarse en el Master: " + e.getMessage());
                System.err.println("       (Asegurese de iniciar el Master primero, o registrese manualmente)");
            }

            // 4. Hook para desregistrarse ordenadamente al presionar Ctrl+C
            final String finalWorkerId = workerId;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("\n[WORKER] Deteniendo worker y notificando al Master...");
                    ObjectPrx mb = communicator.stringToProxy(masterEndpoint);
                    MasterServicePrx mp = MasterServicePrx.checkedCast(mb);
                    if (mp != null) {
                        mp.unregisterWorker(finalWorkerId);
                    }
                } catch (Exception ignored) {}
            }));

            System.out.println("[WORKER] Esperando asignación de tareas... (Presione Ctrl+C para salir)");
            communicator.waitForShutdown();

        } catch (Exception e) {
            System.err.println("[ERROR] Excepcion fatal en Worker: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
