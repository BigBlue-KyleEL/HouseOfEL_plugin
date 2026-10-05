package com.houseofel.builder.gui;

import com.houseofel.common.net.GuiElement;
import com.houseofel.common.net.OpenScreenPayload;
import com.houseofel.builder.npc.Specialization;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class JobAvailabilityTest {
    @Test void unavailableActionsAreRefusedServerSideWithExactRedMessage() {
        List<Component> messages = new ArrayList<>();
        for (String action : List.of("mine", "lumberjack", "farm")) {
            assertTrue(JobAvailability.refuseAction(action, messages::add));
        }
        assertEquals(3, messages.size());
        messages.forEach(message -> assertEquals(Component.text(
                "Coming soon — only Clearing and specialization jobs work right now.", NamedTextColor.RED), message));
        for (String action : List.of("clear", "quarry", "landscape", "cofferdam", "shaft_miner", "cancel")) {
            assertFalse(JobAvailability.refuseAction(action, messages::add));
        }
        assertEquals(3, messages.size());
    }

    @Test void javaButtonsRoundTripDisabledStyleAndTooltipWithoutDisablingSupportedJobs() {
        var options = Arrays.stream(TaskType.values()).map(type -> new JobMenuLayout.ButtonOption(
                "btn_" + type.name(), type.name().toLowerCase(Locale.ROOT), type.label())).toList();
        var screen = OpenScreenPayload.fromBytes(JobMenuLayout.taskTypeScreen("Jobs", List.of(), options).toBytes());
        var buttons = ((GuiElement.Panel) screen.root().getFirst()).children().stream()
                .filter(GuiElement.Button.class::isInstance).map(GuiElement.Button.class::cast).toList();
        assertEquals(TaskType.values().length, buttons.size());
        for (var button : buttons) {
            boolean unavailable = List.of("mine", "lumberjack", "farm").contains(button.action());
            assertEquals(!unavailable, button.enabled());
            assertEquals(unavailable ? "Coming soon" : null, button.tooltip());
            assertEquals("houseofel:gui/button_disabled", button.textureDisabled());
        }
    }

    @Test void bedrockKeepsExactComingSoonLabelsEvenForSpecialists() {
        for (var type : List.of(TaskType.MINE, TaskType.LUMBERJACK, TaskType.FARM)) {
            assertEquals(type.label() + " (Coming soon)", BedrockJobForm.taskLabel(type, null));
            for (var specialization : Specialization.values()) {
                assertEquals(type.label() + " (Coming soon)", BedrockJobForm.taskLabel(type, specialization));
            }
        }
        assertEquals("Clearing", BedrockJobForm.taskLabel(TaskType.CLEAR, null));
        assertEquals("Clearing ★ (specialty)", BedrockJobForm.taskLabel(TaskType.CLEAR, Specialization.GROUNDWORKER));
        assertEquals("Lvl.8: Quarryman", BedrockJobForm.taskLabel(TaskType.QUARRY, Specialization.GROUNDWORKER));
        assertEquals("Lvl.8: Landscaper", BedrockJobForm.taskLabel(TaskType.LANDSCAPE, Specialization.GROUNDWORKER));
        assertEquals("Lvl.16: Cofferdam", BedrockJobForm.taskLabel(TaskType.COFFERDAM, Specialization.GROUNDWORKER));
        assertEquals("Lvl.16: Shaft Miner", BedrockJobForm.taskLabel(TaskType.SHAFT_MINER, Specialization.GROUNDWORKER));
    }
}
