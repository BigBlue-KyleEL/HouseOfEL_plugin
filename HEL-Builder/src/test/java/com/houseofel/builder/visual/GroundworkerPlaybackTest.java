package com.houseofel.builder.visual;

import com.google.gson.*;
import kr.toxicity.model.api.animation.*;
import kr.toxicity.model.api.bone.*;
import kr.toxicity.model.api.data.blueprint.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GroundworkerPlaybackTest {
    private static BoneName bone(String name) { return new BoneName(Set.of(), name, name); }
    private static Map<String, BlueprintAnimation> imported(JsonObject model) {
        Map<String, BlueprintAnimation> result = new HashMap<>();
        for (var element : model.getAsJsonArray("animations")) {
            var a = element.getAsJsonObject();
            Map<BoneName, BlueprintAnimator> animators = new HashMap<>();
            a.getAsJsonObject("animators").entrySet().forEach(e -> {
                var raw = e.getValue().getAsJsonObject();
                var bone = bone(raw.get("name").getAsString());
                animators.put(bone, new BlueprintAnimator(bone, new AnimationKeyframe(new AnimationProgress[]{AnimationProgress.EMPTY})));
            });
            var name = a.get("name").getAsString();
            result.put(name, new BlueprintAnimation(name, AnimationIterator.Type.LOOP, a.get("length").getAsFloat(),
                    false, animators, null, AnimationProgress.emptyStorage(0)));
        }
        return result;
    }
    private static JsonObject fixture() {
        var model = JsonParser.parseString("""
                {"meta":{"format_version":"5.0"},"animations":[{"name":"dig","length":1.8,"animators":{
                "a":{"name":"groundworker","type":"bone","keyframes":[]},
                "b":{"name":"shovel_motion","type":"bone","keyframes":[]},
                "c":{"name":"headband","type":"bone","keyframes":[]}}}]}
                """).getAsJsonObject();
        var a = model.getAsJsonArray("animations").get(0).getAsJsonObject();
        a.getAsJsonObject("animators").entrySet().forEach(e -> {
            var keys = e.getValue().getAsJsonObject().getAsJsonArray("keyframes");
            for (int i = 0; i <= 144; i++) {
                var key = new JsonObject(); key.addProperty("channel", "position");
                key.addProperty("time", i * 0.0125); key.addProperty("interpolation", "linear");
                var point = new JsonObject(); point.addProperty("x", i); point.addProperty("y", 0); point.addProperty("z", 0);
                var points = new JsonArray(); points.add(point); key.add("data_points", points); keys.add(key);
            }
        });
        return model;
    }
    /** Exercises BetterModel's actual state handler, not a mock timer. */
    private static int lastPoseTick(BlueprintAnimation clip) {
        var keyframes = clip.animator().values().iterator().next().keyframe();
        int[] tick = {0}, lastTick = {0};
        var state = new AnimationStateHandler<AnimationProgress>(AnimationProgress.EMPTY, (before, after) -> {
            if (after == keyframes.getLast()) lastTick[0] = tick[0];
        });
        state.addAnimation(clip.name(), AnimationIterator.Type.HOLD_ON_LAST.create(keyframes),
                AnimationModifier.builder().start(0).end(0).speed(1).build(), () -> {});
        while (lastTick[0] == 0 && tick[0] < 10000) { tick[0]++; state.tick(); }
        assertNotEquals(0, lastTick[0]);
        return lastTick[0];
    }
    @Test void denseSourceActuallyPlaysTwiceAsFastInBetterModel() {
        var source = fixture(); String before = source.toString();
        var one = GroundworkerPlayback.prepare(source, imported(source), 1).get("dig");
        var two = GroundworkerPlayback.prepare(source, imported(source), 2).get("dig");
        assertEquals(1.8f, one.length()); assertEquals(0.9f, two.length());
        assertEquals(73, one.animator().values().iterator().next().keyframe().size());
        assertEquals(37, two.animator().values().iterator().next().keyframe().size());
        assertEquals(2 * (lastPoseTick(two) - 2), lastPoseTick(one) - 2,
                "Subtract the engine's fixed two-tick startup; motion duration must halve");
        var endpoint = two.animator().get(bone("groundworker")).keyframe().getLast()
                .animate(new BoneMovement(), new BoneMovement());
        assertEquals(-9f, endpoint.position().x);
        assertEquals(before, source.toString(), "Resampling must not mutate the source model");
    }
    @Test void configuredSpeedDoesNotChangeAmbientOrCelebrationClips() {
        var source = fixture();
        var raw = source.getAsJsonArray("animations").get(0).getAsJsonObject();
        for (String name : List.of("idle", "walk", "greet", "levelup", "levelup_recover")) {
            raw.addProperty("name", name);
            var original = GroundworkerPlayback.prepare(source, imported(source), 1).get(name);
            var fastConfig = GroundworkerPlayback.prepare(source, imported(source), 2).get(name);
            assertEquals(original.length(), fastConfig.length());
            assertEquals(lastPoseTick(original), lastPoseTick(fastConfig));
        }
    }
    @Test void completionWaitsForEveryBoneAndFiresOnlyOncePerPlay() {
        var source = fixture(); var finished = new AtomicInteger();
        var prepared = GroundworkerPlayback.prepare(source, imported(source), 2).get("dig");
        var wrapped = GroundworkerPlayback.onLastPose(prepared, finished::incrementAndGet);
        var bones = new ArrayList<>(wrapped.animator().values());
        for (int i = 0; i < bones.size(); i++) {
            assertEquals(0, finished.get());
            bones.get(i).keyframe().getLast().animate(new BoneMovement(), new BoneMovement());
        }
        assertEquals(1, finished.get());
        bones.forEach(b -> b.keyframe().getLast().animate(new BoneMovement(), new BoneMovement()));
        assertEquals(1, finished.get());
        assertTrue(wrapped.animator().containsKey(bone("headband")));
    }
    @Test void recoveryStepKeepsEquivalentAnglesAndSkipsInterpolation() {
        var source = fixture(); var raw = source.getAsJsonArray("animations").get(0).getAsJsonObject();
        var animator = raw.getAsJsonObject("animators").getAsJsonObject("a");
        animator.add("keyframes", JsonParser.parseString("""
                [{"channel":"rotation","time":0,"interpolation":"step","data_points":[{"x":-702.2467,"y":0,"z":0}]},
                {"channel":"rotation","time":0.05,"interpolation":"linear","data_points":[{"x":-342.2467,"y":0,"z":0}]}]
                """));
        var frames = GroundworkerPlayback.prepare(source, imported(source), 2).get("dig")
                .animator().get(bone("groundworker")).keyframe();
        assertTrue(frames.get(1).skipInterpolation());
        var initial = frames.get(0).animate(new BoneMovement(), new BoneMovement());
        var next = frames.get(1).animate(new BoneMovement(), new BoneMovement());
        assertEquals(360f, initial.rawRotation().x - next.rawRotation().x, 0.001f);
        assertEquals(1f, Math.abs(initial.rotation().dot(next.rotation())), 0.0001f);
    }
    @Test void optionalDeployedModelProbeChecksAllTenClipsWithoutWritingFiles() throws Exception {
        String path = System.getenv("GROUNDWORKER_TEST_MODEL");
        Assumptions.assumeTrue(path != null);
        var file = Path.of(path); byte[] before = Files.readAllBytes(file);
        var raw = JsonParser.parseString(new String(before, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        var one = GroundworkerPlayback.prepare(raw, imported(raw), 1);
        var two = GroundworkerPlayback.load(file, imported(raw), 2);
        assertEquals(10, two.size());
        for (var clip : two.values()) {
            int factor = Set.of("idle", "walk", "greet", "levelup", "levelup_recover").contains(clip.name()) ? 1 : 2;
            assertEquals(factor * (lastPoseTick(clip) - 2), lastPoseTick(one.get(clip.name())) - 2, clip.name());
            for (String accessory : List.of("headband", "apron", "shovel_sash")) {
                var keys = clip.animator().get(bone(accessory)).keyframe();
                for (var frame : keys.progresses()) {
                    var movement = frame.animate(new BoneMovement(), new BoneMovement());
                    assertEquals(0f, movement.position().length());
                    assertEquals(0f, movement.rawRotation().length());
                }
            }
            System.out.println(clip.name() + ": 1x=" + (lastPoseTick(one.get(clip.name())) - 2) * 25
                    + "ms; 2x=" + (lastPoseTick(clip) - 2) * 25 + "ms; bones=" + clip.animator().size());
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }
}
