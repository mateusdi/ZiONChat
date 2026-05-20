package org.example;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.LinkedList;
import java.util.Queue;

public class Main extends Application {
    private String host = "10.110.71.48";
    private int port = 5555;
    private static final String SECRET_KEY = "TheMatrixHasYou!";

    private TextArea chat = new TextArea();
    private ListView<String> usersList = new ListView<>();
    private TextField input = new TextField();
    private List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Stage loginStage;
    private Stage mainStage;

    private boolean notificationsEnabled = true;
    private final int fontSize = 16;
    private int[] drops;
    private final String matrixChars = "0123456789ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ";

    private final Map<String, String> soundMap = new HashMap<>();
    private final Queue<String> soundQueue = new LinkedList<>();
    private boolean isPlayingSound = false;
    private boolean isReconnecting = false;

    private MediaPlayer mediaPlayer;

    @Override
    public void start(Stage stage) {
        this.mainStage = stage;
        loadConfig();
        loadSoundConfig();
        setupMainUI(stage);

        stage.setOnCloseRequest(e -> {
            cleanUpAndExit();
        });

        startConnectionThread();
    }

    private void cleanUpAndExit() {
        try { if (socket != null) socket.close(); } catch (IOException ex) {}
        if (mediaPlayer != null) mediaPlayer.dispose();
        Platform.exit();
        System.exit(0);
    }

    private void startConnectionThread() {
        new Thread(() -> {
            while (true) {
                if (connectToServer()) {
                    isReconnecting = false;
                    Platform.runLater(() -> showLoginWindow(mainStage));
                    break;
                } else {
                    if (!isReconnecting) {
                        isReconnecting = true;
                        Platform.runLater(() -> chat.appendText("[!] Link com a Zion rompido. Tentando reconexão em segundo plano...\n"));
                    }
                    try { Thread.sleep(5000); } catch (InterruptedException e) {}
                }
            }
        }).start();
    }

    private void loadConfig() {
        try {
            File f = new File("config.txt");
            if (!f.exists()) {
                Files.write(Paths.get("config.txt"), "10.110.71.48:5555".getBytes());
            }
            String content = new String(Files.readAllBytes(Paths.get("config.txt"))).trim();
            String[] parts = content.split(":");
            this.host = parts[0];
            this.port = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            System.err.println("Erro ao carregar config.txt, usando padrões.");
        }
    }

    private void loadSoundConfig() {
        File f = new File("sounds.txt");
        if (!f.exists()) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(f))) {
                writer.println("JOIN=sounds/join.mp3");
                writer.println("LEAVE=sounds/leave.mp3");
                writer.println("MAINFRAME=sounds/mainframe.mp3");
                writer.println("MSG=sounds/msg.mp3");
                writer.println("FAH=sounds/fah.mp3");
            } catch (IOException e) {
                System.err.println("Erro ao criar mapa padrão de sons.");
            }
        }

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            soundMap.clear();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    soundMap.put(parts[0].trim().toUpperCase(), parts[1].trim());
                }
            }
        } catch (Exception e) {
            System.err.println("Erro ao carregar mapa dinâmico de sons.");
        }
    }

    private String getLastUser() {
        try {
            File f = new File("lastuser.txt");
            if (f.exists()) return new String(Files.readAllBytes(Paths.get("lastuser.txt"))).trim();
        } catch (Exception e) {}
        return "User_" + new Random().nextInt(999);
    }

    private void saveLastUser(String name) {
        try { Files.write(Paths.get("lastuser.txt"), name.getBytes()); } catch (Exception e) {}
    }

    private void setupMainUI(Stage stage) {
        applyMatrixStyle("#003300");
        chat.setEditable(false);
        Canvas canvas = new Canvas(800, 500);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        drops = new int[(int) (800 / fontSize) + 5];
        Arrays.fill(drops, 1);

        new AnimationTimer() {
            private long lastUpdate = 0;
            @Override
            public void handle(long now) {
                if (now - lastUpdate >= 50_000_000) {
                    drawMatrixEffect(gc, canvas.getWidth(), canvas.getHeight());
                    lastUpdate = now;
                }
            }
        }.start();

        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB) { handleTabComplete(); e.consume(); }
            else if (e.getCode() == KeyCode.UP) { navigateHistory(true); }
            else if (e.getCode() == KeyCode.DOWN) { navigateHistory(false); }
        });

        input.setOnAction(e -> {
            String t = input.getText().trim();
            if (t.isEmpty()) return;

            if (t.equalsIgnoreCase("/notif") || t.equalsIgnoreCase("/som")) {
                notificationsEnabled = !notificationsEnabled;
                chat.appendText("[SISTEMA] Efeitos Sonoros: " + (notificationsEnabled ? "ON" : "OFF") + "\n");
                input.clear();
                return;
            }

            if (t.equalsIgnoreCase("/clean")) {
                chat.clear();
                input.clear();
                return;
            }

            history.add(t);
            historyIndex = -1;
            input.clear();
            send(t.startsWith("/") ? t : "MSG|" + t);
        });

        StackPane rootStack = new StackPane();
        BorderPane uiOverlay = new BorderPane();
        uiOverlay.setPadding(new Insets(15));
        VBox centerBox = new VBox(10);
        VBox.setVgrow(chat, Priority.ALWAYS);
        centerBox.getChildren().addAll(chat, input);
        uiOverlay.setCenter(centerBox);
        VBox side = new VBox(5, new Label(" ONLINE"), usersList);
        side.setPadding(new Insets(0, 0, 0, 10));
        uiOverlay.setRight(side);
        rootStack.getChildren().addAll(canvas, uiOverlay);
        rootStack.setStyle("-fx-background-color: black;");

        canvas.widthProperty().bind(stage.widthProperty());
        canvas.heightProperty().bind(stage.heightProperty());
        stage.setScene(new Scene(rootStack, 800, 500));
        stage.setTitle("ZiON TERMINAL");
        stage.show();
    }

    private void handleTabComplete() {
        String text = input.getText();
        if (text.isEmpty()) return;

        if (text.toLowerCase().startsWith("/fx ")) {
            String[] parts = text.split(" ", 2);
            String currentFxQuery = parts.length > 1 ? parts[1].trim() : "";

            soundMap.keySet().stream()
                    .filter(soundKey -> soundKey.toLowerCase().startsWith(currentFxQuery.toLowerCase()))
                    .findFirst()
                    .ifPresent(match -> {
                        input.setText("/fx " + match.toLowerCase());
                        input.positionCaret(input.getText().length());
                    });
            return;
        }

        String[] words = text.split(" ");
        String lastWord = words[words.length - 1];
        String searchPrefix = lastWord.startsWith("/") ? lastWord.substring(1) : lastWord;
        usersList.getItems().stream()
                .filter(u -> u.toLowerCase().startsWith(searchPrefix.toLowerCase())).findFirst()
                .ifPresent(match -> {
                    String prefix = lastWord.startsWith("/") ? "/" : "";
                    words[words.length - 1] = prefix + match;
                    input.setText(String.join(" ", words) + " ");
                    input.positionCaret(input.getText().length());
                });
    }

    private void navigateHistory(boolean up) {
        if (history.isEmpty()) return;
        if (up) { if (historyIndex < history.size() - 1) historyIndex++; }
        else { if (historyIndex > -1) historyIndex--; }
        if (historyIndex == -1) input.clear();
        else {
            input.setText(history.get(history.size() - 1 - historyIndex));
            input.positionCaret(input.getText().length());
        }
    }

    private void playAlertSound(String soundKey) {
        if (!notificationsEnabled) return;

        String fileName = soundMap.get(soundKey.toUpperCase());
        if (fileName == null) return;

        Platform.runLater(() -> {
            soundQueue.add(fileName);
            processSoundQueue();
        });
    }

    private void processSoundQueue() {
        if (isPlayingSound || soundQueue.isEmpty()) return;

        String nextSound = soundQueue.poll();
        try {
            String mediaUri = null;

            if (nextSound.toLowerCase().startsWith("http://") || nextSound.toLowerCase().startsWith("https://")) {
                mediaUri = nextSound;
            } else {
                File file = new File(nextSound);
                if (file.exists()) {
                    mediaUri = file.toURI().toString();
                }
            }

            if (mediaUri != null) {
                isPlayingSound = true;

                if (mediaPlayer != null) {
                    mediaPlayer.stop();
                    mediaPlayer.dispose();
                    mediaPlayer = null;
                }

                Media media = new Media(mediaUri);
                mediaPlayer = new MediaPlayer(media);

                mediaPlayer.setOnEndOfMedia(() -> {
                    Platform.runLater(() -> {
                        isPlayingSound = false;
                        if (mediaPlayer != null) {
                            mediaPlayer.stop();
                            mediaPlayer.dispose();
                            mediaPlayer = null;
                        }
                        System.gc();
                        processSoundQueue();
                    });
                });

                mediaPlayer.setOnError(() -> {
                    Platform.runLater(() -> {
                        isPlayingSound = false;
                        processSoundQueue();
                    });
                });

                mediaPlayer.play();
            } else {
                processSoundQueue();
            }
        } catch (Exception e) {
            isPlayingSound = false;
            processSoundQueue();
        }
    }

    private void handle(String m) {
        if (m.equals("SYS|JOIN_SUCCESS")) {
            if (loginStage != null) loginStage.close();
            input.requestFocus();
            chat.appendText(">>> BEM-VINDO À REDE ZION.\n");
            chat.appendText("[SISTEMA] Digite /ajuda ou /help para visualizar os comandos operacionais.\n");
        } else if (m.equals("SYS|ARCHITECT_MODE_ON")) {
            chat.appendText(">>> ACESSO DE AGENTE CONFIRMADO.\n");
        } else if (m.equals("SYS|ARCHITECT_MODE_OFF")) {
            applyMatrixStyle("#003300");
            chat.appendText(">>> STATUS: PRIVILÉGIOS REVOGADOS.\n");
        } else if (m.startsWith("SOUND_SYNC|")) {
            String content = m.substring(11);
            soundMap.clear();
            loadSoundConfig();
            if (!content.isEmpty()) {
                String[] pairs = content.split(";");
                for (String pair : pairs) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2) {
                        soundMap.put(parts[0].toUpperCase(), parts[1]);
                    }
                }
            }
        } else if (m.startsWith("AUDIO|")) {
            String soundKey = m.substring(6).trim();
            playAlertSound(soundKey);
        } else if (m.startsWith("USERS|")) {
            usersList.getItems().clear();
            for (String u : m.substring(6).split(",")) if(!u.isEmpty()) usersList.getItems().add(u);
        } else if (m.startsWith("PV|")) {
            String[] p = m.split("\\|", 3);
            chat.appendText("[PRIVADO] <" + p[1] + "> " + p[2] + "\n");
        } else if (m.startsWith("MSG|")) {
            String[] p = m.split("\\|", 3);
            chat.appendText("<" + p[1] + "> " + p[2] + "\n");
            playAlertSound("MSG");
        } else if (m.startsWith("SYS|")) {
            chat.appendText("[SYS] " + m.substring(4) + "\n");

            if (m.contains("[+]")) playAlertSound("JOIN");
            else if (m.contains("[-]")) playAlertSound("LEAVE");
                // GATILHO ADICIONADO AQUI: Toca o alarme sempre que houver [MAINFRAME] ou [ALERTA] do /restart
            else if (m.contains("[MAINFRAME]") || m.contains("[ALERTA]")) playAlertSound("MAINFRAME");
        }
        else if (m.startsWith("FX|")) {

            String effect = m.substring(3);

            switch (effect) {

                case "SHAKE":
                    playAlertSound("TREMER");
                    shakeWindow();
                    break;
            }
        }

        else if (m.startsWith("PPT_INVITE|")) {

            String challenger = m.substring(11);

            showPPTInvite(challenger);
        }

        else if (m.startsWith("PPT_START|")) {

            String opponent = m.substring(10);

            openPPTGame(opponent);
        }

        else if (m.startsWith("PPT_RESULT|")) {

            String result = m.substring(11);

            showPopup("RESULTADO", result);
        }
    }




    private void showPPTInvite(String challenger) {

        Platform.runLater(() -> {

            Stage inviteStage = new Stage();

            inviteStage.setAlwaysOnTop(true);

            VBox root = new VBox(20);

            root.setAlignment(Pos.CENTER);

            root.setPadding(new Insets(20));

            root.setStyle(
                    "-fx-background-color: black;" +
                            "-fx-border-color: #00ff00;" +
                            "-fx-border-width: 2;"
            );

            Label title = new Label(
                    challenger + " desafiou você!"
            );

            title.setStyle(
                    "-fx-text-fill: #00ff00;" +
                            "-fx-font-size: 18;" +
                            "-fx-font-family: Consolas;"
            );

            Button accept = new Button("ACEITAR");

            Button decline = new Button("RECUSAR");

            accept.setOnAction(e -> {

                send("PPT_ACCEPT|" + challenger);

                inviteStage.close();
            });

            decline.setOnAction(e -> {

                send("PPT_DECLINE|" + challenger);

                inviteStage.close();
            });

            HBox buttons = new HBox(15, accept, decline);

            buttons.setAlignment(Pos.CENTER);

            root.getChildren().addAll(
                    title,
                    buttons
            );

            Scene scene = new Scene(root, 350, 180);

            inviteStage.setScene(scene);

            inviteStage.setTitle("PEDRA PAPEL TESOURA");

            inviteStage.show();
        });
    }

    private void openPPTGame(String opponent) {

        Platform.runLater(() -> {

            Stage gameStage = new Stage();

            VBox root = new VBox(20);

            root.setAlignment(Pos.CENTER);

            root.setPadding(new Insets(20));

            root.setStyle(
                    "-fx-background-color: black;" +
                            "-fx-border-color: #00ff00;"
            );

            Label title = new Label(
                    "PARTIDA CONTRA " + opponent
            );

            title.setStyle(
                    "-fx-text-fill: #00ff00;" +
                            "-fx-font-size: 20;"
            );

            Button pedra = new Button("🪨 PEDRA");

            Button papel = new Button("📄 PAPEL");

            Button tesoura = new Button("✂ TESOURA");

            pedra.setOnAction(e -> {
                send("PPT_MOVE|pedra");
                gameStage.close();
            });

            papel.setOnAction(e -> {
                send("PPT_MOVE|papel");
                gameStage.close();
            });

            tesoura.setOnAction(e -> {
                send("PPT_MOVE|tesoura");
                gameStage.close();
            });

            VBox buttons = new VBox(
                    10,
                    pedra,
                    papel,
                    tesoura
            );

            buttons.setAlignment(Pos.CENTER);

            root.getChildren().addAll(
                    title,
                    buttons
            );

            Scene scene =
                    new Scene(root, 300, 300);

            gameStage.setScene(scene);

            gameStage.show();
        });
    }

    private void showPopup(String from, String message) {

        Platform.runLater(() -> {

            Stage popup = new Stage();

            popup.initOwner(mainStage);

            popup.setAlwaysOnTop(true);

            VBox root = new VBox(15);

            root.setPadding(new Insets(20));

            root.setAlignment(Pos.CENTER);

            root.setStyle(
                    "-fx-background-color: black;" +
                            "-fx-border-color: #00ff00;" +
                            "-fx-border-width: 2;"
            );

            Label title = new Label(from);

            title.setStyle(
                    "-fx-text-fill: #00ff00;" +
                            "-fx-font-size: 18;" +
                            "-fx-font-family: Consolas;"
            );

            Label text = new Label(message);

            text.setWrapText(true);

            text.setStyle(
                    "-fx-text-fill: #ccffcc;" +
                            "-fx-font-size: 15;" +
                            "-fx-font-family: Consolas;"
            );

            Button ok = new Button("OK");

            ok.setOnAction(e -> popup.close());

            root.getChildren().addAll(title, text, ok);

            Scene scene = new Scene(root, 350, 180);

            popup.setScene(scene);

            popup.setTitle("TRANSMISSÃO");

            popup.show();
        });
    }

    private void shakeWindow() {

        double originalX = mainStage.getX();
        double originalY = mainStage.getY();

        new Thread(() -> {

            try {

                for (int i = 0; i < 30; i++) {

                    double offsetX = (Math.random() * 20) - 10;
                    double offsetY = (Math.random() * 20) - 10;

                    Platform.runLater(() -> {
                        mainStage.setX(originalX + offsetX);
                        mainStage.setY(originalY + offsetY);
                    });

                    Thread.sleep(20);
                }

            } catch (Exception ignored) {}

            Platform.runLater(() -> {
                mainStage.setX(originalX);
                mainStage.setY(originalY);
            });

        }).start();
    }

    private void drawMatrixEffect(GraphicsContext gc, double w, double h) {
        gc.setFill(Color.color(0, 0, 0, 0.15));
        gc.fillRect(0, 0, w, h);
        gc.setFont(new Font("Consolas", fontSize));
        Random rand = new Random();
        for (int i = 0; i < drops.length; i++) {
            char text = matrixChars.charAt(rand.nextInt(matrixChars.length()));
            gc.setFill(Color.web("#00ff00"));
            gc.fillText(String.valueOf(text), i * fontSize, (drops[i] - 1) * fontSize);
            gc.setFill(Color.web("#ccffcc"));
            gc.fillText(String.valueOf(text), i * fontSize, drops[i] * fontSize);
            if (drops[i] * fontSize > h && Math.random() > 0.975) drops[i] = 0;
            drops[i]++;
        }
    }

    private void applyMatrixStyle(String bc) {
        style(chat, bc); style(usersList, bc); style(input, bc);
    }

    private void style(Control c, String bc) {
        // MUDANÇA AQUI: Fundo preto sólido fixo aplicado para não reagir mais ao ganho/perda de foco
        c.setStyle("-fx-control-inner-background: #000000; -fx-background-color: #000000; -fx-text-fill: #00ff00; -fx-font-family: 'Consolas'; " +
                "-fx-border-color: " + bc + "; -fx-border-width: 2; -fx-padding: 5; -fx-background-insets: 0; -fx-background-radius: 0;");
    }

    private boolean connectToServer() {
        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            new Thread(() -> {
                try {
                    String l;
                    while ((l = in.readLine()) != null) {
                        String d = decrypt(l);
                        Platform.runLater(() -> handle(d));
                    }
                } catch (Exception e) {}
                finally {
                    Platform.runLater(() -> {
                        usersList.getItems().clear();
                        if (loginStage != null && loginStage.isShowing()) loginStage.close();
                    });
                    startConnectionThread();
                }
            }).start();
            return true;
        } catch (Exception e) { return false; }
    }

    private void showLoginWindow(Stage owner) {
        if (loginStage != null && loginStage.isShowing()) return;

        loginStage = new Stage();
        loginStage.initModality(Modality.APPLICATION_MODAL);
        loginStage.initOwner(owner);
        VBox layout = new VBox(10); layout.setAlignment(Pos.CENTER); layout.setPadding(new Insets(20));
        layout.setStyle("-fx-background-color: black; -fx-border-color: #00ff00;");

        TextField nf = new TextField(getLastUser());
        nf.setStyle("-fx-background-color: #001100; -fx-text-fill: #00ff00; -fx-font-family: 'Consolas';");

        Button b = new Button("CONECTAR");
        Runnable loginAction = () -> {
            String name = nf.getText().trim();
            if(!name.isEmpty()){
                saveLastUser(name);
                send("JOIN|"+name);
                b.setDisable(true);
                nf.setDisable(true);
            }
        };
        b.setOnAction(e -> loginAction.run());
        nf.setOnAction(e -> loginAction.run());

        layout.getChildren().addAll(new Label("CODINOME:"), nf, b);
        loginStage.setScene(new Scene(layout, 300, 150));

        loginStage.setOnCloseRequest(ev -> {
            try { if (socket != null) socket.close(); } catch(Exception ex){}
        });

        loginStage.show();
    }

    private void send(String m) { if(out != null) out.println(encrypt(m)); }
    private String encrypt(String d) { try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SECRET_KEY.getBytes(), "AES"), new GCMParameterSpec(128, new byte[12])); return Base64.getEncoder().encodeToString(c.doFinal(d.getBytes())); } catch (Exception e) { return ""; } }
    private String decrypt(String d) { try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SECRET_KEY.getBytes(), "AES"), new GCMParameterSpec(128, new byte[12])); return new String(c.doFinal(Base64.getDecoder().decode(d))); } catch (Exception e) { return "ERR"; } }
    public static void main(String[] args) { launch(args); }
}