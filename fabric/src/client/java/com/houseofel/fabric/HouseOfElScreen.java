package com.houseofel.fabric;

import com.houseofel.common.net.Anchor;
import com.houseofel.common.net.DispatchPayload;
import com.houseofel.common.net.DispatchValue;
import com.houseofel.common.net.GuiElement;
import com.houseofel.common.net.ScreenClosedPayload;
import com.houseofel.common.net.SearchableListState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import com.houseofel.fabric.net.DispatchCustomPayload;
import com.houseofel.fabric.net.ScreenClosedCustomPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HouseOfElScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("HouseOfEL");

    private final String screenId;
    private final int[] canvasMin;
    private final int[] canvasMax;
    private final List<GuiElement> root;

    private int canvasX, canvasY, canvasW, canvasH;
    private float scale = 1.0f;
    private boolean serverClosed;

    // Resolved positions rebuilt every frame
    private final List<ResolvedButton> resolvedButtons = new ArrayList<>();
    private final List<ResolvedTextInput> resolvedTextInputs = new ArrayList<>();
    private final List<ResolvedToggle> resolvedToggles = new ArrayList<>();
    private final List<ResolvedDropdown> resolvedDropdowns = new ArrayList<>();

    // Input element state — initialized once from element definitions
    private final Map<String, StringBuilder> textInputValues = new LinkedHashMap<>();
    private final Map<String, Boolean> toggleValues = new LinkedHashMap<>();
    private final Map<String, Integer> dropdownValues = new LinkedHashMap<>();

    private final Map<String, SearchableListState> listStates = new LinkedHashMap<>();
    private final Map<String, ItemStack> itemIcons = new LinkedHashMap<>();
    private final List<ResolvedList> resolvedLists = new ArrayList<>();
    private record ResolvedList(GuiElement.SearchableList element, int x, int y) {}
    private String draggingListId;
    private String hoverTooltip;

    private String focusedTextInputId;
    private int textCursorPos;
    private String openDropdownId;
    private int tickCount;

    private record ResolvedButton(GuiElement.Button element, int x, int y) {}
    private record ResolvedTextInput(GuiElement.TextInput element, int x, int y) {}
    private record ResolvedToggle(GuiElement.Toggle element, int x, int y, int w, int h) {}
    private record ResolvedDropdown(GuiElement.Dropdown element, int x, int y) {}

    public HouseOfElScreen(String screenId, int[] canvasMin, int[] canvasMax,
                            List<GuiElement> root) {
        super(Component.literal(screenId));
        this.screenId = screenId;
        this.canvasMin = canvasMin;
        this.canvasMax = canvasMax;
        this.root = root;
        initInputState(root);
    }

    private void initInputState(List<GuiElement> elements) {
        for (GuiElement e : elements) {
            switch (e) {
                case GuiElement.TextInput t ->
                        textInputValues.put(t.id(), new StringBuilder(
                                t.initial() != null ? t.initial() : ""));
                case GuiElement.Toggle t ->
                        toggleValues.put(t.id(), t.initial());
                case GuiElement.Dropdown d ->
                        dropdownValues.put(d.id(), d.initial());
                case GuiElement.SearchableList l -> listStates.put(l.id(), new SearchableListState(l));
                case GuiElement.Panel p -> {
                    if (p.children() != null) initInputState(p.children());
                }
                default -> {}
            }
        }
    }

    public String screenId() { return screenId; }

    public void closeFromServer() {
        serverClosed = true;
        onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() { computeCanvas(); }

    @Override
    public void tick() { tickCount++; }

    private void computeCanvas() {
        int windowW = this.width;
        int windowH = this.height;
        int minW = canvasMin[0], minH = canvasMin[1];
        int maxW = canvasMax != null ? canvasMax[0] : Integer.MAX_VALUE;
        int maxH = canvasMax != null ? canvasMax[1] : Integer.MAX_VALUE;

        if (windowW < minW || windowH < minH) {
            scale = Math.min((float) windowW / minW, (float) windowH / minH);
            canvasW = minW;
            canvasH = minH;
        } else {
            scale = 1.0f;
            canvasW = Math.min(windowW, maxW);
            canvasH = Math.min(windowH, maxH);
        }

        canvasX = Math.round((windowW - canvasW * scale) / 2f);
        canvasY = Math.round((windowH - canvasH * scale) / 2f);
    }

    // ---- Rendering ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        hoverTooltip = null;
        resolvedLists.clear();
        resolvedButtons.clear();
        resolvedTextInputs.clear();
        resolvedToggles.clear();
        resolvedDropdowns.clear();

        float cmx = (mouseX - canvasX) / scale;
        float cmy = (mouseY - canvasY) / scale;

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(canvasX, canvasY);
        if (scale != 1.0f) {
            pose.scale(scale, scale);
        }

        for (GuiElement element : root) {
            renderElement(g, element, 0, 0, canvasW, canvasH, cmx, cmy);
        }

        // Render open dropdown overlay last so it draws on top
        if (openDropdownId != null) {
            for (ResolvedDropdown rd : resolvedDropdowns) {
                if (rd.element.id().equals(openDropdownId)) {
                    renderDropdownOptions(g, rd, cmx, cmy);
                    break;
                }
            }
        }

        pose.popMatrix();
        if (hoverTooltip != null) g.setTooltipForNextFrame(Component.literal(hoverTooltip), mouseX, mouseY);
    }

    private void renderElement(GuiGraphicsExtractor g, GuiElement element,
                                int px, int py, int pw, int ph,
                                float mx, float my) {
        if (!element.visible()) return;

        switch (element) {
            case GuiElement.Panel p -> renderPanel(g, p, px, py, pw, ph, mx, my);
            case GuiElement.Label l -> renderLabel(g, l, px, py, pw, ph);
            case GuiElement.Image i -> renderImage(g, i, px, py, pw, ph);
            case GuiElement.Button b -> renderButton(g, b, px, py, pw, ph, mx, my);
            case GuiElement.TextInput t -> renderTextInput(g, t, px, py, pw, ph);
            case GuiElement.Toggle t -> renderToggle(g, t, px, py, pw, ph, mx, my);
            case GuiElement.Dropdown d -> renderDropdown(g, d, px, py, pw, ph, mx, my);
            case GuiElement.ItemIcon i -> {
                int[] pos = resolveAnchor(i.anchor(), i.offset(), 16, 16, px, py, pw, ph);
                renderItemIcon(g, i.itemId(), pos[0], pos[1]);
            }
            case GuiElement.SearchableList l -> renderSearchableList(g, l, px, py, pw, ph, mx, my);
            case GuiElement.Unknown u -> renderUnknown(g, u, px, py, pw, ph);
        }
    }

    private void renderPanel(GuiGraphicsExtractor g, GuiElement.Panel panel,
                              int px, int py, int pw, int ph,
                              float mx, float my) {
        int w = panel.size()[0], h = panel.size()[1];
        int[] pos = resolveAnchor(panel.anchor(), panel.offset(), w, h, px, py, pw, ph);
        int x = pos[0], y = pos[1];

        if (panel.texture() == null) {
            g.fill(x, y, x + w, y + h, 0xC0101010);
        } else {
            Identifier texture = resolveTexture(panel.texture());
            g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, h, w, h);
        }

        if (panel.children() != null) {
            for (GuiElement child : panel.children()) {
                renderElement(g, child, x, y, w, h, mx, my);
            }
        }
    }

    private void renderLabel(GuiGraphicsExtractor g, GuiElement.Label label,
                              int px, int py, int pw, int ph) {
        int textW = this.font.width(label.text());
        int textH = this.font.lineHeight;
        int[] pos = resolveAnchor(label.anchor(), label.offset(), textW, textH,
                px, py, pw, ph);
        g.text(this.font, label.text(), pos[0], pos[1],
                parseColor(label.color()), label.shadow());
    }

    private void renderImage(GuiGraphicsExtractor g, GuiElement.Image image,
                              int px, int py, int pw, int ph) {
        int w = image.size()[0], h = image.size()[1];
        int[] pos = resolveAnchor(image.anchor(), image.offset(), w, h, px, py, pw, ph);
        Identifier texture = resolveTexture(image.texture());
        g.blit(RenderPipelines.GUI_TEXTURED, texture, pos[0], pos[1], 0f, 0f, w, h, w, h);
    }

    private static Identifier resolveTexture(String namespacedPath) {
        int colon = namespacedPath.indexOf(':');
        if (colon == -1) {
            return Identifier.fromNamespaceAndPath("minecraft", "textures/" + namespacedPath + ".png");
        }
        return Identifier.fromNamespaceAndPath(
                namespacedPath.substring(0, colon),
                "textures/" + namespacedPath.substring(colon + 1) + ".png");
    }

    private void renderButton(GuiGraphicsExtractor g, GuiElement.Button button,
                               int px, int py, int pw, int ph,
                               float mx, float my) {
        int w = button.size()[0], h = button.size()[1];
        int[] pos = resolveAnchor(button.anchor(), button.offset(), w, h, px, py, pw, ph);
        int x = pos[0], y = pos[1];

        boolean hovered = button.enabled()
                && mx >= x && mx < x + w && my >= y && my < y + h;

        String texturePath = button.texture();
        if (!button.enabled() && button.textureDisabled() != null) {
            texturePath = button.textureDisabled();
        } else if (hovered && button.textureHover() != null) {
            texturePath = button.textureHover();
        }

        if (texturePath != null) {
            Identifier texture = resolveTexture(texturePath);
            g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, h, w, h);
        } else {
            int bgColor = !button.enabled() ? 0xC0202020
                    : hovered ? 0xC0505050 : 0xC0303030;
            int borderColor = !button.enabled() ? 0xFF555555
                    : hovered ? 0xFFFFFFFF : 0xFFA0A0A0;
            drawBorderedBox(g, x, y, w, h, bgColor, borderColor);
        }

        if (button.text() != null) {
            int textColor = !button.enabled() ? 0xFF999999
                    : hovered ? 0xFFFFFFA0 : 0xFFFFFFFF;
            int textW = this.font.width(button.text());
            int textX = x + (w - textW) / 2;
            int textY = y + (h - this.font.lineHeight) / 2;
            g.text(this.font, button.text(), textX, textY, textColor, true);
        }

        if (button.tooltip() != null && mx >= x && mx < x + w && my >= y && my < y + h)
            hoverTooltip = button.tooltip();
        resolvedButtons.add(new ResolvedButton(button, x, y));
    }

    private void renderTextInput(GuiGraphicsExtractor g, GuiElement.TextInput input,
                                  int px, int py, int pw, int ph) {
        int w = input.size()[0], h = input.size()[1];
        int[] pos = resolveAnchor(input.anchor(), input.offset(), w, h, px, py, pw, ph);
        int x = pos[0], y = pos[1];

        boolean focused = input.id().equals(focusedTextInputId);
        int borderColor = focused ? 0xFFFFFFFF : 0xFF808080;
        drawBorderedBox(g, x, y, w, h, 0xFF000000, borderColor);

        StringBuilder sb = textInputValues.get(input.id());
        String text = sb != null ? sb.toString() : "";
        int pad = 4;
        int textY = y + (h - this.font.lineHeight) / 2;

        if (text.isEmpty() && !focused && input.placeholder() != null) {
            g.text(this.font, input.placeholder(), x + pad, textY, 0xFF666666, false);
        } else {
            String visible = this.font.plainSubstrByWidth(text, w - pad * 2);
            g.text(this.font, visible, x + pad, textY, 0xFFFFFFFF, false);

            if (focused && (tickCount / 10) % 2 == 0) {
                int cursorX = x + pad + this.font.width(
                        text.substring(0, Math.min(textCursorPos, visible.length())));
                g.fill(cursorX, textY - 1, cursorX + 1, textY + this.font.lineHeight, 0xFFFFFFFF);
            }
        }

        resolvedTextInputs.add(new ResolvedTextInput(input, x, y));
    }

    private void renderToggle(GuiGraphicsExtractor g, GuiElement.Toggle toggle,
                               int px, int py, int pw, int ph,
                               float mx, float my) {
        int boxSize = 10;
        int gap = 4;
        int textW = this.font.width(toggle.text());
        int totalW = boxSize + gap + textW;
        int totalH = Math.max(boxSize, this.font.lineHeight);
        int[] pos = resolveAnchor(toggle.anchor(), toggle.offset(), totalW, totalH,
                px, py, pw, ph);
        int x = pos[0], y = pos[1];

        boolean checked = Boolean.TRUE.equals(toggleValues.get(toggle.id()));
        boolean hovered = mx >= x && mx < x + totalW && my >= y && my < y + totalH;

        int boxY = y + (totalH - boxSize) / 2;
        int borderColor = hovered ? 0xFFFFFFFF : 0xFFA0A0A0;
        drawBorderedBox(g, x, y + (totalH - boxSize) / 2, boxSize, boxSize,
                checked ? 0xFF406040 : 0xFF202020, borderColor);

        if (checked) {
            g.text(this.font, "✔", x + 1, boxY + 1, 0xFF80FF80, false);
        }

        int textColor = hovered ? 0xFFFFFFFF : 0xFFCCCCCC;
        g.text(this.font, toggle.text(), x + boxSize + gap,
                y + (totalH - this.font.lineHeight) / 2, textColor, true);

        resolvedToggles.add(new ResolvedToggle(toggle, x, y, totalW, totalH));
    }

    private void renderDropdown(GuiGraphicsExtractor g, GuiElement.Dropdown dropdown,
                                 int px, int py, int pw, int ph,
                                 float mx, float my) {
        int w = dropdown.size()[0], h = dropdown.size()[1];
        int[] pos = resolveAnchor(dropdown.anchor(), dropdown.offset(), w, h, px, py, pw, ph);
        int x = pos[0], y = pos[1];

        boolean open = dropdown.id().equals(openDropdownId);
        boolean hovered = mx >= x && mx < x + w && my >= y && my < y + h;
        int borderColor = open ? 0xFFFFFFFF : hovered ? 0xFFCCCCCC : 0xFF808080;
        drawBorderedBox(g, x, y, w, h, 0xFF181818, borderColor);

        Integer sel = dropdownValues.get(dropdown.id());
        int idx = sel != null ? sel : 0;
        String selectedText = idx >= 0 && idx < dropdown.options().size()
                ? dropdown.options().get(idx) : "";
        int pad = 4;
        g.text(this.font, selectedText, x + pad,
                y + (h - this.font.lineHeight) / 2, 0xFFFFFFFF, true);

        // Arrow indicator
        g.text(this.font, open ? "▲" : "▼", x + w - 10,
                y + (h - this.font.lineHeight) / 2, 0xFFA0A0A0, false);

        resolvedDropdowns.add(new ResolvedDropdown(dropdown, x, y));
    }

    private void renderDropdownOptions(GuiGraphicsExtractor g, ResolvedDropdown rd,
                                        float mx, float my) {
        GuiElement.Dropdown dd = rd.element;
        int w = dd.size()[0];
        int optH = this.font.lineHeight + 4;
        int x = rd.x;
        int y = rd.y + dd.size()[1];
        int pad = 4;

        for (int i = 0; i < dd.options().size(); i++) {
            int oy = y + i * optH;
            boolean hovered = mx >= x && mx < x + w && my >= oy && my < oy + optH;
            g.fill(x, oy, x + w, oy + optH, hovered ? 0xFF404040 : 0xFF1A1A1A);
            g.fill(x, oy, x + 1, oy + optH, 0xFF808080);
            g.fill(x + w - 1, oy, x + w, oy + optH, 0xFF808080);
            if (i == dd.options().size() - 1) {
                g.fill(x, oy + optH - 1, x + w, oy + optH, 0xFF808080);
            }
            g.text(this.font, dd.options().get(i), x + pad, oy + 2, 0xFFFFFFFF, true);
        }
    }

    private void renderItemIcon(GuiGraphicsExtractor g, String itemId, int x, int y) {
        ItemStack stack = itemIcons.computeIfAbsent(itemId, key -> {
            Identifier identifier = Identifier.tryParse(key);
            if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) return ItemStack.EMPTY;
            return new ItemStack(BuiltInRegistries.ITEM.getValue(identifier));
        });
        if (stack.isEmpty()) {
            // Some mineable blocks have no inventory item; they remain selectable by name.
            g.text(this.font, "?", x + 5, y + 4, 0xFFAAAAAA, true);
        } else {
            g.item(stack, x, y);
        }
    }

    private SearchableListState listState(GuiElement.SearchableList list) {
        SearchableListState state = listStates.get(list.id());
        StringBuilder input = textInputValues.get(list.searchInputId());
        state.updateQuery(input == null ? "" : input.toString());
        return state;
    }

    private void renderSearchableList(GuiGraphicsExtractor g, GuiElement.SearchableList list,
                                      int px, int py, int pw, int ph, float mx, float my) {
        int w = list.size()[0], h = list.size()[1];
        int[] pos = resolveAnchor(list.anchor(), list.offset(), w, h, px, py, pw, ph);
        int x = pos[0], y = pos[1];
        SearchableListState state = listState(list);
        String status = state.matches().isEmpty() ? list.noResultsLabel()
                : state.isQuickPicks() ? list.emptyLabel() : state.matches().size() + " " + list.resultsLabel();
        g.text(this.font, status, x + (w - this.font.width(status)) / 2, y, 0xFFAAAAAA, true);
        for (int row = 0; row < state.visibleRows(); row++) {
            GuiElement.SearchOption option = state.row(row);
            if (option == null) break;
            int ry = y + 16 + row * 24;
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + 20;
            String texture = hover && list.textureHover() != null ? list.textureHover() : list.texture();
            if (texture != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, resolveTexture(texture), x, ry, 0f, 0f, w, 20, w, 20);
            } else {
                drawBorderedBox(g, x, ry, w, 20, hover ? 0xFF505050 : 0xFF303030, 0xFF808080);
            }
            var iconPose = g.pose();
            iconPose.pushMatrix();
            iconPose.translate(x + 12, ry + 4);
            iconPose.scale(0.75f, 0.75f);
            renderItemIcon(g, option.itemId(), 0, 0);
            iconPose.popMatrix();
            String label = option.label();
            int available = w - 38;
            if (this.font.width(label) > available) {
                label = this.font.plainSubstrByWidth(label, available - this.font.width("…")) + "…";
            }
            g.text(this.font, label, x + 28, ry + (20 - this.font.lineHeight) / 2,
                    hover ? 0xFFFFFFA0 : 0xFFFFFFFF, true);
            if (hover) hoverTooltip = option.label();
        }
        if (state.maxFirst() > 0) {
            int trackY = y + 16, trackH = state.visibleRows() * 24 - 4;
            int thumbH = Math.max(12, trackH * state.visibleRows() / state.matches().size());
            int thumbY = trackY + Math.round((trackH - thumbH) * (float) state.first() / state.maxFirst());
            g.fill(x + w + 4, trackY, x + w + 10, trackY + trackH, 0xFF252525);
            g.fill(x + w + 4, thumbY, x + w + 10, thumbY + thumbH, 0xFFC5A36B);
        }
        resolvedLists.add(new ResolvedList(list, x, y));
    }

    private void scrollListToMouse(ResolvedList list, float y) {
        SearchableListState state = listState(list.element());
        int trackH = state.visibleRows() * 24 - 4;
        int thumbH = Math.max(12, trackH * state.visibleRows() / Math.max(1, state.matches().size()));
        float fraction = (y - list.y() - 16 - thumbH / 2f) / Math.max(1, trackH - thumbH);
        state.scrollTo(Math.round(fraction * state.maxFirst()));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        float mx = (float) ((x - canvasX) / scale), my = (float) ((y - canvasY) / scale);
        for (ResolvedList list : resolvedLists) {
            if (mx >= list.x() && mx < list.x() + list.element().size()[0] + 10
                    && my >= list.y() && my < list.y() + list.element().size()[1]) {
                SearchableListState state = listState(list.element());
                if (vertical != 0) state.scrollTo(state.first() - (int) Math.copySign(Math.max(1, Math.round(Math.abs(vertical) * 3)), vertical));
                return true;
            }
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingListId != null && event.button() == 0) {
            for (ResolvedList list : resolvedLists) {
                if (list.element().id().equals(draggingListId)) {
                    scrollListToMouse(list, (float) ((event.y() - canvasY) / scale));
                    return true;
                }
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && draggingListId != null) {
            draggingListId = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void renderUnknown(GuiGraphicsExtractor g, GuiElement.Unknown unknown,
                                int px, int py, int pw, int ph) {
        LOGGER.warn("[HouseOfEL] Unknown element type '{}' (id='{}')",
                unknown.typeName(), unknown.id());
        int w = 80, h = 20;
        int[] pos = resolveAnchor(unknown.anchor(), unknown.offset(), w, h, px, py, pw, ph);
        g.fill(pos[0], pos[1], pos[0] + w, pos[1] + h, 0xFFFF00FF);
        g.text(this.font, unknown.typeName(), pos[0] + 2, pos[1] + 6,
                0xFF000000, false);
    }

    // ---- Helpers ----

    private void drawBorderedBox(GuiGraphicsExtractor g,
                                  int x, int y, int w, int h,
                                  int bgColor, int borderColor) {
        g.fill(x, y, x + w, y + 1, borderColor);
        g.fill(x, y + h - 1, x + w, y + h, borderColor);
        g.fill(x, y + 1, x + 1, y + h - 1, borderColor);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, borderColor);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bgColor);
    }

    private int[] resolveAnchor(Anchor anchor, int[] offset, int elemW, int elemH,
                                 int parentX, int parentY, int parentW, int parentH) {
        float fx = anchor.xFraction();
        float fy = anchor.yFraction();
        int x = Math.round(parentX + fx * parentW - fx * elemW) + offset[0];
        int y = Math.round(parentY + fy * parentH - fy * elemH) + offset[1];
        return new int[] { x, y };
    }

    private static int parseColor(String hex) {
        if (hex == null || hex.isEmpty()) return 0xFFFFFFFF;
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            int rgb = (int) Long.parseLong(clean, 16);
            if (clean.length() <= 6) rgb |= 0xFF000000;
            return rgb;
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }

    // ---- Input handling ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (event.button() != 0) return super.mouseClicked(event, consumed);

        float cmx = (float) ((event.x() - canvasX) / scale);
        float cmy = (float) ((event.y() - canvasY) / scale);

        // Open dropdown options take priority
        if (openDropdownId != null) {
            for (ResolvedDropdown rd : resolvedDropdowns) {
                if (!rd.element.id().equals(openDropdownId)) continue;
                int optH = this.font.lineHeight + 4;
                int optY = rd.y + rd.element.size()[1];
                for (int i = 0; i < rd.element.options().size(); i++) {
                    int oy = optY + i * optH;
                    if (cmx >= rd.x && cmx < rd.x + rd.element.size()[0]
                            && cmy >= oy && cmy < oy + optH) {
                        dropdownValues.put(rd.element.id(), i);
                        openDropdownId = null;
                        return true;
                    }
                }
            }
            openDropdownId = null;
            return true;
        }

        // Text inputs — set focus
        for (ResolvedList list : resolvedLists) {
            int w = list.element().size()[0];
            SearchableListState state = listState(list.element());
            if (cmx >= list.x() + w + 4 && cmx < list.x() + w + 10
                    && cmy >= list.y() + 16 && cmy < list.y() + list.element().size()[1]
                    && state.maxFirst() > 0) {
                draggingListId = list.element().id();
                scrollListToMouse(list, cmy);
                return true;
            }
            if (cmx >= list.x() && cmx < list.x() + w && cmy >= list.y() + 16) {
                int row = (int) (cmy - list.y() - 16) / 24;
                GuiElement.SearchOption option = state.row(row);
                if (option != null && (cmy - list.y() - 16) % 24 < 20) {
                    Map<String, DispatchValue> values = new LinkedHashMap<>(gatherValues());
                    values.put(list.element().id(), new DispatchValue.StringVal(option.id()));
                    ClientPlayNetworking.send(new DispatchCustomPayload(
                            new DispatchPayload(screenId, list.element().action(), values)));
                    return true;
                }
            }
        }

        focusedTextInputId = null;
        for (ResolvedTextInput rt : resolvedTextInputs) {
            int w = rt.element.size()[0], h = rt.element.size()[1];
            if (cmx >= rt.x && cmx < rt.x + w && cmy >= rt.y && cmy < rt.y + h) {
                focusedTextInputId = rt.element.id();
                StringBuilder sb = textInputValues.get(rt.element.id());
                textCursorPos = sb != null ? sb.length() : 0;
                return true;
            }
        }

        // Toggles
        for (ResolvedToggle rt : resolvedToggles) {
            if (cmx >= rt.x && cmx < rt.x + rt.w && cmy >= rt.y && cmy < rt.y + rt.h) {
                toggleValues.compute(rt.element.id(), (k, v) -> v == null || !v);
                return true;
            }
        }

        // Dropdowns — toggle open
        for (ResolvedDropdown rd : resolvedDropdowns) {
            int w = rd.element.size()[0], h = rd.element.size()[1];
            if (cmx >= rd.x && cmx < rd.x + w && cmy >= rd.y && cmy < rd.y + h) {
                openDropdownId = rd.element.id();
                return true;
            }
        }

        // Buttons — dispatch with gathered values
        for (ResolvedButton rb : resolvedButtons) {
            if (!rb.element.enabled()) continue;
            int bw = rb.element.size()[0], bh = rb.element.size()[1];
            if (cmx >= rb.x && cmx < rb.x + bw && cmy >= rb.y && cmy < rb.y + bh) {
                ClientPlayNetworking.send(new DispatchCustomPayload(
                        new DispatchPayload(screenId, rb.element.action(), gatherValues())));
                return true;
            }
        }

        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (focusedTextInputId != null) {
            StringBuilder sb = textInputValues.get(focusedTextInputId);
            if (sb != null) {
                int key = event.key();
                if (key == GLFW.GLFW_KEY_BACKSPACE && textCursorPos > 0) {
                    sb.deleteCharAt(--textCursorPos);
                    return true;
                }
                if (key == GLFW.GLFW_KEY_DELETE && textCursorPos < sb.length()) {
                    sb.deleteCharAt(textCursorPos);
                    return true;
                }
                if (key == GLFW.GLFW_KEY_LEFT && textCursorPos > 0) {
                    textCursorPos--;
                    return true;
                }
                if (key == GLFW.GLFW_KEY_RIGHT && textCursorPos < sb.length()) {
                    textCursorPos++;
                    return true;
                }
                if (key == GLFW.GLFW_KEY_HOME) {
                    textCursorPos = 0;
                    return true;
                }
                if (key == GLFW.GLFW_KEY_END) {
                    textCursorPos = sb.length();
                    return true;
                }
            }
        }

        if (super.keyPressed(event)) return true;
        // Let charTyped receive the inventory-bound key while a text field owns focus.
        if (focusedTextInputId == null && this.minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (focusedTextInputId != null && event.isAllowedChatCharacter()) {
            StringBuilder sb = textInputValues.get(focusedTextInputId);
            if (sb != null) {
                sb.insert(textCursorPos, event.codepointAsString());
                textCursorPos += event.codepointAsString().length();
                return true;
            }
        }
        return super.charTyped(event);
    }

    private Map<String, DispatchValue> gatherValues() {
        if (textInputValues.isEmpty() && toggleValues.isEmpty() && dropdownValues.isEmpty()) {
            return Map.of();
        }
        Map<String, DispatchValue> values = new LinkedHashMap<>();
        for (var entry : textInputValues.entrySet()) {
            values.put(entry.getKey(), new DispatchValue.StringVal(entry.getValue().toString()));
        }
        for (var entry : toggleValues.entrySet()) {
            values.put(entry.getKey(), new DispatchValue.BoolVal(entry.getValue()));
        }
        for (var entry : dropdownValues.entrySet()) {
            values.put(entry.getKey(), new DispatchValue.IntVal(entry.getValue()));
        }
        return values;
    }

    @Override
    public void onClose() {
        if (!serverClosed) {
            ClientPlayNetworking.send(new ScreenClosedCustomPayload(
                    new ScreenClosedPayload(screenId)));
        }
        super.onClose();
    }
}
