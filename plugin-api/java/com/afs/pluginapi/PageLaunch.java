package com.afs.pluginapi;

import android.os.Parcel;
import android.os.Parcelable;

/** Values sent to a plugin page during preparePage. No Context, View or Intent. */
public final class PageLaunch implements Parcelable {
    public final long requestId;
    public final String pageId;
    public final int protocol;
    public final int themeBg;
    public final int themeSurface;
    public final int themeText;
    public final int themeText2;
    public final int themeTrack;
    public final int themeAccent;
    public final int themeRed;
    public final int themeGreen;
    public final boolean dark;
    public final String locale;
    public final float fontScale;
    public final boolean reduceMotion;

    public PageLaunch(long requestId, String pageId, int protocol, int themeBg, int themeSurface,
                      int themeText, int themeText2, int themeTrack, int themeAccent,
                      int themeRed, int themeGreen, boolean dark, String locale,
                      float fontScale, boolean reduceMotion) {
        this.requestId = requestId;
        this.pageId = ParcelSafe.fit(pageId, PluginProtocol.MAX_ID);
        this.protocol = protocol;
        this.themeBg = themeBg;
        this.themeSurface = themeSurface;
        this.themeText = themeText;
        this.themeText2 = themeText2;
        this.themeTrack = themeTrack;
        this.themeAccent = themeAccent;
        this.themeRed = themeRed;
        this.themeGreen = themeGreen;
        this.dark = dark;
        this.locale = ParcelSafe.fit(locale, 35);
        this.fontScale = Float.isNaN(fontScale) || fontScale <= 0f ? 1f : Math.min(3f, fontScale);
        this.reduceMotion = reduceMotion;
    }

    private PageLaunch(Parcel in) {
        requestId = ParcelSafe.readLong(in);
        pageId = ParcelSafe.read(in, PluginProtocol.MAX_ID, "pageId");
        protocol = ParcelSafe.readInt(in);
        themeBg = ParcelSafe.readInt(in);
        themeSurface = ParcelSafe.readInt(in);
        themeText = ParcelSafe.readInt(in);
        themeText2 = ParcelSafe.readInt(in);
        themeTrack = ParcelSafe.readInt(in);
        themeAccent = ParcelSafe.readInt(in);
        themeRed = ParcelSafe.readInt(in);
        themeGreen = ParcelSafe.readInt(in);
        dark = ParcelSafe.readCount(in,1,"boolean") != 0;
        locale = ParcelSafe.read(in, 35, "locale");
        fontScale = ParcelSafe.readFloat(in);
        if(fontScale<=0f||fontScale>3f)throw new android.os.BadParcelableException("invalid font scale");
        reduceMotion = ParcelSafe.readCount(in,1,"boolean") != 0;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(requestId);
        ParcelSafe.write(out, pageId, PluginProtocol.MAX_ID);
        out.writeInt(protocol);
        out.writeInt(themeBg);
        out.writeInt(themeSurface);
        out.writeInt(themeText);
        out.writeInt(themeText2);
        out.writeInt(themeTrack);
        out.writeInt(themeAccent);
        out.writeInt(themeRed);
        out.writeInt(themeGreen);
        out.writeInt(dark ? 1 : 0);
        ParcelSafe.write(out, locale, 35);
        out.writeFloat(fontScale);
        out.writeInt(reduceMotion ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<PageLaunch> CREATOR = new Creator<PageLaunch>() {
        @Override public PageLaunch createFromParcel(Parcel in) { return new PageLaunch(in); }
        @Override public PageLaunch[] newArray(int size) { return new PageLaunch[size]; }
    };
}
