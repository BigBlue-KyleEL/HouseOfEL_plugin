package com.houseofel.common.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record DispatchPayload(String screenId, String action, Map<String, DispatchValue> values) {

    public byte[] toBytes() {
        try {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeUTF(screenId);
            dos.writeUTF(action);
            dos.writeInt(values.size());
            for (var entry : values.entrySet()) {
                dos.writeUTF(entry.getKey());
                entry.getValue().writeTo(dos);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DispatchPayload fromBytes(byte[] data) {
        try {
            var dis = new DataInputStream(new ByteArrayInputStream(data));
            String screenId = dis.readUTF();
            String action = dis.readUTF();
            int count = dis.readInt();
            Map<String, DispatchValue> values = new LinkedHashMap<>(count);
            for (int i = 0; i < count; i++) {
                String key = dis.readUTF();
                values.put(key, DispatchValue.readFrom(dis));
            }
            return new DispatchPayload(screenId, action,
                    Collections.unmodifiableMap(values));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
