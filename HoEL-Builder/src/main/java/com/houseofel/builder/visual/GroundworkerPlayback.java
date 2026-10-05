package com.houseofel.builder.visual;

import com.google.gson.*;
import kr.toxicity.model.api.animation.*;
import kr.toxicity.model.api.bone.*;
import kr.toxicity.model.api.data.blueprint.*;
import kr.toxicity.model.api.data.raw.ModelMeta.FormatVersion;
import kr.toxicity.model.api.util.InterpolationUtil;
import kr.toxicity.model.api.util.function.FloatFunction;
import kr.toxicity.model.api.util.interpolator.VectorInterpolator;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only adapter for BetterModel 3.0.2's one-pose-per-25ms renderer. */
final class GroundworkerPlayback {
    static final float FRAME_SECONDS = 0.025f;
    private GroundworkerPlayback() {}

    static Map<String, BlueprintAnimation> load(Path source, Map<String, BlueprintAnimation> imported, float speed)
            throws IOException {
        try (var reader = Files.newBufferedReader(source)) {
            return prepare(JsonParser.parseReader(reader).getAsJsonObject(), imported, speed);
        }
    }

    static Map<String, BlueprintAnimation> prepare(JsonObject model, Map<String, BlueprintAnimation> imported, float speed) {
        GroundworkerAnimation.validateSpeed(speed);
        var version = FormatVersion.find(Integer.parseInt(model.getAsJsonObject("meta")
                .get("format_version").getAsString().split("\\.")[0]));
        Map<String, BlueprintAnimation> result = new HashMap<>();
        for (var element : model.getAsJsonArray("animations")) {
            var raw = element.getAsJsonObject();
            String name = raw.get("name").getAsString();
            var original = Objects.requireNonNull(imported.get(name), "Animation not loaded: " + name);
            float length = raw.get("length").getAsFloat();
            float clipSpeed = GroundworkerAnimation.speedFor(name, speed);
            if (!Float.isFinite(length) || length <= 0) throw new IllegalArgumentException("Invalid clip length: " + name);
            // Round up to the same server-tick deadline used by the controller, retaining the exact end pose.
            int serverTicks = Math.max(1, (int) Math.ceil(length * 20.0 / clipSpeed - 0.00001));
            int intervals = Math.multiplyExact(serverTicks, 2);
            Map<BoneName, BlueprintAnimator> animators = new LinkedHashMap<>();
            for (var entry : raw.getAsJsonObject("animators").entrySet()) {
                var animator = entry.getValue().getAsJsonObject();
                var keys = animator.getAsJsonArray("keyframes");
                if (keys == null || keys.isEmpty()) continue;
                if (!animator.get("type").getAsString().equals("bone"))
                    throw new IllegalArgumentException("Unsupported effects animator in " + name);
                String boneName = animator.get("name").getAsString();
                var bone = original.animator().keySet().stream().filter(b -> b.rawName().equals(boneName))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Unloaded bone: " + boneName));
                Map<String, List<VectorPoint>> channels = new HashMap<>();
                for (var keyElement : keys) {
                    var key = keyElement.getAsJsonObject();
                    String channel = key.get("channel").getAsString();
                    var points = key.getAsJsonArray("data_points");
                    if (points.size() != 1) throw new IllegalArgumentException("Unsupported multi-point key in " + name);
                    var point = points.get(0).getAsJsonObject();
                    var vector = new Vector3f(number(point, "x"), number(point, "y"), number(point, "z"));
                    switch (channel) {
                        case "position" -> version.convertAnimationPosition(vector);
                        case "rotation" -> version.convertAnimationRotation(vector);
                        case "scale" -> version.convertAnimationScale(vector);
                        default -> throw new IllegalArgumentException("Unsupported channel: " + channel);
                    }
                    var interpolation = switch (key.get("interpolation").getAsString()) {
                        case "linear" -> VectorInterpolator.LINEAR;
                        case "step" -> VectorInterpolator.STEP;
                        default -> throw new IllegalArgumentException("Unsupported interpolation in " + name);
                    };
                    channels.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(new VectorPoint(
                            FloatFunction.of(vector), number(key, "time"),
                            new VectorPoint.BezierConfig(null, null, null, null), interpolation));
                }
                channels.values().forEach(c -> c.sort(Comparator.comparingDouble(VectorPoint::time)));
                var positions = channels.getOrDefault("position", List.of());
                var scales = channels.getOrDefault("scale", List.of());
                var rotations = channels.getOrDefault("rotation", List.of());
                var position = InterpolationUtil.interpolatorFor(positions);
                var scale = InterpolationUtil.interpolatorFor(scales);
                var rotation = InterpolationUtil.interpolatorFor(rotations);
                var frames = AnimationKeyframe.builder(intervals + 1,
                        animator.has("rotation_global") && animator.get("rotation_global").getAsBoolean());
                float previousTime = -1;
                for (int i = 0; i <= intervals; i++) {
                    float time = i == intervals ? length : Math.min(length, i * FRAME_SECONDS * clipSpeed);
                    var p = position.build(time); var s = scale.build(time); var r = rotation.build(time);
                    boolean step = p.skipInterpolation() || s.skipInterpolation() || r.skipInterpolation()
                            || crossedStep(positions, previousTime, time) || crossedStep(scales, previousTime, time)
                            || crossedStep(rotations, previousTime, time);
                    frames.write(i == 0 ? 0 : FRAME_SECONDS, p.vector(), s.vector(), r.vector(), step);
                    previousTime = time;
                }
                animators.put(bone, new BlueprintAnimator(bone, frames.build()));
            }
            if (animators.isEmpty()) throw new IllegalArgumentException("Empty animation: " + name);
            var empty = animators.values().iterator().next().keyframe().toEmpty();
            // The approved model has no effects. Do not carry an unscaled empty script timeline.
            result.put(name, new BlueprintAnimation(name, original.loop(), serverTicks / 20f,
                    original.override(), Map.copyOf(animators), null, empty));
        }
        return Map.copyOf(result);
    }

    private static float number(JsonObject object, String key) {
        float value = object.get(key).getAsFloat();
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite animation number");
        return value;
    }

    private static boolean crossedStep(List<VectorPoint> points, float before, float after) {
        for (int i = 1; i < points.size(); i++)
            if (!points.get(i - 1).isContinuous() && points.get(i).time() > before && points.get(i).time() <= after)
                return true;
        return false;
    }

    /** Per-play completion marker: every keyed bone must apply its final pose before a transition. */
    static BlueprintAnimation onLastPose(BlueprintAnimation source, Runnable completed) {
        var remaining = new AtomicInteger(source.animator().size());
        Map<BoneName, BlueprintAnimator> animators = new HashMap<>();
        source.animator().forEach((bone, animator) -> {
            var progresses = animator.keyframe().progresses().clone();
            var last = progresses[progresses.length - 1];
            var applied = new AtomicBoolean();
            progresses[progresses.length - 1] = new AnimationProgress() {
                public float time() { return last.time(); }
                public boolean skipInterpolation() { return last.skipInterpolation(); }
                public boolean globalRotation() { return last.globalRotation(); }
                public BoneMovement animate(BoneMovement movement, BoneMovement dest) {
                    var result = last.animate(movement, dest);
                    if (applied.compareAndSet(false, true) && remaining.decrementAndGet() == 0) completed.run();
                    return result;
                }
            };
            animators.put(bone, new BlueprintAnimator(bone, new AnimationKeyframe(progresses)));
        });
        return new BlueprintAnimation(source.name(), source.loop(), source.length(), source.override(),
                Map.copyOf(animators), source.script(), source.emptyAnimator());
    }
}
