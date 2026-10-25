package com.afs.guard;

/** Frame-rate independence and continuity protect repeated/interruptible dock gestures. */
public final class DockSpringTest {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static void main(String[] args) {
        DockSpring a=new DockSpring(0,300,.55),b=new DockSpring(0,300,.55);
        a.target=b.target=4;
        for(int i=0;i<18;i++)a.advance(1.0/60);
        for(int i=0;i<36;i++)b.advance(1.0/120);
        check(Math.abs(a.value-b.value)<.000001,"60 Hz and 120 Hz follow the same trajectory");
        check(Math.abs(a.velocity-b.velocity)<.000001,"velocity is independent of frame rate");
        double value=a.value,velocity=a.velocity;
        a.target=0;a.tune(1024,.9);
        check(a.value==value&&a.velocity==velocity,"retargeting neither jumps nor drops velocity");
        for(int i=0;i<240;i++)a.advance(1.0/120);
        check(!a.moving()&&a.value==0&&a.velocity==0,"reversal converges to an exact resting state");
        DockSpring critical=new DockSpring(1,100,1);critical.target=0;
        double previous=critical.value;
        for(int i=0;i<120;i++){critical.advance(1.0/60);check(critical.value>=0&&critical.value<=previous,"critical glow decay never overshoots");previous=critical.value;}
        DockSpring delayed=new DockSpring(0,311,.593);delayed.target=1;delayed.advance(2);
        check(Double.isFinite(delayed.value)&&Math.abs(delayed.value-1)<.001,"a delayed frame cannot explode the spring");
        delayed.snap(3);check(delayed.value==3&&delayed.target==3&&delayed.velocity==0&&!delayed.moving(),"reduce-motion snap leaves no residual motion");
        System.out.println("Dock springs: "+checks+" checks passed");
    }
}
