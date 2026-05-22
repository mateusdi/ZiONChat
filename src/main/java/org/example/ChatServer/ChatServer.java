package org.example.ChatServer;
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

    private static final Map<String, Long> lastFxTime = new ConcurrentHashMap<>();
    private static final long FX_COOLDOWN_MS = 5000;

    private static final Map<String, String> serverSounds = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastShakeTime = new ConcurrentHashMap<>();

    private static final String BANS_FILE = "bans.txt";
    private static final String ADMINS_FILE = "admins.txt";
    private static final String SOUNDS_FILE = "server_sounds.txt";

    private static final List<PPTGame> games = Collections.synchronizedList(new ArrayList<>());

    private static final List<PongGame> pongGames = Collections.synchronizedList(new ArrayList<>());

    private static final List<BombermanLobby> bombermanLobbies = Collections.synchronizedList(new ArrayList<>());
    private static final List<BombermanGame> bombermanGames = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        loadConfig();
        loadList(BANS_FILE, banList);
        loadList(ADMINS_FILE, adminIPs);
        loadServerSounds();

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

    private static void loadServerSounds() {
        File f = new File(SOUNDS_FILE);
        if (!f.exists()) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(f))) {
                writer.println("JOIN=sounds/join.mp3");
                writer.println("LEAVE=sounds/leave.mp3");
                writer.println("MAINFRAME=sounds/mainframe.mp3");
                writer.println("MSG=sounds/msg.mp3");
                writer.println("FAH=sounds/fah.mp3");
            } catch (IOException e) {}
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            serverSounds.clear();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    serverSounds.put(parts[0].trim().toUpperCase(), parts[1].trim());
                }
            }
            System.out.println("[SISTEMA] " + serverSounds.size() + " mapeamentos de áudio carregados.");
        } catch (Exception e) {
            System.out.println("[!] Erro ao carregar mapeamentos de som no servidor.");
        }
    }

    private static synchronized void saveServerSounds() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(SOUNDS_FILE))) {
            for (Map.Entry<String, String> entry : serverSounds.entrySet()) {
                pw.println(entry.getKey() + "=" + entry.getValue());
            }
        } catch (IOException e) {
            System.out.println("[!] Erro ao salvar lista de sons.");
        }
    }

    private static String buildSoundSyncPayload() {
        StringBuilder sb = new StringBuilder("SOUND_SYNC|");
        for (Map.Entry<String, String> entry : serverSounds.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append(";");
        }
        if (sb.length() > 11) sb.setLength(sb.length() - 1);
        return sb.toString();
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
            // ADICIONADO: Sistema nativo de reinicialização agendada via console do servidor
            case "restart":
                int delaySeconds = 5;
                if (!target.isEmpty()) {
                    try { delaySeconds = Integer.parseInt(target); } catch(Exception e) {}
                }
                final int totalTempo = delaySeconds;
                new Thread(() -> {
                    try {
                        for (int i = totalTempo; i > 0; i--) {
                            broadcast("SYS|[ALERTA] O mainframe do servidor será REINICIADO em " + i + " segundos...");
                            System.out.println("[RESTART] Reiniciando em " + i + "s...");
                            Thread.sleep(1000);
                        }
                        broadcast("SYS|[MAINFRAME] Desconectando link de segurança. Reiniciando...");
                        System.out.println("[SISTEMA] Encerrando processos e reiniciando mainframe.");

                        // Desconecta de forma limpa todos os terminais ativos
                        for (ClientHandler c : clients.values()) {
                            c.disconnect("MAINFRAME REINICIANDO PARA ATUALIZAÇÃO.");
                        }
                        Thread.sleep(500);
                        System.exit(0); // Fecha o processo principal
                    } catch (Exception ex) {}
                }).start();
                break;
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
                if (isServerConsole) System.out.println("[?] restart [tempo_s], list, admin, deadmin, kick, mute, ban [nome], unban [ip], /say");
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

                //if (clients.values().stream().anyMatch(c -> c.socket.getInetAddress().getHostAddress().equals(ip))) {
                //    disconnect("DUPLICIDADE DE IP."); return;
                //}

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
                        send(buildSoundSyncPayload());
                        System.out.println("[CONN] " + name + " via " + ip);
                    }
                    else if (dec.startsWith("/")) {
                        String[] parts = dec.split(" ", 2);
                        String cmdOriginal = parts[0].substring(1);
                        String cmdLower = cmdOriginal.toLowerCase();

                        if (cmdLower.equals("mainframe")) {
                            send("SYS|Acesso negado: /mainframe é um comando exclusivo do console nativo.");
                            continue;
                        }

                        // Mapeia novos efeitos sonoros
                        if (cmdLower.equals("addsound")) {
                            if (!isAdmin) {
                                send("SYS|Acesso negado: Apenas Agentes do Sistema podem cadastrar novos efeitos.");
                                continue;
                            }
                            if (parts.length > 1) {
                                String[] argsSound = parts[1].trim().split(" ", 2);
                                if (argsSound.length == 2) {
                                    String novaChave = argsSound[0].trim().toUpperCase();
                                    String novaUrl = argsSound[1].trim();

                                    serverSounds.put(novaChave, novaUrl);
                                    saveServerSounds();

                                    broadcast("SYS|[NOVO SOM] O Agente " + name + " mapeou o comando /fx " + novaChave.toLowerCase());
                                    broadcast(buildSoundSyncPayload());
                                } else {
                                    send("SYS|Uso incorreto. Exemplo: /addsound NOME https://link.com/audio.mp3");
                                }
                            } else {
                                send("SYS|Uso incorreto. Exemplo: /addsound NOME https://link.com/audio.mp3");
                            }
                            continue;
                        }

                        // ADICIONADO: /editfx [nome] [nova_url] para atualizar sons
                        if (cmdLower.equals("editfx")) {
                            if (!isAdmin) {
                                send("SYS|Acesso negado: Apenas Agentes do Sistema podem alterar efeitos.");
                                continue;
                            }
                            if (parts.length > 1) {
                                String[] argsEdit = parts[1].trim().split(" ", 2);
                                if (argsEdit.length == 2) {
                                    String chave = argsEdit[0].trim().toUpperCase();
                                    String novaUrl = argsEdit[1].trim();

                                    if (serverSounds.containsKey(chave)) {
                                        serverSounds.put(chave, novaUrl);
                                        saveServerSounds();
                                        broadcast("SYS|[SOMS MODIFICADO] O Agente " + name + " alterou o arquivo de /fx " + chave.toLowerCase());
                                        broadcast(buildSoundSyncPayload());
                                    } else {
                                        send("SYS|Efeito '" + chave.toLowerCase() + "' não existe para ser editado. Use /addsound.");
                                    }
                                } else send("SYS|Uso correto: /editfx NOME NOVA_URL");
                            } else send("SYS|Uso correto: /editfx NOME NOVA_URL");
                            continue;
                        }

                        // ADICIONADO: /delfx [nome] para apagar sons permanentemente
                        if (cmdLower.equals("delfx")) {
                            if (!isAdmin) {
                                send("SYS|Acesso negado: Apenas Agentes do Sistema podem remover efeitos.");
                                continue;
                            }
                            if (parts.length > 1) {
                                String chaveDel = parts[1].trim().toUpperCase();
                                if (serverSounds.containsKey(chaveDel)) {
                                    serverSounds.remove(chaveDel);
                                    saveServerSounds();
                                    broadcast("SYS|[SOM DELETADO] O Agente " + name + " removeu o efeito /fx " + chaveDel.toLowerCase());
                                    broadcast(buildSoundSyncPayload());
                                } else {
                                    send("SYS|Efeito /fx " + chaveDel.toLowerCase() + " não foi encontrado.");
                                }
                            } else send("SYS|Uso correto: /delfx NOME_DO_SOM");
                            continue;
                        }

                        if (cmdLower.equals("fx")) {
                            if (parts.length > 1) {
                                String subCmd = parts[1].trim();
                                String subCmdLower = subCmd.toLowerCase();

                                if (subCmdLower.equals("lista")) {
                                    if (serverSounds.isEmpty()) {
                                        send("SYS|Nenhum efeito sonoro cadastrado no momento.");
                                    } else {
                                        List<String> sortedSounds = new ArrayList<>(serverSounds.keySet());
                                        Collections.sort(sortedSounds);
                                        send("SYS|======= EFEITOS DISPONÍVEIS ========");
                                        for (String soundKey : sortedSounds) {
                                            send("SYS| -> /fx " + soundKey.toLowerCase());
                                        }
                                        send("SYS|=====================================");
                                    }
                                    continue;
                                }

                                long now = System.currentTimeMillis();
                                if (now - lastFxTime.getOrDefault(name, 0L) < FX_COOLDOWN_MS) {
                                    long restando = (FX_COOLDOWN_MS - (now - lastFxTime.get(name))) / 1000;
                                    send("SYS|Anti-Spam FX: Aguarde " + (restando <= 0 ? 1 : restando) + "s para usar o /fx novamente.");
                                    continue;
                                }

                                String chaveCaixaAlta = subCmd.toUpperCase();
                                if (serverSounds.containsKey(chaveCaixaAlta)) {
                                    lastFxTime.put(name, now);
                                    broadcast("SYS| * " + name + " usou o efeito: /fx " + subCmdLower);
                                    broadcast("AUDIO|" + chaveCaixaAlta);
                                } else {
                                    send("SYS|Efeito /fx " + subCmdLower + " não encontrado. Digite /fx lista para ver as opções.");
                                }
                            } else {
                                send("SYS|Uso correto: /fx [nome_do_som] ou digite /fx lista");
                            }
                            continue;
                        }

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

                        if (cmdLower.equals("tremer")) {

                            if (parts.length <= 1) {
                                send("SYS|Uso correto: /tremer nome_do_usuario");
                                continue;
                            }

                            String alvo = parts[1].trim();

                            if (!clients.containsKey(alvo)) {
                                send("SYS|Usuário não encontrado.");
                                continue;
                            }

                            ClientHandler targetClient = clients.get(alvo);

                            long now = System.currentTimeMillis();

                            if (now - lastShakeTime.getOrDefault(name, 0L) < 5000) {
                                send("SYS|Aguarde 5s para usar /tremer novamente.");
                                continue;
                            }

                            lastShakeTime.put(name, now);

                            targetClient.send("FX|SHAKE");

                            send("SYS|Você fez a tela de " + alvo + " tremer.");

                            if (!alvo.equals(name)) {
                                targetClient.send("SYS|[EFEITO] " + name + " fez sua tela tremer.");
                            }

                            continue;
                        }

                        if (cmdLower.equals("pong")) {

                            if (parts.length <= 1) {
                                send("SYS|Uso: /pong usuario");
                                continue;
                            }

                            String alvo = parts[1].trim();

                            if (!clients.containsKey(alvo)) {
                                send("SYS|Usuário não encontrado.");
                                continue;
                            }

                            clients.get(alvo)
                                    .send("PONG_INVITE|" + name);

                            send("SYS|Convite Pong enviado.");

                            continue;
                        }

                        if (cmdLower.equals("ppt")) {

                            if (parts.length <= 1) {
                                send("SYS|Uso: /ppt usuario");
                                continue;
                            }

                            String alvo = parts[1].trim();

                            if (!clients.containsKey(alvo)) {
                                send("SYS|Usuário não encontrado.");
                                continue;
                            }

                            clients.get(alvo)
                                    .send("PPT_INVITE|" + name);

                            send("SYS|Convite enviado.");

                            continue;
                        }

                        // ============================
// /bomberman
// ============================

                        if (cmdLower.equals("bomberman")) {

                            boolean alreadyHosting =
                                    bombermanLobbies.stream()
                                            .anyMatch(l ->
                                                    l.host.equals(name)
                                            );

                            if (alreadyHosting) {

                                send("SYS|Você já possui um lobby aberto.");

                                continue;
                            }

                            BombermanLobby lobby =
                                    new BombermanLobby(name);

                            bombermanLobbies.add(lobby);

                            int invitedCount = 0;

                            for (String username : clients.keySet()) {

                                if (username.equals(name))
                                    continue;

                                if (invitedCount >= 3)
                                    break;

                                lobby.invited.add(username);

                                clients.get(username)
                                        .send("BOMBERMAN_INVITE|" + name);

                                invitedCount++;
                            }

                            send("SYS|Convites enviados para "
                                    + invitedCount
                                    + " jogadores.");

                            startBombermanLobbyTimer(lobby);

                            continue;
                        }

                        // MODIFICADO: Lista de ajuda atualizada com informações detalhadas e novos comandos
                        if (cmdLower.equals("help") || cmdLower.equals("ajuda")) {
                            send("SYS|COMANDOS AGENTE: /kick, /mute, /ban, /list, /unban [ip], /addsound [chave] [url], /editfx [chave] [url], /delfx [chave]");
                            send("SYS|COMANDOS GERAIS: /fah [nome], /som ou /notif (muda som local), /clean (limpa tela), /fx lista, /fx [nome]" +
                                    " /tremer [usuario], /ppt [usuario] (desafia para pedra, papel e tesoura)");
                            send("SYS|MENSAGEM PRIVADA: /[nome_do_usuario] [sua_mensagem_aqui]");
                            continue;
                        }

                        if (clients.containsKey(cmdOriginal)) {
                            if (parts.length > 1) {
                                clients.get(cmdOriginal).send("PV|" + name + "|" + parts[1]);
                                send("PV|Para " + cmdOriginal + "|" + parts[1]);
                            } else {
                                send("SYS|ERRO: Mensagem privada vazia. Uso correto: /usuario texto_da_mensagem");
                            }
                            continue;
                        }

                        if (isAdmin && (cmdLower.equals("kick") || cmdLower.equals("mute") || cmdLower.equals("ban") || cmdLower.equals("unban") || cmdLower.equals("admin") || cmdLower.equals("deadmin") || cmdLower.equals("list"))) {
                            processCommand(name, dec, false);
                            continue;
                        }

                        send("SYS|Comando /" + cmdLower + " desconhecido. Digite /ajuda para ver comandos válidos.");
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

                    else if (dec.startsWith("PPT_ACCEPT|")) {

                        String challenger = dec.substring(11);

                        PPTGame game =
                                new PPTGame(challenger, name);

                        games.add(game);

                        clients.get(challenger)
                                .send("PPT_START|" + name);

                        send("PPT_START|" + challenger);

                        continue;
                    }

                    else if (dec.startsWith("PPT_MOVE|")) {

                        String move = dec.substring(9);

                        PPTGame game = games.stream()
                                .filter(g ->
                                        g.player1.equals(name)
                                                || g.player2.equals(name))
                                .findFirst()
                                .orElse(null);

                        if (game == null) {
                            send("SYS|Partida não encontrada.");
                            continue;
                        }

                        if (name.equals(game.player1)) {
                            game.move1 = move;
                        } else {
                            game.move2 = move;
                        }

                        if (game.move1 != null &&
                                game.move2 != null) {

                            resolveGame(game);
                        }

                        continue;
                    }

                    else if (dec.startsWith("PONG_ACCEPT|")) {

                        String challenger = dec.substring(12);

                        PongGame game =
                                new PongGame(challenger, name);

                        pongGames.add(game);

                        clients.get(challenger)
                                .send("PONG_START|" + name + "|LEFT");

                        send("PONG_START|" + challenger + "|RIGHT");

                        startPongLoop(game);

                        continue;
                    }

                    else if (dec.startsWith("PONG_INPUT|")) {

                        String[] p = dec.split("\\|");

                        String dir = p[1];

                        String action = p[2];

                        PongGame game =
                                pongGames.stream()
                                        .filter(g ->
                                                g.playerLeft.equals(name)
                                                        || g.playerRight.equals(name))
                                        .findFirst()
                                        .orElse(null);

                        if (game == null)
                            continue;

                        boolean pressed =
                                action.equals("PRESS");

                        boolean isLeft =
                                name.equals(game.playerLeft);

                        if (isLeft) {

                            if (dir.equals("UP")) {

                                game.leftUp = pressed;
                            }

                            if (dir.equals("DOWN")) {

                                game.leftDown = pressed;
                            }

                        } else {

                            if (dir.equals("UP")) {

                                game.rightUp = pressed;
                            }

                            if (dir.equals("DOWN")) {

                                game.rightDown = pressed;
                            }
                        }

                        continue;
                    }

                    // ============================
// ACEITAR
// ============================

                    else if (dec.startsWith("BOMBERMAN_ACCEPT|")) {

                        String host =
                                dec.split("\\|")[1];

                        BombermanLobby lobby =
                                bombermanLobbies.stream()
                                        .filter(l ->
                                                l.host.equals(host)
                                        )
                                        .findFirst()
                                        .orElse(null);

                        if (lobby == null) {

                            send("SYS|Lobby não encontrado.");

                            continue;
                        }

                        if (lobby.started) {

                            send("SYS|Partida já iniciada.");

                            continue;
                        }

                        if (!lobby.invited.contains(name)) {

                            send("SYS|Você não foi convidado.");

                            continue;
                        }

                        lobby.accepted.add(name);

                        lobby.declined.remove(name);

                        clients.get(host)
                                .send("SYS|" + name + " entrou no BOMBERMAN.");

                        checkBombermanLobby(lobby);

                        continue;
                    }

                    // ============================
// RECUSAR
// ============================

                    else if (dec.startsWith("BOMBERMAN_DECLINE|")) {

                        String host =
                                dec.split("\\|")[1];

                        BombermanLobby lobby =
                                bombermanLobbies.stream()
                                        .filter(l ->
                                                l.host.equals(host))
                                        .findFirst()
                                        .orElse(null);

                        if (lobby == null)
                            continue;

                        lobby.declined.add(name);

                        lobby.accepted.remove(name);

                        checkBombermanLobby(lobby);

                        continue;
                    }

                    else if (dec.startsWith("BOMBERMAN_COLOR|")) {

                        String color =
                                dec.split("\\|")[1];

                        BombermanGame game =
                                bombermanGames.stream()
                                        .filter(g ->
                                                g.players.stream()
                                                        .anyMatch(p ->
                                                                p.name.equals(name)
                                                        )
                                        )
                                        .findFirst()
                                        .orElse(null);

                        if (game == null)
                            continue;

                        boolean alreadyUsed =
                                game.players.stream()
                                        .anyMatch(p ->
                                                p.color.equals(color)
                                        );

                        if (alreadyUsed) {

                            send("SYS|Cor já escolhida.");

                            continue;
                        }

                        BombermanPlayer player =
                                game.players.stream()
                                        .filter(p ->
                                                p.name.equals(name)
                                        )
                                        .findFirst()
                                        .orElse(null);

                        if (player == null)
                            continue;

                        player.color = color;

                        sendBombermanLobbyState(game);

                        continue;
                    }

                    else if (dec.startsWith("BOMBERMAN_READY")) {

                        BombermanGame game =
                                bombermanGames.stream()
                                        .filter(g ->
                                                g.players.stream()
                                                        .anyMatch(p ->
                                                                p.name.equals(name)
                                                        )
                                        )
                                        .findFirst()
                                        .orElse(null);

                        if (game == null)
                            continue;

                        BombermanPlayer player =
                                game.players.stream()
                                        .filter(p ->
                                                p.name.equals(name)
                                        )
                                        .findFirst()
                                        .orElse(null);

                        if (player == null)
                            continue;

                        player.ready = !player.ready;

                        sendBombermanLobbyState(game);

                        boolean allReady =
                                game.players.stream()
                                        .allMatch(p ->
                                                p.ready
                                        );

                        if (allReady) {

                            startBombermanMatch(game);
                        }

                        continue;
                    }
                }
            } catch (Exception e) {
            } finally {
                if (name != null) {
                    removePlayerFromBomberman(name);
                    clients.remove(name);
                    updateUsers();
                    broadcast("SYS|[-] " + name);
                }
            }
        }
    }

    private static void startBombermanMatch(
            BombermanGame game
    ) {

        game.started = true;

        int[][] spawn = {
                {1,1},
                {13,1},
                {1,11},
                {13,11}
        };

        for (int i = 0; i < game.players.size(); i++) {

            game.players.get(i).x =
                    spawn[i][0];

            game.players.get(i).y =
                    spawn[i][1];
        }

        sendBombermanState(game);
    }

    private static void sendBombermanState(
            BombermanGame game
    ) {

        StringBuilder sb =
                new StringBuilder(
                        "BOMBERMAN_STATE|"
                );

        for (BombermanPlayer p : game.players) {

            sb.append(p.name)
                    .append(",")
                    .append(p.color)
                    .append(",")
                    .append(p.x)
                    .append(",")
                    .append(p.y)
                    .append(";");
        }

        String payload =
                sb.toString();

        for (BombermanPlayer p : game.players) {

            if (clients.containsKey(p.name)) {

                clients.get(p.name)
                        .send(payload);
            }
        }
    }

    // ============================
    // TIMER AUTOMÁTICO
    // ============================

    private static void startBombermanLobbyTimer(BombermanLobby lobby) {

        new Thread(() -> {

            try {

                Thread.sleep(10_000);

            } catch (Exception ignored) {}

            if (lobby.started)
                return;

            for (String invited : lobby.invited) {

                boolean respondeu =
                        lobby.accepted.contains(invited)
                                || lobby.declined.contains(invited);

                if (!respondeu) {

                    lobby.declined.add(invited);

                    if (clients.containsKey(invited)) {

                        clients.get(invited)
                                .send("SYS|Convite expirado.");
                    }
                }
            }

            checkBombermanLobby(lobby);

        }).start();
    }

    // ============================
    // VERIFICAR INÍCIO
    // ============================

    private static void checkBombermanLobby(BombermanLobby lobby) {

        int totalResponses = lobby.accepted.size() + lobby.declined.size();

        if (totalResponses >= lobby.invited.size()) {

            startBomberman(lobby);
        }
    }

    // ============================
    // INICIAR PARTIDA
    // ============================

    private static void startBomberman(
            BombermanLobby lobby
    ) {

        if (lobby.started)
            return;

        lobby.started = true;

        List<String> players =
                new ArrayList<>();

        players.add(lobby.host);

        players.addAll(lobby.accepted);

        if (players.size() < 2) {

            if (clients.containsKey(lobby.host)) {

                clients.get(lobby.host)
                        .send("SYS|Partida cancelada.");
            }

            bombermanLobbies.remove(lobby);

            return;
        }

        BombermanGame game =
                new BombermanGame();

        for (String p : players) {

            game.players.add(
                    new BombermanPlayer(p)
            );
        }

        bombermanGames.add(game);

        sendBombermanLobbyState(game);

        bombermanLobbies.remove(lobby);
    }

    private static void sendBombermanLobbyState(
            BombermanGame game
    ) {

        StringBuilder sb =
                new StringBuilder(
                        "BOMBERMAN_LOBBY|"
                );

        for (BombermanPlayer p : game.players) {

            sb.append(p.name)
                    .append(",")
                    .append(p.color)
                    .append(",")
                    .append(p.ready)
                    .append(";");
        }

        String payload =
                sb.toString();

        for (BombermanPlayer p : game.players) {

            if (clients.containsKey(p.name)) {

                clients.get(p.name)
                        .send(payload);
            }
        }
    }

    // ============================
    // STATUS DA SALA
    // ============================

    private static void broadcastBombermanStatus(BombermanLobby lobby) {

        String msg =
                "SYS|BOMBERMAN Lobby -> "
                        + "Aceitos: "
                        + lobby.accepted.size()
                        + "/3 | "
                        + "Recusados: "
                        + lobby.declined.size();

        if (clients.containsKey(lobby.host)) {

            clients.get(lobby.host)
                    .send(msg);
        }

        for (String p : lobby.accepted) {

            if (clients.containsKey(p)) {

                clients.get(p)
                        .send(msg);
            }
        }
    }

    // ============================
// LIMPEZA AO DESCONECTAR
// ============================

    private static void removePlayerFromBomberman(String player) {

        List<BombermanLobby> toRemove =
                new ArrayList<>();

        for (BombermanLobby lobby : bombermanLobbies) {

            if (lobby.host.equals(player)) {

                for (String invited : lobby.invited) {

                    if (clients.containsKey(invited)) {

                        clients.get(invited)
                                .send("SYS|Lobby encerrado.");
                    }
                }

                toRemove.add(lobby);

                continue;
            }

            if (lobby.accepted.contains(player)) {

                lobby.accepted.remove(player);

                lobby.declined.add(player);

                checkBombermanLobby(lobby);
            }
        }

        bombermanLobbies.removeAll(toRemove);
    }


    private static void startPongLoop(
            PongGame game
    ) {

        new Thread(() -> {

            long lastTime =
                    System.nanoTime();

            while (game.running) {

                long now = System.nanoTime();

                double deltaTime = (now - lastTime) / 1_000_000_000.0;

                lastTime = now;

                game.deltaTime = deltaTime;

                updateGame(game, deltaTime);

                sendState(game);

                try {

                    Thread.sleep(16);

                } catch(Exception e) {}
            }

        }).start();
    }

    private static void updateGame(PongGame g, double dt) {

        // =========================
        // MOVIMENTO DAS RAQUETES
        // =========================

        double paddleSpeed = 450;

        // LEFT PLAYER
        if (g.leftUp) {
            g.leftY -= paddleSpeed * dt;
        }

        if (g.leftDown) {
            g.leftY += paddleSpeed * dt;
        }

        // RIGHT PLAYER
        if (g.rightUp) {
            g.rightY -= paddleSpeed * dt;
        }

        if (g.rightDown) {
            g.rightY += paddleSpeed * dt;
        }

        // =========================
        // LIMITES DAS RAQUETES
        // =========================

        double maxY = 400;

        if (g.leftY < 0) {
            g.leftY = 0;
        }

        if (g.leftY > maxY) {
            g.leftY = maxY;
        }

        if (g.rightY < 0) {
            g.rightY = 0;
        }

        if (g.rightY > maxY) {
            g.rightY = maxY;
        }

        // =========================
        // MOVIMENTO DA BOLA
        // =========================

        g.ballX += g.ballVelX * dt;
        g.ballY += g.ballVelY * dt;

        // =========================
        // COLISÃO TOPO/BAIXO
        // =========================

        if (g.ballY <= 0) {

            g.ballY = 0;

            g.ballVelY *= -1;
        }

        if (g.ballY >= 480) {

            g.ballY = 480;

            g.ballVelY *= -1;
        }

        // =========================
        // COLISÃO ESQUERDA
        // =========================

        if (
                g.ballX <= 30 &&
                        g.ballX >= 10 &&
                        g.ballY + 20 >= g.leftY &&
                        g.ballY <= g.leftY + 100
        ) {

            g.ballX = 30;

            g.ballVelX = Math.abs(g.ballVelX);

            increaseBallSpeed(g);
        }

        // =========================
        // COLISÃO DIREITA
        // =========================

        if (
                g.ballX + 20 >= 770 &&
                        g.ballX + 20 <= 790 &&
                        g.ballY + 20 >= g.rightY &&
                        g.ballY <= g.rightY + 100
        ) {

            g.ballX = 750;

            g.ballVelX = -Math.abs(g.ballVelX);

            increaseBallSpeed(g);
        }

        // =========================
        // PONTO DIREITA
        // =========================

        if (g.ballX < -20) {

            g.scoreRight++;

            resetBall(g);

            return;
        }

        // =========================
        // PONTO ESQUERDA
        // =========================

        if (g.ballX > 820) {

            g.scoreLeft++;

            resetBall(g);

            return;
        }

        // =========================
        // VITÓRIA
        // =========================

        if (g.scoreLeft >= 5 || g.scoreRight >= 5) {

            g.running = false;

            String winner =
                    g.scoreLeft >= 5
                            ? g.playerLeft
                            : g.playerRight;

            if (clients.containsKey(g.playerLeft)) {
                clients.get(g.playerLeft)
                        .send("PONG_END|" + winner);
            }

            if (clients.containsKey(g.playerRight)) {
                clients.get(g.playerRight)
                        .send("PONG_END|" + winner);
            }

            pongGames.remove(g);
        }
    }

    private static void increaseBallSpeed(PongGame g) {
        g.ballVelX *= 1.05;
        g.ballVelY *= 1.05;
    }

    private static void resetBall(PongGame g) {

        g.ballX = 400;
        g.ballY = 250;

        double speed = 350;

        g.ballVelX = Math.random() > 0.5 ? speed : -speed;

        g.ballVelY = Math.random() > 0.5 ? speed : -speed;
    }

    private static void resolveGame(PPTGame game) {

        String result1;
        String result2;

        if (game.move1.equals(game.move2)) {

            result1 = "EMPATE";
            result2 = "EMPATE";

        } else if (
                (game.move1.equals("pedra") &&
                        game.move2.equals("tesoura")) ||

                        (game.move1.equals("papel") &&
                                game.move2.equals("pedra")) ||

                        (game.move1.equals("tesoura") &&
                                game.move2.equals("papel"))
        ) {

            result1 = "VOCÊ VENCEU";
            result2 = "VOCÊ PERDEU";

        } else {

            result1 = "VOCÊ PERDEU";
            result2 = "VOCÊ VENCEU";
        }

        clients.get(game.player1)
                .send("PPT_RESULT|" + result1);

        clients.get(game.player2)
                .send("PPT_RESULT|" + result2);

        games.remove(game);
    }

    private static void sendState(PongGame g) {

        String state =
                "PONG_STATE|"
                        + g.ballX + "|"
                        + g.ballY + "|"
                        + g.leftY + "|"
                        + g.rightY + "|"
                        + g.scoreLeft + "|"
                        + g.scoreRight;

        clients.get(g.playerLeft).send(state);
        clients.get(g.playerRight).send(state);
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