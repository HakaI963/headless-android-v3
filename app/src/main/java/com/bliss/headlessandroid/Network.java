package com.bliss.headlessandroid;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Finds the address an MCP client should use to reach this phone.
 *
 * <p>The Wi-Fi IPv4 is preferred because that is where a desktop AI client
 * usually lives; the phone's own loopback is the fallback when no network is
 * up (for a client running on the phone itself, or over adb forward).
 */
final class Network {
    private Network() {
    }

    static String host(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int address = wifi.getConnectionInfo().getIpAddress();
                if (address != 0) {
                    return (address & 0xff) + "." + ((address >> 8) & 0xff) + "."
                            + ((address >> 16) & 0xff) + "." + ((address >> 24) & 0xff);
                }
            }
        } catch (Throwable ignored) {
        }
        return loopback();
    }

    static String loopback() {
        try {
            return InetAddress.getLoopbackAddress().getHostAddress();
        } catch (Throwable ignored) {
            return "127.0.0.1";
        }
    }

    /** Best-effort scan of the device's own interfaces for an IPv4 address. */
    static String anyAddress(Context context) {
        String wifi = host(context);
        if (!wifi.equals(loopback()) && !wifi.isEmpty()) {
            return wifi;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return loopback();
    }
}