package org.example.ChatServer;

public class PongGame {

    String playerLeft;
    String playerRight;

    double leftY = 200;
    double rightY = 200;

    double ballX = 400;
    double ballY = 250;

    // AGORA EM PIXELS POR SEGUNDO
    double ballVelX = 350;
    double ballVelY = 350;

    int scoreLeft = 0;
    int scoreRight = 0;

    boolean running = true;

    boolean leftUp;
    boolean leftDown;

    boolean rightUp;
    boolean rightDown;

    double deltaTime = 0;

    PongGame(String l, String r) {
        playerLeft = l;
        playerRight = r;
    }
}
