package org.kioskclock.bridge;

import android.os.IBinder;
import android.os.Parcel;

/**
 * Minimal client for Lenovo's "charge_base" system service (android.app.IChargeBaseManager,
 * framework.jar of build 1.2.2.627). Transaction codes were decoded from the firmware's
 * IChargeBaseManager$Stub static fields (docs/DOCK.md).
 *
 * Only read calls and the light setter are reachable from this class. The update calls
 * (1 setUpdate, 9 mcuUpdate, 14 wirelessUpdate) flash dock firmware and are never used;
 * 8 setPower / 10 setLedAndPower are not used until their meaning is known.
 */
final class ChargeBase {
    private static final String DESCRIPTOR = "android.app.IChargeBaseManager";
    private static final int GET_MCU_VERSION = 2;
    private static final int GET_FIRMWARE_VERSION = 3;
    private static final int GET_STATUS = 6;
    private static final int SET_LED_BRI = 7;

    /** Parsed dock status frame: 80 51 08 LIGHT 01 CHARGER 00 CHECKSUM. */
    static final class Status {
        boolean valid;
        int light;
        int charger;
        String raw = "";
        boolean phonePresent() { return valid && charger != 1 && charger != 0; }
        boolean phoneCharging() { return valid && charger == 3; }
    }

    private IBinder binder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return (IBinder) sm.getMethod("getService", String.class).invoke(null, "charge_base");
        } catch (Throwable t) {
            return null;
        }
    }

    boolean available() { return binder() != null; }

    Status status() {
        Status s = new Status();
        byte[] b = callBytes(GET_STATUS);
        if (b == null) return s;
        StringBuilder hex = new StringBuilder();
        for (byte x : b) hex.append(String.format("%02x", x & 0xff));
        s.raw = hex.toString();
        if (b.length == 8 && (b[0] & 0xff) == 0x80 && (b[1] & 0xff) == 0x51) {
            int sum = 0;
            for (int i = 0; i < 7; i++) sum += b[i] & 0xff;
            if ((sum & 0xff) == (b[7] & 0xff)) {
                s.valid = true;
                s.light = b[3] & 0xff;
                s.charger = b[5] & 0xff;
            }
        }
        return s;
    }

    String mcuVersion() { return callString(GET_MCU_VERSION); }

    String firmwareVersion() { return callString(GET_FIRMWARE_VERSION); }

    boolean setLight(int level) {
        if (level < 0 || level > 10) throw new IllegalArgumentException("level must be 0..10");
        IBinder b = binder();
        if (b == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeByte((byte) level);
            b.transact(SET_LED_BRI, data, reply, 0);
            reply.readException();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private byte[] callBytes(int code) {
        IBinder b = binder();
        if (b == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reply.createByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private String callString(int code) {
        IBinder b = binder();
        if (b == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reply.readString();
        } catch (Exception e) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
