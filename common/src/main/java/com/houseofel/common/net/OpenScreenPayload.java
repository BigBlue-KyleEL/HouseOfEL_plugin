package com.houseofel.common.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record OpenScreenPayload(String screenId, int[] canvasMin, int[] canvasMax,
                                 List<GuiElement> root) {

    public byte[] toBytes() {
        try {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeUTF(screenId);
            dos.writeInt(canvasMin[0]);
            dos.writeInt(canvasMin[1]);
            if (canvasMax != null) {
                dos.writeBoolean(true);
                dos.writeInt(canvasMax[0]);
                dos.writeInt(canvasMax[1]);
            } else {
                dos.writeBoolean(false);
            }
            dos.writeInt(root.size());
            for (GuiElement element : root) {
                GuiElement.writeTo(dos, element);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static OpenScreenPayload fromBytes(byte[] data) {
        try {
            var dis = new DataInputStream(new ByteArrayInputStream(data));
            String screenId = dis.readUTF();
            int[] canvasMin = new int[] { dis.readInt(), dis.readInt() };
            int[] canvasMax = dis.readBoolean() ? new int[] { dis.readInt(), dis.readInt() } : null;
            int count = dis.readInt();
            List<GuiElement> root = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                root.add(GuiElement.readFrom(dis));
            }
            return new OpenScreenPayload(screenId, canvasMin, canvasMax,
                    Collections.unmodifiableList(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
