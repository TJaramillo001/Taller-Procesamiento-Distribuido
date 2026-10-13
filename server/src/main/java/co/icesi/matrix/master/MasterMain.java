package co.icesi.matrix.master;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

/**
 * MasterMain
 * 
 * Punto de entrada del nodo Maestro (Server / Master).
 * Expone la interfaz MasterService en el puerto 10000 para atender peticiones
 * de Clientes y gestionar el registro de Workers en red.
 */
public class MasterMain {

    public static void main(String[] args) {
        int masterPort = 10000;

        if (args.length >= 1) {
            try {
                masterPort = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        System.out.println("=============================================================");
        System.out.printf("  INICIANDO NODO MAESTRO (MASTER) EN PUERTO %d%n", masterPort);
        System.out.println("=============================================================");

        // Ice rechaza por defecto mensajes de mas de 1 MB (Ice.MessageSizeMax = 1024 KB).
        // Una matriz 600x600 de double ya ocupa ~2.9 MB, asi que subimos el limite a 100 MB (valor en KB).
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties();
        initData.properties.setProperty("Ice.MessageSizeMax", "102400");
        initData.properties.setProperty("Ice.Override.ConnectTimeout", "5000");

        try (Communicator communicator = Util.initialize(args, initData)) {
            // 1. Crear el adaptador de objetos del Master
            String endpoint = "tcp -h 0.0.0.0 -p " + masterPort;
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints("MasterAdapter", endpoint);

            // 2. Registrar el Servant
            MasterServiceI masterServant = new MasterServiceI();
            adapter.add(masterServant, Util.stringToIdentity("MasterService"));

            // 3. Activar el adaptador para empezar a recibir llamadas RPC
            adapter.activate();

            System.out.println("[MASTER] Servicio listo y escuchando en: " + endpoint);
            System.out.println("[MASTER] Esperando registro de Workers y solicitudes de Clientes...");
            System.out.println("         (Presione Ctrl+C para detener el servidor)\n");

            communicator.waitForShutdown();

        } catch (Exception e) {
            System.err.println("[ERROR] Excepcion fatal en Master: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
