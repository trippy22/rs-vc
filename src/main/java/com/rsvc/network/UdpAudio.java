package com.rsvc.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional encrypted UDP audio path to the relay ("UDP audio" in the relay's PROTOCOL.md). The
 * WebSocket stays the control channel and the audio fallback. Sends are serialized on this
 * object; one daemon thread receives and calls the listener.
 */
final class UdpAudio implements AutoCloseable {
    static final int PROBE = 1, AUDIO_UP = 2, PROBE_ACK = 3, AUDIO_DOWN = 4;
    // Client to relay: kind, session, 64-bit counter. Relay to client: kind, 32-bit counter.
    static final int HEADER = 17, DOWN_HEADER = 5, TAG = 16;
    static final int TO_RELAY = 0, FROM_RELAY = 1;

    interface Listener {
        void acknowledged();

        void audio(byte[] payload);
    }

    private final DatagramSocket socket;
    private final byte[] session;
    private final SecretKeySpec key;
    private final Cipher sealer, opener;
    private final Listener listener;
    private long sent;
    // Owned by the receive thread.
    private long received;
    private volatile boolean closed;

    UdpAudio(InetSocketAddress relay, byte[] session, byte[] key, Listener listener)
            throws IOException, GeneralSecurityException {
        this.session = session.clone();
        this.key = new SecretKeySpec(key, "AES");
        this.listener = listener;
        sealer = Cipher.getInstance("AES/GCM/NoPadding");
        opener = Cipher.getInstance("AES/GCM/NoPadding");
        socket = new DatagramSocket();
        // A connected socket only accepts datagrams from the relay's address.
        socket.connect(relay);
        socket.setSoTimeout(1000);
        Thread reader = new Thread(this::receive, "RS VC UDP");
        reader.setDaemon(true);
        reader.start();
    }

    synchronized boolean send(int kind, byte[] payload) {
        if (closed) {
            return false;
        }
        try {
            byte[] datagram = seal(sealer, key, kind, session, ++sent, payload);
            socket.send(new DatagramPacket(datagram, datagram.length));
            return true;
        } catch (IOException | GeneralSecurityException e) {
            return false;
        }
    }

    private void receive() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!closed) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                continue;
            }
            int length = packet.getLength();
            int kind = buffer[0] & 0xff;
            if (length < DOWN_HEADER + TAG || (kind != PROBE_ACK && kind != AUDIO_DOWN)) {
                continue;
            }
            long counter = Integer.toUnsignedLong(ByteBuffer.wrap(buffer, 1, 4).getInt());
            // Replayed and reordered datagrams are dropped; late audio is useless.
            if (counter <= received) {
                continue;
            }
            byte[] payload = open(opener, key, buffer, length);
            if (payload == null) {
                continue;
            }
            received = counter;
            if (kind == PROBE_ACK) {
                if (payload.length == 0) {
                    listener.acknowledged();
                }
            } else {
                listener.audio(payload);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }

    private static byte[] header(int kind, byte[] session, long counter) {
        return ByteBuffer.allocate(HEADER).put((byte) kind).put(session).putLong(counter).array();
    }

    static GCMParameterSpec nonce(int direction, long counter) {
        return new GCMParameterSpec(
                TAG * 8, ByteBuffer.allocate(12).putInt(direction).putLong(counter).array());
    }

    /** A client-to-relay datagram. */
    static byte[] seal(
            Cipher cipher, SecretKeySpec key, int kind, byte[] session, long counter, byte[] payload)
            throws GeneralSecurityException {
        byte[] header = header(kind, session, counter);
        cipher.init(Cipher.ENCRYPT_MODE, key, nonce(TO_RELAY, counter));
        cipher.updateAAD(header);
        byte[] sealed = cipher.doFinal(payload);
        byte[] datagram = Arrays.copyOf(header, HEADER + sealed.length);
        System.arraycopy(sealed, 0, datagram, HEADER, sealed.length);
        return datagram;
    }

    /**
     * The payload of a relay-to-client datagram, or null when it fails authentication. The
     * connected socket already identifies the session, so the header carries no session ID.
     */
    static byte[] open(Cipher cipher, SecretKeySpec key, byte[] data, int length) {
        if (length < DOWN_HEADER + TAG) {
            return null;
        }
        try {
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    nonce(FROM_RELAY, Integer.toUnsignedLong(ByteBuffer.wrap(data, 1, 4).getInt())));
            cipher.updateAAD(data, 0, DOWN_HEADER);
            return cipher.doFinal(data, DOWN_HEADER, length - DOWN_HEADER);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
