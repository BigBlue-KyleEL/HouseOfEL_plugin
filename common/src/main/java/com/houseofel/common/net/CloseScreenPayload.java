package com.houseofel.common.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public record CloseScreenPayload(String screenId) {

    public byte[] toBytes() {
        try {
            var baos = new ByteArrayOutputStream();
            new DataOutputStream(baos).writeUTF(screenId);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static CloseScreenPayload fromBytes(byte[] data) {
        try {
            return new CloseScreenPayload(
                    new DataInputStream(new ByteArrayInputStream(data)).readUTF());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
