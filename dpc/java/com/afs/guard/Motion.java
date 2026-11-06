package com.afs.guard;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
/** Framework-only motion tokens. Callers must also apply MotionPolicy. */
final class Motion {
    static final Interpolator OUT=new PathInterpolator(.16f,1f,.30f,1f);
    static final Interpolator OUT_SOFT=new PathInterpolator(.33f,1f,.68f,1f);
    static final Interpolator IN=new PathInterpolator(.70f,0f,.84f,0f);
    static final Interpolator IN_OUT=new PathInterpolator(.65f,0f,.35f,1f);
    static final long TAP=120, ENTER=200, EXIT=160, PAGE=280;
    private Motion(){}
}
