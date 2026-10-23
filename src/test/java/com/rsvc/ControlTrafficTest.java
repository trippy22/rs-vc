package com.rsvc;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.Request;
import okhttp3.WebSocket;
import okio.ByteString;
import org.junit.Test;
import static org.junit.Assert.*;

public class ControlTrafficTest
{
    @Test public void heartbeatIsDeduplicatedAndLoginChangesAreImmediate()
    {
        List<String> sent = new ArrayList<>(); VoiceMetrics metrics = new VoiceMetrics();
        WebSocket socket = new WebSocket() {
            public Request request() { return new Request.Builder().url("http://localhost").build(); }
            public long queueSize() { return 0; }
            public boolean send(String text) { sent.add(text); return true; }
            public boolean send(ByteString bytes) { return true; }
            public boolean close(int code, String reason) { return true; }
            public void cancel() {}
        };
        VoiceConnection c = new VoiceConnection(new Gson(), "Tester", s -> {}, p -> {}, p -> {}, retry -> {}, metrics);
        c.onOpen(socket, null); assertEquals(2, sent.size());
        c.listening(false, Collections.emptySet(), Collections.emptySet()); assertEquals(2, sent.size());
        c.heartbeat(true); c.heartbeat(true); assertEquals(3, sent.size()); assertTrue(c.canTransmit());
        assertEquals("{\"type\":\"heartbeat\",\"active\":true}", sent.get(2));
        assertTrue(sent.get(0).contains("\"v\":2"));
        c.heartbeat(false); assertEquals(4, sent.size()); assertFalse(c.canTransmit());
        c.listening(true, Collections.emptySet(), Collections.emptySet()); assertEquals(5, sent.size());
        c.onMessage(socket, "{\"type\":\"peers\",\"peers\":[]}");
        assertTrue(metrics.csv().contains("rx_control_messages,1,messages"));
        c.close(); c.close(); c.heartbeat(true); assertEquals(5, sent.size()); assertFalse(c.canTransmit());
        assertTrue(metrics.csv().contains("tx_control_messages,5,messages"));
    }
}
