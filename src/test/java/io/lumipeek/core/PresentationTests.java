package io.lumipeek.core;

import java.awt.*;
import java.time.Duration;
import java.util.List;
import java.util.*;

public final class PresentationTests {
    private static int passed;
    private static final PhotoFlow.Observation CLEAR = new PhotoFlow.Observation(false, false, false, true);
    private PresentationTests() {}
    private static void check(boolean value) { if (!value) throw new AssertionError("presentation check failed"); passed++; }
    private static long seconds(long value) { return Duration.ofSeconds(value).toNanos(); }
    static PreparedPhoto image() throws Exception {
        byte[] bytes = Wire.write(out -> {
            out.writeInt(1); out.writeInt(1); out.writeInt(3); out.write(new byte[3]);
            out.writeInt(8); out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        }, 100);
        return PreparedPhoto.read(Wire.input(bytes, 100));
    }
    public static void main(String[] args) throws Exception {
        try (PhotoFlow flow = new PhotoFlow(Duration.ofMinutes(3))) {
            UUID id = UUID.randomUUID(); long token = flow.select(id, 0);
            check(flow.tick(0, CLEAR).state() == PhotoFlow.State.SELECTED);
            flow.prepared(token, image());
            check(flow.tick(seconds(1), CLEAR).visible() && flow.tick(seconds(1), CLEAR).localDots());
            check(flow.tick(seconds(91), CLEAR).state() == PhotoFlow.State.ANALYZING);
            flow.analyzed(token, Optional.of(new Reaction("귀여운 사진이네", "작은 그림", "calm", true)));
            var ready = flow.tick(seconds(92), CLEAR);
            check(ready.photoId().equals(id) && ready.reactionEligible());
            check(!flow.shown(token, seconds(92), new PhotoFlow.Observation(false, false, true, true)));
            check(flow.shown(token, seconds(93), CLEAR));
            check(flow.tick(seconds(94), new PhotoFlow.Observation(false,false,true,true)).visible());
            check(flow.tick(seconds(99), CLEAR).end() == PhotoFlow.End.COMPLETE);
            long next = flow.select(UUID.randomUUID(), seconds(100));
            flow.prepared(next, image()); flow.cancel();
            flow.analyzed(next, Optional.of(new Reaction("old", "old", "calm", true)));
            check(flow.tick(seconds(101), CLEAR).end() == PhotoFlow.End.CANCELLED);
            long newest = flow.select(UUID.randomUUID(), seconds(102));
            PreparedPhoto stale = image(); flow.prepared(next, stale);
            try { stale.copyRgb(); throw new AssertionError(); } catch (IllegalStateException expected) { passed++; }
            flow.prepared(newest, image()); flow.otherSpeech();
            check(flow.tick(seconds(103), CLEAR).end() == PhotoFlow.End.SUPPRESSED);
        }
        for (int mode = 0; mode < 4; mode++) {
            try (PhotoFlow flow = new PhotoFlow(Duration.ofSeconds(5))) {
                long token = flow.select(UUID.randomUUID(), 0); flow.prepared(token, image());
                var observation = mode == 0 ? new PhotoFlow.Observation(true,false,false,true)
                    : mode == 1 ? new PhotoFlow.Observation(false,false,false,false) : CLEAR;
                if (mode == 2) flow.failed(token);
                check(flow.tick(seconds(6), observation).state() == PhotoFlow.State.FINISHED);
            }
        }
        long previous;
        try (PhotoFlow stopped = new PhotoFlow(Duration.ofSeconds(5))) {
            previous=stopped.select(UUID.randomUUID(),-100);
        }
        try (PhotoFlow restarted = new PhotoFlow(Duration.ofSeconds(5))) {
            long current=restarted.select(UUID.randomUUID(),0);
            check(current!=previous);
            restarted.prepared(previous,image());
            check(restarted.tick(0,CLEAR).state()==PhotoFlow.State.SELECTED);
            restarted.prepared(current,image()); restarted.analyzed(current,Optional.empty());
            restarted.failed(current);
            check(restarted.tick(1,CLEAR).end()==PhotoFlow.End.SKIPPED);
        }
        for(int phase=0;phase<4;phase++) {
            try(PhotoFlow flow=new PhotoFlow(Duration.ofSeconds(5))) {
                long token=flow.select(UUID.randomUUID(),0);
                if(phase>0) flow.prepared(token,image());
                if(phase>1) flow.analyzed(token,Optional.of(new Reaction("반가워","그림","calm",true)));
                if(phase>2) check(flow.shown(token,1,CLEAR));
                flow.cancel();
                var view=flow.tick(2,CLEAR); check(!view.visible() && view.end()==PhotoFlow.End.CANCELLED);
            }
        }
        BusyPolicy busy = new BusyPolicy();
        check(!busy.requestOnce(false,false,false,true,0));
        check(!busy.requestOnce(true,true,false,true,0));
        check(!busy.requestOnce(true,false,true,true,0));
        check(busy.requestOnce(true,false,false,true,0));
        check(!busy.localDotsOnly(seconds(89)) && busy.localDotsOnly(seconds(90)));
        check(!busy.requestOnce(true,false,false,true,seconds(120)));
        PreviewFollow follow = new PreviewFollow();
        Rectangle mascot = new Rectangle(400,300,64,64), original = new Rectangle(mascot);
        List<Rectangle> screens = List.of(new Rectangle(0,0,1920,1080), new Rectangle(-1280,-100,1280,1024));
        Dimension size = new Dimension(180,200);
        Rectangle first = follow.update(mascot,true,screens,size,0).orElseThrow();
        check(first.x >= mascot.getMaxX() + 48 && mascot.equals(original));
        Rectangle moved = follow.update(new Rectangle(600,300,64,64),true,screens,size,0.03).orElseThrow();
        check(moved.x > first.x && moved.x < 600 + 64 + 48);
        Rectangle second = follow.update(new Rectangle(-600,300,64,64),true,screens,size,0.03).orElseThrow();
        check(second.x < 0 && screens.get(1).contains(second));
        Rectangle edge = follow.update(new Rectangle(1900,1000,20,20),true,screens,size,0.03).orElseThrow();
        check(screens.get(0).contains(edge));
        check(follow.update(mascot,true,List.of(new Rectangle(0,0,20,20)),size,0.03).isEmpty());
        System.out.println("PASS: state/busy/preview geometry " + passed + " assertions (no real windows)");
    }
}
