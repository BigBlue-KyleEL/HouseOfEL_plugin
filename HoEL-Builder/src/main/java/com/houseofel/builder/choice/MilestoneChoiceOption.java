package com.houseofel.builder.choice;

/**
 * One pickable option at a Choice slot: the display name, a first-person description of
 * what it means (shown in the picker GUI), and the value actually persisted — which must
 * equal the spec-specific enum's {@code .name()} (e.g. {@link GroundworkerL8Choice}).
 */
public record MilestoneChoiceOption(String label, String description, String storedValue) {
}
