package org.example;


import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ChatServer {
    private static int PORT = 5555;
    private static final String SECRET_KEY = "TheMatrixHasYou!";
    private static final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private static final Set<String> banList = ConcurrentHashMap.newKeySet();
    private static final Set<String> adminIPs = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> lastFahTime = new ConcurrentHashMap<>();

    private static final String BANS_FILE = "bans.txt";
    private static final String ADMINS_FILE = "admins.txt";

    public static void main(String[] args) {
        loadConfig();
        loadList(BANS_FILE, banList);
        loadList(ADMINS_FILE, adminIPs);

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

    private static void loadConfig() {
        File f = new File("server_config.txt");
        if (f.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                PORT = Integer.parseInt(br.readLine().trim());
            } catch (Exception e) {
                System.out.println("[!] Erro ao ler server_config.txt. Usando porta 5555.");
            }
        } else {
            try (PrintWriter pw = new PrintWriter(f)) {
                pw.println(PORT);
            } catch (Exception e) {}
        }
    }

    private static void loadList(String fileName, Set<String> targetSet) {
        File f = new File(fileName);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) targetSet.add(line.trim());
            }
        } catch (IOException e) { System.out.println("[!] Erro ao carregar " + fileName); }
    }

    private static void saveList(String fileName, Set<String> sourceSet) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(fileName))) {
            sourceSet.forEach(pw::println);
        } catch (IOException e) { System.out.println("[!] Erro ao salvar " + fileName); }
    }

    private static void listenConsole() {
        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) {
            String input = sc.nextLine().trim();
            if (input.isEmpty()) continue;
            if (input.startsWith("/say ")) {
                broadcast("SYS|[MAINFRAME] " + input.substring(5));
            } else {
                processCommand("CONSOLE", input, true);
            }
        }
    }

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
                    String ip = clients.get(target).socket.getInetAddress().getHostAddress();
                    adminIPs.add(ip);
                    saveList(ADMINS_FILE, adminIPs);
                    clients.get(target).setAdmin(true);
                    System.out.println("[LOG] " + executor + " promoveu " + target + " (IP: " + ip + ")");
                } else System.out.println("[!] Alvo não encontrado.");
                break;
            case "deadmin":
                if (clients.containsKey(target)) {
                    String ip = clients.get(target).socket.getInetAddress().getHostAddress();
                    adminIPs.remove(ip);
                    saveList(ADMINS_FILE, adminIPs);
                    clients.get(target).setAdmin(false);
                    System.out.println("[LOG] " + executor + " removeu privilégios de " + target);
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
                    clients.get(target).send(m ? "SYS|VOCÊ FOI SILENCIADO PELO ARQUITETO." : "SYS|SUA VOZ FOI RESTAURADA.");
                }
                break;
            case "ban":
                if (clients.containsKey(target)) {
                    String ip = clients.get(target).socket.getInetAddress().getHostAddress();
                    banList.add(ip);
                    saveList(BANS_FILE, banList);
                    clients.get(target).disconnect("CONEXÃO BANIDA.");
                    System.out.println("[ALERTA] " + executor + " BANIU o IP: " + ip + " (" + target + ")");
                }
                break;
            case "unban":
                if (!target.isEmpty()) {
                    banList.remove(target);
                    saveList(BANS_FILE, banList);
                    System.out.println("[LOG] " + executor + " removeu o ban do IP: " + target);
                }
                break;
            default:
                if (isServerConsole) System.out.println("[?] list, admin, deadmin, kick, mute, ban [nome], unban [ip], /say");
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
        private long lastMsgTime = 0;

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

                if (clients.values().stream().anyMatch(c -> c.socket.getInetAddress().getHostAddress().equals(ip))) {
                    disconnect("DUPLICIDADE DE IP."); return;
                }

                String line;
                while ((line = in.readLine()) != null) {
                    String dec = MatrixCrypt.decrypt(line).trim();

                    if (dec.length() > 1000) {
                        send("SYS|Mensagem rejeitada: Tamanho excede o limite.");
                        continue;
                    }

                    if (dec.startsWith("JOIN|")) {
                        this.name = dec.substring(5).trim();
                        if (clients.containsKey(name)) { send("SYS|ERROR_NAME_TAKEN"); continue; }
                        clients.put(name, this);
                        send("SYS|JOIN_SUCCESS");

                        if (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || adminIPs.contains(ip)) {
                            setAdmin(true);
                        }

                        updateUsers();
                        broadcast("SYS|[+] " + name + (isAdmin ? " (AGENTE)" : ""));
                        //broadcast("AUDIO|JOIN");
                        System.out.println("[CONN] " + name + " via " + ip);
                    }
                    else if (dec.startsWith("/")) {
                        String[] parts = dec.split(" ", 2);
                        String cmdOriginal = parts[0].substring(1);
                        String cmdLower = cmdOriginal.toLowerCase();

                        if (cmdLower.startsWith("fah")) {
                            long now = System.currentTimeMillis();
                            if (now - lastFahTime.getOrDefault(name, 0L) < 5000) {
                                send("SYS|Aguarde 5s para usar o /fah novamente.");
                                continue;
                            }
                            lastFahTime.put(name, now);
                            String target = parts.length > 1 ? parts[1].trim() : "";
                            if (target.isEmpty()) {
                                broadcast("SYS|FAH");
                                broadcast("AUDIO|FAH");
                            } else if (clients.containsKey(target)) {
                                clients.get(target).send("SYS|FAH");
                                clients.get(target).send("AUDIO|FAH");
                                send("SYS|Atenção chamada em " + target);
                            } else send("SYS|Usuário não encontrado.");
                            continue;
                        }

                        if (cmdLower.equals("help") || cmdLower.equals("ajuda")) {
                            send("SYS|COMANDOS AGENTE: /kick, /mute, /ban, /list, /unban [ip]");
                            send("SYS|COMANDOS GERAIS: /fah [nome], /som (liga/desliga efeitos sonoros), /[qualquer_som_do_txt]");
                            continue;
                        }

                        // Se o comando for o nome de um usuário online (mensagem privada)
                        if (clients.containsKey(cmdOriginal)) {
                            if (parts.length > 1) {
                                clients.get(cmdOriginal).send("PV|" + name + "|" + parts[1]);
                                send("PV|Para " + cmdOriginal + "|" + parts[1]);
                            } else {
                                send("SYS|ERRO: Mensagem privada vazia. Uso correto: /usuario texto_da_mensagem");
                            }
                            continue;
                        }

                        // Se for um comando de administração rodado por um Agente
                        if (isAdmin && (cmdLower.equals("kick") || cmdLower.equals("mute") || cmdLower.equals("ban") || cmdLower.equals("unban") || cmdLower.equals("admin") || cmdLower.equals("deadmin") || cmdLower.equals("list"))) {
                            processCommand(name, dec, false);
                            continue;
                        }

                        // ============================================================
                        // GATILHO 100% DINÂMICO PARA OS SONS DO SEU ARQUIVO TXT
                        // ============================================================
                        // Qualquer coisa que não caiu nas regras acima vira um comando de som!
                        // O servidor avisa o chat e dispara a reprodução da chave em CAIXA ALTA.
                        broadcast("SYS| * " + name + " usou o efeito: /" + cmdLower);
                        broadcast("AUDIO|" + cmdOriginal.toUpperCase());
                    }
                    else if (dec.startsWith("MSG|")) {
                        long now = System.currentTimeMillis();
                        if (now - lastMsgTime < 500) {
                            send("SYS|Anti-Spam: Você está indo rápido demais.");
                            continue;
                        }
                        lastMsgTime = now;
                        if (!isMuted) {
                            broadcast("MSG|" + name + "|" + dec.substring(4));
                        } else {
                            send("SYS|Você está em silêncio.");
                        }
                    }
                }
            } catch (Exception e) {
            } finally {
                if (name != null) {
                    clients.remove(name);
                    updateUsers();
                    broadcast("SYS|[-] " + name);
                    //broadcast("AUDIO|LEAVE");
                }
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