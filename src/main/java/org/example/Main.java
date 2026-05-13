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
import javafx.stage.Modality;
import javafx.stage.Stage;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.util.*;

public class Main extends Application {
    private static final String HOST = "10.110.71.48";
    private static final int PORT = 5555;
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

    private final int fontSize = 16;
    private int[] drops;

    // Caracteres originais: Katakana (receita de sushi) + Números
    private final String matrixChars = "0123456789ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ";

    @Override
    public void start(Stage stage) {
        setupMainUI(stage);
        new Thread(() -> {
            if (connectToServer()) {
                Platform.runLater(() -> showLoginWindow(stage));
            } else {
                Platform.runLater(() -> chat.appendText("[!] Zion Offline. Verifique o servidor.\n"));
            }
        }).start();
    }

    private void setupMainUI(Stage stage) {
        applyMatrixStyle("#003300");
        chat.setEditable(false);
        input.setPromptText("");

        Canvas canvas = new Canvas(800, 500);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        int columns = (int) (800 / fontSize) + 5;
        drops = new int[columns];
        Arrays.fill(drops, 1);

        AnimationTimer timer = new AnimationTimer() {
            private long lastUpdate = 0;
            @Override
            public void handle(long now) {
                if (now - lastUpdate >= 50_000_000) {
                    drawMatrixEffect(gc, canvas.getWidth(), canvas.getHeight());
                    lastUpdate = now;
                }
            }
        };
        timer.start();

        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB) {
                handleTabComplete();
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                navigateHistory(true);
            } else if (e.getCode() == KeyCode.DOWN) {
                navigateHistory(false);
            }
        });

        input.setOnAction(e -> {
            String t = input.getText().trim();
            if (t.isEmpty()) return;
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
        side.setStyle("-fx-text-fill: #00ff00; -fx-font-family: 'Consolas';");
        uiOverlay.setRight(side);

        rootStack.getChildren().addAll(canvas, uiOverlay);
        rootStack.setStyle("-fx-background-color: black;");

        canvas.widthProperty().bind(stage.widthProperty());
        canvas.heightProperty().bind(stage.heightProperty());
        canvas.widthProperty().addListener(ov -> {
            drops = new int[(int)(canvas.getWidth()/fontSize) + 5];
            Arrays.fill(drops, 1);
        });

        stage.setScene(new Scene(rootStack, 800, 500));
        stage.setTitle("ZiON TERMINAL");
        stage.show();
    }

    private void drawMatrixEffect(GraphicsContext gc, double w, double h) {
        // 1. Aplica uma camada preta semi-transparente para criar o rastro de desfoque
        gc.setFill(Color.color(0, 0, 0, 0.15));
        gc.fillRect(0, 0, w, h);

        gc.setFont(new Font("Consolas", fontSize));
        Random rand = new Random();

        for (int i = 0; i < drops.length; i++) {
            // Escolhe um caractere aleatório (Katakana/Sushi)
            char text = matrixChars.charAt(rand.nextInt(matrixChars.length()));

            // --- LÓGICA DE COR CORRIGIDA ---

            // 2. Desenha o rastro (o caractere que ficou para trás) em VERDE
            // Isso garante que o rastro não fique branco
            gc.setFill(Color.web("#00ff00"));
            gc.fillText(String.valueOf(text), i * fontSize, (drops[i] - 1) * fontSize);

            // 3. Desenha a "cabeça" da gota em BRANCO/VERDE CLARO
            gc.setFill(Color.web("#ccffcc"));
            gc.fillText(String.valueOf(text), i * fontSize, drops[i] * fontSize);

            // Reinicia a gota aleatoriamente ao chegar no fim da tela
            if (drops[i] * fontSize > h && Math.random() > 0.975) {
                drops[i] = 0;
            }

            drops[i]++;
        }
    }

    private void handleTabComplete() {
        String text = input.getText();
        if (text.isEmpty()) return;
        String[] words = text.split(" ");
        String lastWord = words[words.length - 1];
        String searchPrefix = lastWord.startsWith("@") ? lastWord.substring(1) : lastWord;

        Optional<String> match = usersList.getItems().stream()
                .filter(u -> u.toLowerCase().startsWith(searchPrefix.toLowerCase())).findFirst();

        if (match.isPresent()) {
            String prefix = lastWord.startsWith("@") ? "@" : "";
            words[words.length - 1] = prefix + match.get();
            input.setText(String.join(" ", words) + " ");
            input.positionCaret(input.getText().length());
        }
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

    private void handle(String m) {
        if (m.equals("SYS|JOIN_SUCCESS")) {
            if (loginStage != null) loginStage.close();
            input.requestFocus();
            chat.appendText(">>> BEM-VINDO À REDE ZION.\n");
            chat.appendText(">>> Digite /help para listar comandos disponíveis.\n\n");
        }
        else if (m.equals("SYS|ARCHITECT_MODE_ON")) {
            applyMatrixStyle("#FF0000");
            chat.appendText(">>> ACESSO DE AGENTE CONFIRMADO.\n");
        }
        else if (m.equals("SYS|ARCHITECT_MODE_OFF")) {
            applyMatrixStyle("#003300");
            chat.appendText(">>> STATUS: PRIVILÉGIOS REVOGADOS. VOLTANDO AO NORMAL.\n");
        }
        else if (m.startsWith("USERS|")) {
            usersList.getItems().clear();
            for (String u : m.substring(6).split(",")) if(!u.isEmpty()) usersList.getItems().add(u);
        } else if (m.startsWith("MSG|")) {
            String[] p = m.split("\\|", 3);
            chat.appendText("<" + p[1] + "> " + p[2] + "\n");
        } else if (m.startsWith("SYS|")) {
            chat.appendText("[SYS] " + m.substring(4) + "\n");
        }
    }

    private void applyMatrixStyle(String borderColor) {
        style(chat, borderColor);
        style(usersList, borderColor);
        style(input, borderColor);
    }

    private void style(Control c, String bc) {
        c.setStyle("-fx-control-inner-background: rgba(0, 20, 0, 0.7); " +
                "-fx-text-fill: #00ff00; -fx-font-family: 'Consolas'; " +
                "-fx-background-color: transparent; -fx-border-color: " + bc + "; -fx-border-width: 2; " +
                "-fx-padding: 5 2 5 2;");
    }

    private boolean connectToServer() {
        try {
            socket = new Socket(HOST, PORT);
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
            }).start();
            return true;
        } catch (Exception e) { return false; }
    }

    private void showLoginWindow(Stage owner) {
        loginStage = new Stage();
        loginStage.initModality(Modality.APPLICATION_MODAL);
        loginStage.initOwner(owner);
        VBox layout = new VBox(10); layout.setAlignment(Pos.CENTER); layout.setPadding(new Insets(20));
        layout.setStyle("-fx-background-color: black; -fx-border-color: #00ff00;");
        TextField nf = new TextField("User_" + new Random().nextInt(999));
        nf.setStyle("-fx-background-color: #001100; -fx-text-fill: #00ff00; -fx-font-family: 'Consolas';");
        Button b = new Button("CONECTAR");
        b.setOnAction(e -> { if(!nf.getText().trim().isEmpty()){ send("JOIN|"+nf.getText().trim()); b.setDisable(true); } });
        layout.getChildren().addAll(new Label("CODINOME:"), nf, b);
        loginStage.setScene(new Scene(layout, 300, 150));
        loginStage.show();
    }

    private void send(String m) { if(out != null) out.println(encrypt(m)); }
    private String encrypt(String d) { try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SECRET_KEY.getBytes(), "AES"), new GCMParameterSpec(128, new byte[12])); return Base64.getEncoder().encodeToString(c.doFinal(d.getBytes())); } catch (Exception e) { return ""; } }
    private String decrypt(String d) { try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SECRET_KEY.getBytes(), "AES"), new GCMParameterSpec(128, new byte[12])); return new String(c.doFinal(Base64.getDecoder().decode(d))); } catch (Exception e) { return "ERR"; } }
    public static void main(String[] args) { launch(args); }
}