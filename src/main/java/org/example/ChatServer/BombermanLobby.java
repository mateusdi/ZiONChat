package org.example.ChatServer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BombermanLobby {

    String host;

    Set<String> invited =
            ConcurrentHashMap.newKeySet();

    Set<String> accepted =
            ConcurrentHashMap.newKeySet();

    Set<String> declined =
            ConcurrentHashMap.newKeySet();

    boolean started = false;

    long createdAt =
            System.currentTimeMillis();

    public BombermanLobby(String host) {

        this.host = host;
    }
}
