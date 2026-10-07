package com.houseofel.builder.visual;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.houseofel.builder.visual.GroundworkerAnimation.Clip.*;

class GroundworkerAnimationTest {
    @Test void drawsOncePerSessionAndExplicitlyTransitionsOutOfHold() {
        List<GroundworkerAnimation.Clip> played = new ArrayList<>();
        var a = new GroundworkerAnimation((c, h) -> { played.add(c); assertEquals(c == DRAW, h); });
        a.tick(0,true,false,false);
        for (int t=1;t<200;t++) a.tick(t,true,false,false);
        assertEquals(List.of(DRAW,DIG),played);
        a.tick(200,true,true,false); a.tick(201,true,false,false);
        assertEquals(DIG,a.clip()); assertEquals(1,played.stream().filter(c -> c==DRAW).count());
        a.tick(202,false,false,false); assertEquals(STOW,a.clip());
        a.tick(271,false,false,false); assertEquals(STOW,a.clip());
        a.tick(272,false,false,false); assertEquals(IDLE,a.clip());
        a.tick(273,true,false,false); assertEquals(DRAW,a.clip());
    }
    @Test void endingDuringDrawStillStowsOnce() {
        List<GroundworkerAnimation.Clip> played=new ArrayList<>();
        var a=new GroundworkerAnimation((c,h)->played.add(c));
        a.tick(0,true,false,false);a.tick(20,false,false,false);a.tick(70,false,false,false);
        assertEquals(List.of(DRAW,STOW),played);a.tick(140,false,false,false);assertEquals(IDLE,a.clip());
    }
    @Test void levelupImmediatelyRecoversThenIntentionallySnapsToIdle() {
        List<GroundworkerAnimation.Clip> played=new ArrayList<>();
        var a=new GroundworkerAnimation((c,h)->played.add(c));
        a.tick(0,false,false,false);a.levelUp();a.tick(1,false,false,false);
        a.tick(90,false,false,false);assertEquals(LEVELUP,a.clip());
        a.tick(91,false,false,false);assertEquals(RECOVER,a.clip());
        a.tick(200,false,false,false);assertEquals(RECOVER,a.clip());
        a.tick(201,false,false,false);assertEquals(IDLE,a.clip());
        assertEquals(List.of(IDLE,LEVELUP,RECOVER,IDLE),played);
    }
    @Test void movingRustAndActionEventsReturnToCorrectBaseline() {
        var a=new GroundworkerAnimation((c,h)->{});
        a.tick(0,false,false,true);assertEquals(RUSTED_IDLE,a.clip());
        a.tick(1,false,true,true);assertEquals(WALK,a.clip());
        a.greet();a.tick(2,false,false,true);assertEquals(GREET,a.clip());
        a.tick(44,false,false,true);assertEquals(RUSTED_IDLE,a.clip());
        a.place();a.tick(45,false,false,false);assertEquals(PLACE,a.clip());
        a.tick(69,false,false,false);assertEquals(IDLE,a.clip());
    }
    @Test void workContinuesThroughLevelupWithoutGameplayMutations() {
        var a=new GroundworkerAnimation((c,h)->{});
        a.tick(0,true,false,false);a.tick(70,true,false,false);a.levelUp();a.tick(71,true,false,false);
        assertEquals(LEVELUP,a.clip());a.tick(161,true,false,false);assertEquals(RECOVER,a.clip());
        a.tick(271,true,false,false);assertEquals(IDLE,a.clip());
        a.tick(272,true,false,false);assertEquals(DIG,a.clip(), "level-up must not start a second draw in the same work session");
    }
    @Test void placementBurstsCoalesceAndEventsDuringPlaceAreDropped() {
        List<GroundworkerAnimation.Clip> played = new ArrayList<>();
        var a = new GroundworkerAnimation((c, h) -> played.add(c));
        a.tick(0, true, false, false);
        for (int i = 0; i < 100; i++) a.place();
        a.tick(70, true, false, false);
        assertEquals(PLACE, a.clip());
        assertEquals(1, played.stream().filter(c -> c == PLACE).count());
        for (int i = 0; i < 100; i++) a.place();
        a.tick(94, false, false, false);
        assertEquals(STOW, a.clip());
        for (int t = 95; t < 500; t++) a.tick(t, false, false, false);
        assertEquals(IDLE, a.clip());
        assertEquals(1, played.stream().filter(c -> c == PLACE).count(),
                "queued and playing placement bursts must not leave a backlog after the job");
        a.place();
        a.tick(500, false, false, false);
        assertEquals(PLACE, a.clip(), "a fresh event is accepted after the previous clip ends");
        assertEquals(2, played.stream().filter(c -> c == PLACE).count());
    }
    @Test void doubleSpeedScalesWorkClipsButPreservesAmbientAndCelebrationPacing() {
        var a = new GroundworkerAnimation((c, h) -> {}, 2.0);
        for (var clip : GroundworkerAnimation.Clip.values()) {
            assertEquals(clip == IDLE || clip == WALK || clip == GREET || clip == LEVELUP || clip == RECOVER
                    ? clip.ticks : (clip.ticks + 1L) / 2, a.scaledTicks(clip), clip.name);
        }
        assertEquals(50, a.scaledTicks(100), "loop preview window also scales");
        a.tick(0, true, false, false);
        a.tick(34, true, false, false); assertEquals(DRAW, a.clip());
        a.tick(35, true, false, false); assertEquals(DIG, a.clip());
        a.place(); a.tick(36, true, false, false);
        a.tick(47, true, false, false); assertEquals(PLACE, a.clip());
        a.tick(48, true, false, false); assertEquals(DIG, a.clip());
        a.greet(); a.tick(49, true, false, false);
        a.tick(70, true, false, false); assertEquals(GREET, a.clip(), "still greeting at the old 2x deadline");
        a.tick(90, true, false, false); assertEquals(GREET, a.clip());
        a.tick(91, true, false, false); assertEquals(DIG, a.clip());
        a.tick(92, false, false, false); assertEquals(STOW, a.clip());
        a.tick(126, false, false, false); assertEquals(STOW, a.clip());
        a.tick(127, false, false, false); assertEquals(IDLE, a.clip());
        a.levelUp(); a.tick(128, false, false, false);
        a.tick(217, false, false, false); assertEquals(LEVELUP, a.clip());
        a.tick(218, false, false, false); assertEquals(RECOVER, a.clip());
        a.tick(327, false, false, false); assertEquals(RECOVER, a.clip());
        a.tick(328, false, false, false); assertEquals(IDLE, a.clip());
    }
    @Test void fractionalSpeedRoundsDurationsUpAndRejectsInvalidSpeed() {
        var a = new GroundworkerAnimation((c, h) -> {}, 1.5);
        assertEquals(47, a.scaledTicks(70));
        assertEquals(1, a.scaledTicks(1));
        for (double speed : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new GroundworkerAnimation((c, h) -> {}, speed));
        }
    }

    @Test void timedTransitionsWaitForRenderedEndPoseIncludingLevelupAndDraw() {
        boolean[] ready = {false};
        var a = new GroundworkerAnimation((c, h) -> ready[0] = false, 2, () -> ready[0]);
        a.tick(0, true, false, false);
        a.tick(35, true, false, false); assertEquals(DRAW, a.clip());
        ready[0] = true; a.tick(36, true, false, false); assertEquals(DIG, a.clip());
        a.levelUp(); a.tick(37, true, false, false);
        a.tick(127, true, false, false); assertEquals(LEVELUP, a.clip());
        ready[0] = true; a.tick(128, true, false, false); assertEquals(RECOVER, a.clip());
        a.tick(238, true, false, false); assertEquals(RECOVER, a.clip());
        ready[0] = true; a.tick(239, true, false, false); assertEquals(IDLE, a.clip());
    }

    @Test void idleAndWalkKeepAuthoredPaceWhileRustedIdleAndDigScale() {
        assertEquals(1f, GroundworkerAnimation.speedFor("idle", 2.0f));
        assertEquals(1f, GroundworkerAnimation.speedFor("walk", 2.0f));
        assertEquals(1f, GroundworkerAnimation.speedFor("greet", 2.0f));
        assertEquals(1f, GroundworkerAnimation.speedFor("levelup", 2.0f));
        assertEquals(1f, GroundworkerAnimation.speedFor("levelup_recover", 2.0f));
        assertEquals(2f, GroundworkerAnimation.speedFor("rusted_idle", 2.0f));
        assertEquals(2f, GroundworkerAnimation.speedFor("dig", 2.0f));
        assertEquals(2f, GroundworkerAnimation.speedFor("shovel_draw", 2.0f));
        assertEquals(2f, GroundworkerAnimation.speedFor("shovel_stow", 2.0f));
        assertEquals(2f, GroundworkerAnimation.speedFor("place", 2.0f));
    }

    @Test void greetingPreviewKeepsAuthoredDeadlineAndWaitsForRenderedPose() {
        boolean[] ready = {false};
        var a = new GroundworkerAnimation((c, h) -> {}, 2, () -> ready[0]);
        a.preview(GREET, 100);
        a.tick(121, false, false, false); assertEquals(GREET, a.clip());
        a.tick(141, false, false, false); assertEquals(GREET, a.clip());
        a.tick(142, false, false, false); assertEquals(GREET, a.clip(), "must still await the final rendered pose");
        ready[0] = true;
        a.tick(143, false, false, false); assertEquals(IDLE, a.clip());
        assertEquals(42, a.scaledTicks(GREET));
    }

}
