package com.houseofel.builder.gui;

import com.houseofel.builder.choice.MilestoneChoiceRegistry;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.common.net.*;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ForkScreenLayoutTest {
    @Test void milestoneDescriptionsSurviveV3RoundTripInVisibleLinesForEveryFork() {
        assertEquals(3, GuiConstants.SCHEMA_VERSION);
        for (String parent : List.of("QUARRYMAN", "LANDSCAPER")) {
            for (int level : List.of(8, 16)) {
                var source = MilestoneChoiceRegistry.optionsFor(Specialization.GROUNDWORKER, level, parent);
                var options = source.stream().map(o -> new ForkScreenLayout.Option(o.storedValue(),
                        o.label() + " (current)", o.description())).toList();
                var screen = OpenScreenPayload.fromBytes(ForkScreenLayout.create("fork", "Montgomery — Reconsider Your Path", options).toBytes());
                var panel = (GuiElement.Panel) screen.root().getFirst();
                var buttons = panel.children().stream().filter(GuiElement.Button.class::isInstance)
                        .map(GuiElement.Button.class::cast).toList();
                assertEquals(3, buttons.size());
                for (int i = 0; i < source.size(); i++) {
                    int index = i;
                    String visible = panel.children().stream().filter(GuiElement.Label.class::isInstance)
                            .map(GuiElement.Label.class::cast).filter(l -> l.id().startsWith("description_" + index + "_"))
                            .map(GuiElement.Label::text).collect(java.util.stream.Collectors.joining(" "));
                    assertEquals(source.get(i).description(), visible);
                    assertEquals(source.get(i).label() + " (current)", buttons.get(i).text());
                    assertTrue(buttons.get(i).enabled());
                }
                assertBounds(panel);
            }
        }
    }

    @Test void recruitmentButtonsDisableComingSoonSpecializationsWithoutHidingDescriptions() {
        var options = Arrays.stream(Specialization.values()).map(ForkScreenLayout::specializationOption).toList();
        var panel = (GuiElement.Panel) ForkScreenLayout.create("fork", "New Helper — Specialization", options).root().getFirst();
        var buttons = panel.children().stream().filter(GuiElement.Button.class::isInstance)
                .map(GuiElement.Button.class::cast).toList();
        assertEquals(List.of("pick:GROUNDWORKER", "pick:LUMBERJACK", "pick:FARMER", "cancel"),
                buttons.stream().map(GuiElement.Button::action).toList());
        assertTrue(buttons.getFirst().enabled());
        assertNull(buttons.getFirst().tooltip());
        for (int i : List.of(1, 2)) {
            assertFalse(buttons.get(i).enabled());
            assertEquals("Coming soon", buttons.get(i).tooltip());
            assertEquals("houseofel:gui/button_disabled", buttons.get(i).textureDisabled());
            assertTrue(buttons.get(i).text().endsWith(" (Coming soon)"));
        }
        assertBounds(panel);
    }

    @Test void longestDescriptionsStayCenteredInsideButtonsAcrossWindowsAndGuiScales() {
        var layouts = new java.util.ArrayList<OpenScreenPayload>();
        layouts.add(ForkScreenLayout.create("new", "New Helper — Specialization",
                Arrays.stream(Specialization.values()).map(ForkScreenLayout::specializationOption).toList()));
        for (String parent : List.of("QUARRYMAN", "LANDSCAPER")) {
            for (int level : List.of(8,16)) {
                layouts.add(ForkScreenLayout.create("choice", "Montgomery — Reconsider Your Path",
                        MilestoneChoiceRegistry.optionsFor(Specialization.GROUNDWORKER,level,parent).stream()
                                .map(o -> new ForkScreenLayout.Option(o.storedValue(),o.label(),o.description())).toList()));
            }
        }
        for (var payload : layouts) {
            var panel = (GuiElement.Panel) payload.root().getFirst();
            assertBounds(panel);
            for (int[] window : List.of(new int[]{640,480},new int[]{854,480},new int[]{1280,720},
                    new int[]{1920,1080},new int[]{2560,1440})) {
                for (int guiScale : List.of(1,2,3,4,6)) {
                    int w = (int)Math.ceil(window[0]/(double)guiScale);
                    int h = (int)Math.ceil(window[1]/(double)guiScale);
                    // Mirror HouseOfElScreen.computeCanvas/resolveAnchor; GUI scale has
                    // already converted physical pixels to the client's screen dimensions.
                    double scale = Math.min(1,Math.min(w/(double)panel.size()[0],h/(double)panel.size()[1]));
                    double center = w/2.0;
                    double buttonLeft = center - ForkScreenLayout.BUTTON_WIDTH*scale/2;
                    double buttonRight = center + ForkScreenLayout.BUTTON_WIDTH*scale/2;
                    for (var element : panel.children()) {
                        if (!(element instanceof GuiElement.Label label) || !label.id().startsWith("description_")) continue;
                        double widthBound = ForkScreenLayout.textWidthBound(label.text())*scale;
                        assertTrue(center-widthBound/2 >= buttonLeft, label.text());
                        assertTrue(center+widthBound/2+scale <= buttonRight, label.text()); // shadow
                    }
                    assertTrue(panel.size()[1]*scale <= h+0.001);
                }
            }
        }
    }

    @Test void wrapPreservesTextIncludingWidePunctuationAndSplitsLongTokens() {
        String text = "A wide — @ ~ description with an unusuallylongunbrokenwordthatcannotfitononebuttonwidthline.";
        var lines = ForkScreenLayout.wrap(text,ForkScreenLayout.BUTTON_WIDTH-4);
        assertTrue(lines.size()>1);
        assertEquals(text.replace(" ",""),String.join("",lines).replace(" ",""));
        assertTrue(lines.stream().allMatch(l -> ForkScreenLayout.textWidthBound(l)<=ForkScreenLayout.BUTTON_WIDTH-4));
    }

    @Test void namingTextboxAndValidationMessageRoundTripOnSchemaV3() {
        assertEquals(3,GuiConstants.SCHEMA_VERSION);
        var screen = OpenScreenPayload.fromBytes(ForkScreenLayout.nameScreen("naming","Ann Marie",
                "That Helper name is already in use.").toBytes());
        var panel = (GuiElement.Panel)screen.root().getFirst();
        var input = panel.children().stream().filter(GuiElement.TextInput.class::isInstance)
                .map(GuiElement.TextInput.class::cast).findFirst().orElseThrow();
        assertEquals("name",input.id());
        assertEquals("Ann Marie",input.initial());
        assertEquals(Anchor.TOP_CENTER,input.anchor());
        assertEquals(240,input.size()[0]);
        assertEquals(List.of("recruit","cancel"),panel.children().stream().filter(GuiElement.Button.class::isInstance)
                .map(GuiElement.Button.class::cast).map(GuiElement.Button::action).toList());
    }

    private void assertBounds(GuiElement.Panel panel) {
        int previousBottom = 0;
        for (var element : panel.children()) {
            if (element instanceof GuiElement.Image) continue;
            int y = element.offset()[1];
            int height = element instanceof GuiElement.Button b ? b.size()[1] : 9;
            assertTrue(y >= previousBottom, "No overlapping text/buttons: " + element.id());
            assertTrue(y + height <= panel.size()[1] - 18, element.id());
            previousBottom = y + height;
            if (element instanceof GuiElement.Label l) {
                int limit = l.id().startsWith("description_") ? ForkScreenLayout.BUTTON_WIDTH - 4 : 346;
                assertTrue(ForkScreenLayout.textWidthBound(l.text()) <= limit, l.text());
                assertEquals(Anchor.TOP_CENTER, l.anchor());
                assertEquals(0, l.offset()[0]);
            }
        }
    }
}
