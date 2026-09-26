package com.houseofel.common.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public record HandshakePayload(String modVersion, int schemaVersion) {

    public byte[] toBytes() {
        try {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeUTF(modVersion);
            dos.writeInt(schemaVersion);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static HandshakePayload fromBytes(byte[] data) {
        try {
            var dis = new DataInputStream(new ByteArrayInputStream(data));
            return new HandshakePayload(dis.readUTF(), dis.readInt());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
