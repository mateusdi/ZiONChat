package org.example;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ChatServer {
    private static final int PORT = 5555;
    private static final String SECRET_KEY = "TheMatrixHasYou!";
    private static final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private static final Set<String> banList = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) {
        new Thread(ChatServer::listenConsole).start();
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("\n[SISTEMA] MAINFRAME ZION OPERACIONAL");
            System.out.println("[INFO] Escutando na porta: " + PORT + "\n");
            while (true) {
                Socket socket = server.accept();
                new Thread(new ClientHandler(socket)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    // Console do Servidor
    private static void listenConsole() {
        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) {
            String input = sc.nextLine().trim();
            if (input.isEmpty()) continue;
            processCommand("CONSOLE", input, true);
        }
    }

    // Lógica Central de Comandos (Server Console + Chat Admin)
    static void processCommand(String executor, String fullCmd, boolean isServerConsole) {
        String cleanCmd = fullCmd.startsWith("/") ? fullCmd.substring(1) : fullCmd;
        String[] parts = cleanCmd.split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String target = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "list":
                System.out.println("[LOG] Usuários: " + clients.keySet());
                break;
            case "admin":
                if (clients.containsKey(target)) {
                    clients.get(target).setAdmin(true);
                    System.out.println("[LOG] " + executor + " promoveu " + target + " a AGENTE.");
                } else System.out.println("[!] Alvo não encontrado.");
                break;
            case "deadmin":
                if (clients.containsKey(target)) {
                    clients.get(target).setAdmin(false);
                    System.out.println("[LOG] " + executor + " removeu privilégios de " + target + ".");
                }
                break;
            case "kick":
                if (clients.containsKey(target)) {
                    clients.get(target).disconnect("TERMINADO PELO SISTEMA.");
                    System.out.println("[LOG] " + executor + " kickou " + target + ".");
                }
                break;
            case "mute":
                if (clients.containsKey(target)) {
                    boolean m = clients.get(target).toggleMute();
                    System.out.println("[LOG] " + executor + (m ? " silenciou " : " desmutou ") + target + ".");

                    // MENSAGEM PARA O ALVO
                    if (m) {
                        clients.get(target).send("SYS|VOCÊ FOI SILENCIADO PELO ARQUITETO.");
                    } else {
                        clients.get(target).send("SYS|SUA VOZ FOI RESTAURADA.");
                    }
                }
                break;
            case "ban":
                if (clients.containsKey(target)) {
                    String ip = clients.get(target).socket.getInetAddress().getHostAddress();
                    banList.add(ip);
                    clients.get(target).disconnect("CONEXÃO BANIDA.");
                    System.out.println("[ALERTA] " + executor + " BANIU o IP: " + ip + " (" + target + ")");
                }
                break;
            default:
                if (isServerConsole) System.out.println("[?] Comandos: list, admin, deadmin, kick, mute, ban [nome]");
        }
    }

    static synchronized void broadcast(String m) { clients.values().forEach(c -> c.send(m)); }
    static synchronized void updateUsers() {
        StringBuilder sb = new StringBuilder("USERS|");
        clients.keySet().forEach(u -> sb.append(u).append(","));
        broadcast(sb.toString());
    }

    static class ClientHandler implements Runnable {
        Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String name;
        private boolean isAdmin = false;
        private boolean isMuted = false;

        ClientHandler(Socket s) { this.socket = s; }

        public void setAdmin(boolean state) {
            this.isAdmin = state;
            send(state ? "SYS|ARCHITECT_MODE_ON" : "SYS|ARCHITECT_MODE_OFF");
        }

        public boolean toggleMute() { this.isMuted = !this.isMuted; return this.isMuted; }

        public void disconnect(String msg) { send("SYS|" + msg); try { socket.close(); } catch (Exception e) {} }

        void send(String m) { if (out != null) out.println(MatrixCrypt.encrypt(m)); }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                String ip = socket.getInetAddress().getHostAddress();

                if (banList.contains(ip)) { disconnect("IP BANIDO."); return; }

                String line;
                while ((line = in.readLine()) != null) {
                    String dec = MatrixCrypt.decrypt(line).trim();

                    if (dec.startsWith("JOIN|")) {
                        this.name = dec.substring(5).trim();
                        if (clients.containsKey(name)) { send("SYS|ERROR_NAME_TAKEN"); continue; }
                        clients.put(name, this);
                        send("SYS|JOIN_SUCCESS");
                        if (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1")) setAdmin(true);
                        updateUsers();
                        broadcast("SYS|[+] " + name + (isAdmin ? " (AGENTE)" : " conectado."));
                        System.out.println("[CONN] " + name + " entrou via " + ip);
                    }
                    else if (dec.startsWith("/")) {
                        if (dec.equalsIgnoreCase("/help") || dec.equalsIgnoreCase("/ajuda")) {
                            send("SYS|COMANDOS AGENTE: /kick [nome], /mute [nome], /ban [nome], /list");
                        } else if (isAdmin) {
                            processCommand(name, dec, false);
                        } else {
                            send("SYS|ERRO: Você não é um Agente.");
                        }
                    }
                    else if (dec.startsWith("MSG|")) {
                        if (!isMuted) broadcast("MSG|" + name + "|" + dec.substring(4));
                        else send("SYS|Você está em silêncio.");
                    }
                }
            } catch (Exception e) {
            } finally {
                if (name != null) { clients.remove(name); updateUsers(); broadcast("SYS|[-] " + name + " saiu."); }
            }
        }
    }

    static class MatrixCrypt {
        public static String encrypt(String d) {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SECRET_KEY.getBytes(), "AES"), new GCMParameterSpec(128, new byte[12]));
                return Base64.getEncoder().encodeToString(c.doFinal(d.getBytes()));
            } catch (Exception e) { return ""; }
        }
        public static String decrypt(String d) {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SECRET_KEY.getBytes(), "AES"), new GCMParameterSpec(128, new byte[12]));
                return new String(c.doFinal(Base64.getDecoder().decode(d)));
            } catch (Exception e) { return "ERR"; }
        }
    }
}