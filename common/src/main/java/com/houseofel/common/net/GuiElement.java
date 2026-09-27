package com.houseofel.common.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public sealed interface GuiElement
        permits GuiElement.Panel, GuiElement.Label, GuiElement.Image,
                GuiElement.Button, GuiElement.TextInput, GuiElement.Toggle,
                GuiElement.Dropdown, GuiElement.SearchableList, GuiElement.ItemIcon, GuiElement.Unknown {

    String id();
    Anchor anchor();
    int[] offset();
    boolean visible();

    record Panel(String id, Anchor anchor, int[] offset, boolean visible,
                 int[] size, String texture, int[] nineSlice,
                 List<GuiElement> children) implements GuiElement {}

    record Label(String id, Anchor anchor, int[] offset, boolean visible,
                 String text, String color, boolean shadow,
                 String align) implements GuiElement {}

    record Image(String id, Anchor anchor, int[] offset, boolean visible,
                 int[] size, String texture) implements GuiElement {}

    record Button(String id, Anchor anchor, int[] offset, boolean visible,
                  int[] size, String action, String text,
                  String texture, String textureHover, String textureDisabled,
                  boolean enabled, String tooltip) implements GuiElement {
        public Button(String id, Anchor anchor, int[] offset, boolean visible,
                      int[] size, String action, String text, String texture,
                      String textureHover, String textureDisabled, boolean enabled) {
            this(id, anchor, offset, visible, size, action, text, texture,
                    textureHover, textureDisabled, enabled, null);
        }
    }

    record ItemIcon(String id, Anchor anchor, int[] offset, boolean visible,
                    String itemId) implements GuiElement {}

    record SearchOption(String id, String label, String itemId) {}

    /** Size includes a 16px status header; rows are 24px high. Dispatches chosen id under this element id. */
    record SearchableList(String id, Anchor anchor, int[] offset, boolean visible,
                          int[] size, String searchInputId, String action,
                          String texture, String textureHover, String emptyLabel,
                          String resultsLabel, String noResultsLabel,
                          List<SearchOption> options, List<String> quickPickIds) implements GuiElement {
        public SearchableList {
            options = List.copyOf(options);
            quickPickIds = List.copyOf(quickPickIds);
        }
    }

    record TextInput(String id, Anchor anchor, int[] offset, boolean visible,
                     int[] size, String placeholder, String initial) implements GuiElement {}

    record Toggle(String id, Anchor anchor, int[] offset, boolean visible,
                  String text, boolean initial) implements GuiElement {}

    record Dropdown(String id, Anchor anchor, int[] offset, boolean visible,
                    int[] size, List<String> options, int initial) implements GuiElement {}

    record Unknown(String id, Anchor anchor, int[] offset, boolean visible,
                   String typeName) implements GuiElement {}

    // --- Wire format ---

    static void writeTo(DataOutputStream dos, GuiElement element) throws IOException {
        switch (element) {
            case Panel p -> {
                dos.writeUTF("panel");
                writeCommon(dos, p);
                dos.writeInt(p.size[0]);
                dos.writeInt(p.size[1]);
                writeNullableUTF(dos, p.texture);
                if (p.nineSlice != null) {
                    dos.writeBoolean(true);
                    for (int v : p.nineSlice) dos.writeInt(v);
                } else {
                    dos.writeBoolean(false);
                }
                List<GuiElement> kids = p.children != null ? p.children : List.of();
                dos.writeInt(kids.size());
                for (GuiElement child : kids) writeTo(dos, child);
            }
            case Label l -> {
                dos.writeUTF("label");
                writeCommon(dos, l);
                dos.writeUTF(l.text);
                dos.writeUTF(l.color);
                dos.writeBoolean(l.shadow);
                dos.writeUTF(l.align);
            }
            case Image i -> {
                dos.writeUTF("image");
                writeCommon(dos, i);
                dos.writeInt(i.size[0]);
                dos.writeInt(i.size[1]);
                dos.writeUTF(i.texture);
            }
            case Button b -> {
                dos.writeUTF("button");
                writeCommon(dos, b);
                dos.writeInt(b.size[0]);
                dos.writeInt(b.size[1]);
                dos.writeUTF(b.action);
                writeNullableUTF(dos, b.text);
                writeNullableUTF(dos, b.texture);
                writeNullableUTF(dos, b.textureHover);
                writeNullableUTF(dos, b.textureDisabled);
                dos.writeBoolean(b.enabled);
                writeNullableUTF(dos, b.tooltip);
            }
            case TextInput t -> {
                dos.writeUTF("text_input");
                writeCommon(dos, t);
                dos.writeInt(t.size[0]);
                dos.writeInt(t.size[1]);
                writeNullableUTF(dos, t.placeholder);
                writeNullableUTF(dos, t.initial);
            }
            case Toggle t -> {
                dos.writeUTF("toggle");
                writeCommon(dos, t);
                dos.writeUTF(t.text);
                dos.writeBoolean(t.initial);
            }
            case Dropdown d -> {
                dos.writeUTF("dropdown");
                writeCommon(dos, d);
                dos.writeInt(d.size[0]);
                dos.writeInt(d.size[1]);
                dos.writeInt(d.options.size());
                for (String opt : d.options) dos.writeUTF(opt);
                dos.writeInt(d.initial);
            }
            case ItemIcon i -> {
                dos.writeUTF("item_icon");
                writeCommon(dos, i);
                dos.writeUTF(i.itemId);
            }
            case SearchableList l -> {
                dos.writeUTF("searchable_list");
                writeCommon(dos, l);
                dos.writeInt(l.size[0]);
                dos.writeInt(l.size[1]);
                dos.writeUTF(l.searchInputId);
                dos.writeUTF(l.action);
                writeNullableUTF(dos, l.texture);
                writeNullableUTF(dos, l.textureHover);
                dos.writeUTF(l.emptyLabel);
                dos.writeUTF(l.resultsLabel);
                dos.writeUTF(l.noResultsLabel);
                dos.writeInt(l.options.size());
                for (SearchOption option : l.options) {
                    dos.writeUTF(option.id);
                    dos.writeUTF(option.label);
                    dos.writeUTF(option.itemId);
                }
                dos.writeInt(l.quickPickIds.size());
                for (String quickId : l.quickPickIds) dos.writeUTF(quickId);
            }
            case Unknown u -> {
                dos.writeUTF(u.typeName);
                writeCommon(dos, u);
            }
        }
    }

    static GuiElement readFrom(DataInputStream dis) throws IOException {
        String type = dis.readUTF();
        String id = dis.readUTF();
        Anchor anchor = Anchor.fromWire(dis.readUTF());
        int[] offset = new int[] { dis.readInt(), dis.readInt() };
        boolean visible = dis.readBoolean();

        return switch (type) {
            case "panel" -> {
                int[] size = new int[] { dis.readInt(), dis.readInt() };
                String texture = readNullableUTF(dis);
                int[] nineSlice = null;
                if (dis.readBoolean()) {
                    nineSlice = new int[] { dis.readInt(), dis.readInt(), dis.readInt(), dis.readInt() };
                }
                int count = dis.readInt();
                List<GuiElement> children = new ArrayList<>(count);
                for (int i = 0; i < count; i++) children.add(readFrom(dis));
                yield new Panel(id, anchor, offset, visible, size, texture, nineSlice,
                        Collections.unmodifiableList(children));
            }
            case "label" -> new Label(id, anchor, offset, visible,
                    dis.readUTF(), dis.readUTF(), dis.readBoolean(), dis.readUTF());
            case "image" -> new Image(id, anchor, offset, visible,
                    new int[] { dis.readInt(), dis.readInt() }, dis.readUTF());
            case "button" -> new Button(id, anchor, offset, visible,
                    new int[] { dis.readInt(), dis.readInt() },
                    dis.readUTF(), readNullableUTF(dis),
                    readNullableUTF(dis), readNullableUTF(dis), readNullableUTF(dis),
                    dis.readBoolean(), readNullableUTF(dis));
            case "text_input" -> new TextInput(id, anchor, offset, visible,
                    new int[] { dis.readInt(), dis.readInt() },
                    readNullableUTF(dis), readNullableUTF(dis));
            case "toggle" -> new Toggle(id, anchor, offset, visible,
                    dis.readUTF(), dis.readBoolean());
            case "dropdown" -> {
                int[] size = new int[] { dis.readInt(), dis.readInt() };
                int optCount = dis.readInt();
                List<String> options = new ArrayList<>(optCount);
                for (int i = 0; i < optCount; i++) options.add(dis.readUTF());
                yield new Dropdown(id, anchor, offset, visible, size,
                        Collections.unmodifiableList(options), dis.readInt());
            }
            case "item_icon" -> new ItemIcon(id, anchor, offset, visible, dis.readUTF());
            case "searchable_list" -> {
                int[] size = {dis.readInt(), dis.readInt()};
                String input = dis.readUTF(), action = dis.readUTF();
                String texture = readNullableUTF(dis), hover = readNullableUTF(dis);
                String empty = dis.readUTF(), results = dis.readUTF(), noResults = dis.readUTF();
                int count = dis.readInt();
                if (count < 0 || count > 10000) throw new IOException("Invalid list size");
                List<SearchOption> options = new ArrayList<>(count);
                for (int i = 0; i < count; i++)
                    options.add(new SearchOption(dis.readUTF(), dis.readUTF(), dis.readUTF()));
                int quickCount = dis.readInt();
                if (quickCount < 0 || quickCount > count) throw new IOException("Invalid quick-pick count");
                List<String> quick = new ArrayList<>(quickCount);
                for (int i = 0; i < quickCount; i++) quick.add(dis.readUTF());
                yield new SearchableList(id, anchor, offset, visible, size, input, action,
                        texture, hover, empty, results, noResults, options, quick);
            }
            default -> new Unknown(id, anchor, offset, visible, type);
        };
    }

    private static void writeCommon(DataOutputStream dos, GuiElement e) throws IOException {
        dos.writeUTF(e.id());
        dos.writeUTF(e.anchor().name());
        dos.writeInt(e.offset()[0]);
        dos.writeInt(e.offset()[1]);
        dos.writeBoolean(e.visible());
    }

    private static void writeNullableUTF(DataOutputStream dos, String value) throws IOException {
        if (value != null) {
            dos.writeBoolean(true);
            dos.writeUTF(value);
        } else {
            dos.writeBoolean(false);
        }
    }

    private static String readNullableUTF(DataInputStream dis) throws IOException {
        return dis.readBoolean() ? dis.readUTF() : null;
    }
}
