package com.afs.pluginapi;

import android.os.BadParcelableException;
import android.os.Parcel;

/** Explicit bounds shared by every protocol-1 Parcelable. */
public final class ParcelSafe {
    private ParcelSafe() {}

    public static String fit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public static void write(Parcel out, String value, int max) {
        out.writeString(fit(value, max));
    }

    public static String read(Parcel in, int max, String what) {
        int start=in.dataPosition();int length=readInt(in);
        if(length < -1 || length > max)throw new BadParcelableException(what+" bad string length");
        if(length>=0)require(in,(int)((((long)length+1)*2+3)&~3L));
        in.setDataPosition(start);
        String value = in.readString();
        if(length>=0&&(value==null||value.length()!=length))throw new BadParcelableException(what+" malformed string");
        if (value == null) value = "";
        if (value.length() > max) {
            throw new BadParcelableException(what + " too long: " + value.length());
        }
        return value;
    }

    public static int readCount(Parcel in, int max, String what) {
        int count = readInt(in);
        if (count < 0 || count > max) {
            throw new BadParcelableException(what + " bad count: " + count);
        }
        return count;
    }

    public static long readTime(Parcel in, String what) {
        long value = readLong(in);
        if (value < 0L) throw new BadParcelableException(what + " negative time");
        return value;
    }

    public static int readStatus(Parcel in, int[] allowed, String what) {
        int status = readInt(in);
        for (int value : allowed) if (value == status) return status;
        throw new BadParcelableException(what + " unknown status: " + status);
    }
    public static void require(Parcel in,int bytes){if(bytes<0||in.dataAvail()<bytes)throw new BadParcelableException("truncated protocol field");}
    public static int readInt(Parcel in){require(in,4);return in.readInt();}
    public static long readLong(Parcel in){require(in,8);return in.readLong();}
    public static float readFloat(Parcel in){require(in,4);float value=in.readFloat();if(Float.isNaN(value)||Float.isInfinite(value))throw new BadParcelableException("nonfinite protocol float");return value;}
    public static void readBinder(Parcel in){require(in,4);int start=in.dataPosition();in.readStrongBinder();if(in.dataPosition()<=start||in.dataPosition()>in.dataSize())throw new BadParcelableException("truncated Binder field");}
}
