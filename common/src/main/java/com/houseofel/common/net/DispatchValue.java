package com.houseofel.common.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public sealed interface DispatchValue {

    byte TAG_STRING = 0x01;
    byte TAG_BOOL = 0x02;
    byte TAG_INT = 0x03;

    void writeTo(DataOutputStream dos) throws IOException;

    record StringVal(String value) implements DispatchValue {
        @Override
        public void writeTo(DataOutputStream dos) throws IOException {
            dos.writeByte(TAG_STRING);
            dos.writeUTF(value);
        }
    }

    record BoolVal(boolean value) implements DispatchValue {
        @Override
        public void writeTo(DataOutputStream dos) throws IOException {
            dos.writeByte(TAG_BOOL);
            dos.writeBoolean(value);
        }
    }

    record IntVal(int value) implements DispatchValue {
        @Override
        public void writeTo(DataOutputStream dos) throws IOException {
            dos.writeByte(TAG_INT);
            dos.writeInt(value);
        }
    }

    static DispatchValue readFrom(DataInputStream dis) throws IOException {
        byte tag = dis.readByte();
        return switch (tag) {
            case TAG_STRING -> new StringVal(dis.readUTF());
            case TAG_BOOL -> new BoolVal(dis.readBoolean());
            case TAG_INT -> new IntVal(dis.readInt());
            default -> throw new IllegalArgumentException(
                    "Unknown dispatch value tag: 0x" + String.format("%02X", tag));
        };
    }
}
